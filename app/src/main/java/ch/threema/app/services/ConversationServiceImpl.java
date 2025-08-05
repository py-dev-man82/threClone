/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2014-2025 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.app.services;


import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.collections.Functional;
import ch.threema.app.collections.IPredicateNonNull;
import ch.threema.app.glide.AvatarOptions;
import ch.threema.app.managers.ListenerManager;
import ch.threema.app.messagereceiver.ContactMessageReceiver;
import ch.threema.app.messagereceiver.DistributionListMessageReceiver;
import ch.threema.app.messagereceiver.GroupMessageReceiver;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.utils.ContactUtil;
import ch.threema.app.utils.MessageUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.domain.models.IdentityState;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.storage.DatabaseService;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.ConversationModel;
import ch.threema.storage.models.ConversationTag;
import ch.threema.storage.models.ConversationTagModel;
import ch.threema.storage.models.DistributionListMessageModel;
import ch.threema.storage.models.DistributionListModel;
import ch.threema.storage.models.GroupMessageModel;
import ch.threema.storage.models.GroupModel;
import ch.threema.storage.models.MessageModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.ReceiverModel;

public class ConversationServiceImpl implements ConversationService {
    private static final Logger logger = LoggingUtil.getThreemaLogger("ConversationServiceImpl");

    private final Context context;
    private final @NonNull List<ConversationModel> conversationCache;
    private final ConversationTagService conversationTagService;
    private final DatabaseService databaseService;
    private final ContactService contactService;
    private final GroupService groupService;
    private final DistributionListService distributionListService;
    private final MessageService messageService;
    @NonNull
    private final ConversationCategoryService conversationCategoryService;
    private final BlockedIdentitiesService blockedIdentitiesService;
    private boolean initAllLoaded = false;

    static class ConversationResult {
        /**
         * The unique identifier of this conversation:
         * <p>
         * - Contacts: Identity
         * - Groups: DB primary key (id)
         * - Distribution lists: DB primary key (id)
         */
        public final @NonNull String identifier;

        /**
         * The message count in this conversation.
         */
        public final long messageCount;

        /**
         * Date of the last modification of this conversation.
         */
        public final @NonNull Date lastUpdate;

        /**
         * The database ID of the latest message in this conversation.
         */
        public final @Nullable Integer latestMessageId;

        ConversationResult(
            @NonNull String identifier,
            long messageCount,
            @NonNull Date lastUpdate,
            @Nullable Integer latestMessageId
        ) {
            this.identifier = identifier;
            this.messageCount = messageCount;
            this.lastUpdate = lastUpdate;
            this.latestMessageId = latestMessageId;
        }
    }

    public ConversationServiceImpl(
        Context context,
        CacheService cacheService,
        DatabaseService databaseService,
        ContactService contactService,
        GroupService groupService,
        DistributionListService distributionListService,
        MessageService messageService,
        @NonNull ConversationCategoryService conversationCategoryService,
        @NonNull BlockedIdentitiesService blockedIdentitiesService,
        ConversationTagService conversationTagService
    ) {
        this.context = context;
        this.databaseService = databaseService;
        this.contactService = contactService;
        this.groupService = groupService;
        this.distributionListService = distributionListService;
        this.messageService = messageService;
        this.conversationCategoryService = conversationCategoryService;
        this.blockedIdentitiesService = blockedIdentitiesService;
        this.conversationCache = cacheService.getConversationModelCache();
        this.conversationTagService = conversationTagService;
    }

    /**
     * Remove the conversation from the cache.
     * <p>
     * The onRemove event of the conversation listener will be notified.
     * <p>
     * Note: Because we can't guarantee that object IDs are always identical, we search for
     * all matching conversations (by UID) and remove them all.
     */
    private void removeFromCache(@NonNull ConversationModel conversationModel) {
        synchronized (this.conversationCache) {
            final List<ConversationModel> conversationModels = Functional.filter(
                this.conversationCache,
                (IPredicateNonNull<ConversationModel>) (c) -> c.getUid().equals(conversationModel.getUid())
            );
            this.conversationCache.removeAll(conversationModels);
        }

        // Notify listeners that the conversation was removed
        ListenerManager.conversationListeners.handle(listener -> listener.onRemoved(conversationModel));
    }

    @NonNull
    @Override
    public synchronized List<ConversationModel> getAll(boolean forceReloadFromDatabase) {
        return this.getAll(forceReloadFromDatabase, null);
    }

    @Override
    public synchronized @NonNull List<ConversationModel> getAll(
        boolean forceReloadFromDatabase,
        final @Nullable Filter filter
    ) {
        logger.debug("getAll forceReloadFromDatabase = {}", forceReloadFromDatabase);
        synchronized (this.conversationCache) {
            if (forceReloadFromDatabase || !this.initAllLoaded) {
                this.conversationCache.clear();
            }
            if (this.conversationCache.isEmpty()) {

                logger.debug("start selecting");
                for (ConversationModelParser<?, ?, ?> parser : new ConversationModelParser<?, ?, ?>[]{
                    new ContactConversationModelParser(),
                    new GroupConversationModelParser(),
                    new DistributionListConversationModelParser()
                }) {
                    parser.processAll();
                }

                this.updateTags();

                logger.debug("selection finished");
                this.initAllLoaded = true;
            }

            this.sort();

            //filter only if a filter object is set and one of the filter property contains a filter
            if (filter != null
                && (filter.onlyUnread()
                || filter.noDistributionLists()
                || filter.noHiddenChats()
                || filter.noInvalid()
                || !TestUtil.isEmptyOrNull(filter.filterQuery()))) {

                List<ConversationModel> filtered = this.conversationCache;
                if (filter.onlyUnread()) {
                    logger.debug("filter unread");
                    filtered = Functional.filter(filtered, new IPredicateNonNull<ConversationModel>() {
                        @Override
                        public boolean apply(@NonNull ConversationModel conversationModel) {
                            return conversationModel.hasUnreadMessage();
                        }
                    });
                }

                if (filter.noDistributionLists()) {
                    logger.debug("filter distribution lists");
                    filtered = Functional.filter(filtered, new IPredicateNonNull<ConversationModel>() {
                        @Override
                        public boolean apply(@NonNull ConversationModel conversationModel) {
                            return !conversationModel.isDistributionListConversation();
                        }
                    });
                }

                if (filter.noHiddenChats()) {
                    logger.debug("filter hidden lists");
                    filtered = Functional.filter(filtered, new IPredicateNonNull<ConversationModel>() {
                        @Override
                        public boolean apply(@NonNull ConversationModel conversationModel) {
                            return !conversationCategoryService.isPrivateChat(conversationModel.messageReceiver.getUniqueIdString());
                        }
                    });
                }

                if (filter.noInvalid()) {
                    logger.debug("filter chats with revoked contacts / left group that cannot receive messages");
                    filtered = Functional.filter(filtered, new IPredicateNonNull<ConversationModel>() {
                        @Override
                        public boolean apply(@NonNull ConversationModel conversationModel) {
                            if (conversationModel.isContactConversation()) {
                                return conversationModel.getContact() != null && conversationModel.getContact().getState() != IdentityState.INVALID;
                            } else if (conversationModel.isGroupConversation()) {
                                return conversationModel.getGroup() != null && groupService.isGroupMember(conversationModel.getGroup());
                            }
                            return true;
                        }
                    });
                }

                if (filter.onlyPersonal()) {
                    logger.debug("filter non-personal chats such as channels/broadcasts or blocked chats");
                    filtered = Functional.filter(filtered, new IPredicateNonNull<ConversationModel>() {
                        @Override
                        public boolean apply(@NonNull ConversationModel conversationModel) {
                            if (conversationModel.isContactConversation()) {
                                return !ContactUtil.isEchoEchoOrGatewayContact(conversationModel.getContact()) &&
                                    !blockedIdentitiesService.isBlocked(conversationModel.getContact().getIdentity());
                            }
                            return true;
                        }
                    });
                }

                if (!TestUtil.isEmptyOrNull(filter.filterQuery())) {
                    logger.debug("filter query");
                    filtered = Functional.filter(filtered, new IPredicateNonNull<ConversationModel>() {
                        @Override
                        public boolean apply(@NonNull ConversationModel conversationModel) {
                            return TestUtil.matchesConversationSearch(filter.filterQuery(), conversationModel.messageReceiver.getDisplayName());
                        }
                    });
                }

                return filtered;
            }
        }

        return new ArrayList<>(this.conversationCache);
    }

    @Override
    public @NonNull List<ConversationModel> getArchived(@Nullable String searchQuery) {
        List<ConversationModel> conversationModels = new ArrayList<>();

        for (ConversationModelParser parser : new ConversationModelParser[]{
            new ContactConversationModelParser(),
            new GroupConversationModelParser(),
            new DistributionListConversationModelParser()
        }) {
            parser.processArchived(conversationModels, searchQuery);
        }

        Collections.sort(
            conversationModels,
            (c1, c2) -> c2.getSortDate().compareTo(c1.getSortDate())
        );

        return conversationModels;
    }

    @Override
    public int getArchivedCount() {
        String query = "SELECT" +
            getArchivedContactsCountQuery() +
            " + " +
            getArchivedGroupsCountQuery() +
            " + " +
            getArchivedDistListsCountQuery();

        Cursor c = databaseService.getReadableDatabase().rawQuery(query, null);
        if (c != null) {
            try {
                c.moveToNext();
                return (int) c.getLong(0);
            } catch (Exception ignored) {
            } finally {
                c.close();
            }
        }
        return 0;
    }

    private String getArchivedContactsCountQuery() {
        return "(SELECT COUNT(DISTINCT c.identity) FROM contacts c " +
            "INNER JOIN message m " +
            "ON c.identity = m.identity " +
            "WHERE m.isSaved = 1 " +
            "AND c.isArchived = 1)";
    }

    private String getArchivedGroupsCountQuery() {
        return "(SELECT COUNT(DISTINCT g.id) FROM m_group g " +
            "LEFT OUTER JOIN m_group_message gm " +
            "ON gm.groupId = g.id " +
            "AND gm.isStatusMessage = 0 " +
            "AND gm.isSaved = 1 " +
            "AND g.isArchived = 1)";
    }

    private String getArchivedDistListsCountQuery() {
        return "(SELECT COUNT(DISTINCT d.id) FROM distribution_list d " +
            "LEFT OUTER JOIN distribution_list_message dm " +
            "ON dm.distributionListId = d.id " +
            "AND dm.isStatusMessage = 0 " +
            "AND dm.isSaved = 1 " +
            "WHERE d.isArchived = 1)";
    }

    @Override
    public void updateTags() {
        Set<String> pinTaggedUids = new HashSet<>();
        Set<String> unreadTaggedUids = new HashSet<>();
        for (ConversationTagModel tagModel : conversationTagService.getAll()) {
            if (ConversationTag.PINNED.value.equals(tagModel.getTag())) {
                pinTaggedUids.add(tagModel.getConversationUid());
            } else if (ConversationTag.MARKED_AS_UNREAD.value.equals(tagModel.getTag())) {
                unreadTaggedUids.add(tagModel.getConversationUid());
            }
        }

        synchronized (conversationCache) {
            for (ConversationModel conversationModel : conversationCache) {
                conversationModel.isPinTagged = pinTaggedUids.contains(conversationModel.getUid());
                conversationModel.isUnreadTagged = unreadTaggedUids.contains(conversationModel.getUid());
            }
        }
    }

    @Override
    public void sort() {
        synchronized (this.conversationCache) {
            // Sort conversations in cache
            Collections.sort(this.conversationCache, (c1, c2) -> {
                // Sorting: Pinned chats are at the top. Otherwise, sort by getSortDate.
                boolean conversation1pinned = c1.isPinTagged;
                boolean conversation2pinned = c2.isPinTagged;
                if (conversation1pinned == conversation2pinned) {
                    return c2.getSortDate().compareTo(c1.getSortDate());
                }
                return conversation2pinned ? 1 : -1;
            });

            // Set new position
            int pos = 0;
            for (ConversationModel m : this.conversationCache) {
                m.setPosition(pos++);
            }
        }
    }

    @Override
    public synchronized ConversationModel refresh(AbstractMessageModel modifiedMessageModel) {
        ConversationModelParser parser = this.createParser(modifiedMessageModel);
        if (parser != null) {
            return parser.refresh(modifiedMessageModel);
        }
        return null;
    }

    @Override
    public synchronized void markConversationAsRead(@NonNull AbstractMessageModel messageModel) {
        ConversationModelParser parser = this.createParser(messageModel);
        if (parser != null) {
            parser.markConversationAsRead(messageModel);
        }
    }

    @Override
    public void markConversationAsRead(@NonNull MessageReceiver<?> messageReceiver) {
        if (messageReceiver instanceof GroupMessageReceiver) {
            new GroupConversationModelParser().markConversationAsRead(
                ((GroupMessageReceiver) messageReceiver).getGroup()
            );
        } else if (messageReceiver instanceof DistributionListMessageReceiver) {
            new DistributionListConversationModelParser().markConversationAsRead(
                ((DistributionListMessageReceiver) messageReceiver).getDistributionList()
            );
        } else if (messageReceiver instanceof ContactMessageReceiver) {
            ch.threema.data.models.ContactModel contactModel = ((ContactMessageReceiver) messageReceiver).getContactModel();
            if (contactModel != null) {
                new ContactConversationModelParser().markConversationAsRead(contactModel);
            } else {
                logger.error("Could not mark conversation as read because the contact model is null");
            }
        }
    }

    @Override
    public synchronized void updateContactConversation(@NonNull ContactModel contactModel) {
        synchronized (conversationCache) {
            for (int i = 0; i < conversationCache.size(); i++) {
                ConversationModel conversationModel = conversationCache.get(i);
                if (conversationModel.isContactConversation() && contactModel.getIdentity().equals(conversationModel.getContact().getIdentity())) {
                    ContactConversationModelParser conversationModelParser = new ContactConversationModelParser();
                    final List<ConversationResult> result = conversationModelParser.select(contactModel.getIdentity());
                    if (result.isEmpty() || result.get(0) == null) {
                        logger.warn("No result for updating identity {}", contactModel.getIdentity());
                        return;
                    }
                    ConversationModel updatedModel = conversationModelParser.parseResult(result.get(0), conversationModel, false);
                    if (updatedModel != null) {
                        // persist tags from original model
                        updatedModel.isPinTagged = conversationModel.isPinTagged;
                        updatedModel.isUnreadTagged = conversationModel.isUnreadTagged;
                    }

                    conversationCache.set(i, updatedModel);
                    break;
                }
            }
        }
    }

    @Override
    public synchronized ConversationModel refresh(@Nullable ch.threema.data.models.ContactModel contactModel) {
        if (contactModel == null) {
            return null;
        }
        return new ContactConversationModelParser()
            .refresh(contactModel);
    }

    @Override
    public synchronized ConversationModel refresh(GroupModel groupModel) {
        return new GroupConversationModelParser()
            .refresh(groupModel);
    }

    @Override
    public synchronized ConversationModel refresh(DistributionListModel distributionListModel) {
        return new DistributionListConversationModelParser()
            .refresh(distributionListModel);
    }

    @Override
    public synchronized ConversationModel refresh(@NonNull MessageReceiver receiver) {
        switch (receiver.getType()) {
            case MessageReceiver.Type_CONTACT:
                return this.refresh(((ContactMessageReceiver) receiver).getContactModel());
            case MessageReceiver.Type_GROUP:
                return this.refresh(((GroupMessageReceiver) receiver).getGroup());
            case MessageReceiver.Type_DISTRIBUTION_LIST:
                return this.refresh(((DistributionListMessageReceiver) receiver).getDistributionList());
            default:
                throw new IllegalStateException("Got ReceiverModel with invalid receiver type!");
        }
    }

    @Override
    public synchronized ConversationModel setIsTyping(ContactModel contact, boolean isTyping) {
        ContactConversationModelParser p = new ContactConversationModelParser();
        ConversationModel conversationModel = p.getCached(contact.getIdentity());
        if (conversationModel != null) {
            conversationModel.isTyping = isTyping;
            this.fireOnModifiedConversation(conversationModel);
        }
        return conversationModel;
    }

    @Override
    public synchronized void refreshWithDeletedMessage(AbstractMessageModel modifiedMessageModel) {
        ConversationModelParser parser = this.createParser(modifiedMessageModel);
        if (parser != null) {
            parser.messageDeleted(modifiedMessageModel);
        }
    }

    @Override
    public synchronized void archive(@NonNull ConversationModel conversationModel, @NonNull TriggerSource triggerSource) {
        this.conversationTagService.removeAll(conversationModel, triggerSource);

        conversationModel.setUnreadCount(0);

        if (conversationModel.isContactConversation()) {
            ContactModel contactModel = conversationModel.getContact();
            if (contactModel == null) {
                logger.error("Cannot archive contact conversation where contact is null");
                return;
            }
            String identity = contactModel.getIdentity();
            contactService.setIsArchived(identity, true, triggerSource);
        } else if (conversationModel.isGroupConversation()) {
            GroupModel groupModel = conversationModel.getGroup();
            if (groupModel == null) {
                logger.error("Cannot archive group conversation where group is null");
                return;
            }
            groupService.setIsArchived(
                groupModel.getCreatorIdentity(),
                groupModel.getApiGroupId(),
                true,
                triggerSource
            );
        } else if (conversationModel.isDistributionListConversation()) {
            distributionListService.setIsArchived(conversationModel.getDistributionList(), true);
        }

        this.removeFromCache(conversationModel);
    }

    @Override
    public void unarchive(List<ConversationModel> conversationModels, @NonNull TriggerSource triggerSource) {
        for (ConversationModel conversationModel : conversationModels) {
            if (conversationModel.isContactConversation()) {
                ContactModel contactModel = conversationModel.getContact();
                if (contactModel == null) {
                    logger.error("Contact model cannot be null while un-archiving contact");
                    return;
                }
                contactService.setIsArchived(contactModel.getIdentity(), false, triggerSource);
            } else if (conversationModel.isGroupConversation()) {
                GroupModel groupModel = conversationModel.getGroup();
                if (groupModel == null) {
                    logger.error("Group model cannot be null while un-archiving group");
                    return;
                }
                groupService.setIsArchived(
                    groupModel.getCreatorIdentity(),
                    groupModel.getApiGroupId(),
                    false,
                    triggerSource
                );
            } else if (conversationModel.isDistributionListConversation()) {
                distributionListService.setIsArchived(conversationModel.getDistributionList(), false);
            }

            // Note: Don't call the conversation listener (onNew) here, that will be handled
            // already by the save() call in the contact/group/distributionlist-service.
        }
    }

    @Override
    public synchronized int empty(@NonNull MessageReceiver receiver) {
        // First refresh the receiver. Otherwise it is possible that the conversation is null as it
        // does not yet exist (or is just not yet loaded) and then the chat won't be emptied.
        ConversationModel model = refresh(receiver);
        if (model != null) {
            return this.empty(model, true);
        } else {
            logger.error("Could not empty conversation as conversation model is null");
            return 0;
        }
    }

    @Override
    public synchronized int empty(final ConversationModel conversation, boolean silentMessageUpdate) {
        // Remove all messages
        MessageReceiver<?> receiver = conversation.messageReceiver;
        final List<AbstractMessageModel> messages = this.messageService.getMessagesForReceiver(receiver);
        logger.info("Empty conversation with {} messages for receiver {} (type={})", messages.size(), receiver.getUniqueIdString(), receiver.getType());
        for (AbstractMessageModel m : messages) {
            this.messageService.remove(m, silentMessageUpdate);
        }

        // Remove unread tag but not the pinned tag
        this.conversationTagService.removeTag(conversation.getUid(), ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL);

        // Update conversation
        conversation.latestMessage = null;
        conversation.messageCount = 0;
        conversation.setUnreadCount(0);
        this.fireOnModifiedConversation(conversation);

        // Return number of removed messages
        return messages.size();
    }

    @Override
    public int empty(@NonNull String identity) {
        final ConversationModel conversationModel = new ContactConversationModelParser().getCached(identity);
        if (conversationModel != null) {
            return this.empty(conversationModel, true);
        }
        logger.warn("Contact conversation model is null, cannot empty");
        return 0;
    }

    @Override
    public synchronized int empty(@NonNull GroupModel groupModel) {
        final ConversationModel conversationModel = new GroupConversationModelParser().getCached(groupModel.getId());
        if (conversationModel != null) {
            return this.empty(conversationModel, true);
        }
        logger.warn("Group conversation model is null, cannot empty");
        return 0;
    }

    @Override
    public synchronized int empty(@NonNull DistributionListModel distributionListModel) {
        final ConversationModel conversationModel = new DistributionListConversationModelParser().getCached(distributionListModel.getId());
        if (conversationModel != null) {
            return this.empty(conversationModel, true);
        }
        logger.warn("DistributionList conversation model is null, cannot empty");
        return 0;
    }

    @Override
    public synchronized int delete(@NonNull String identity) {
        // Empty chat (if it isn't already empty)
        final int removedCount = this.empty(identity);

        // Clear lastUpdate
        this.contactService.clearLastUpdate(identity);

        // Remove from cache and notify listeners
        final ConversationModel conversationModel = new ContactConversationModelParser().getCached(identity);
        if (conversationModel != null) {
            // Remove tags
            this.conversationTagService.removeAll(conversationModel, TriggerSource.LOCAL);
            this.removeFromCache(conversationModel);
        }

        return removedCount;
    }

    @Override
    public synchronized void removeFromCache(@NonNull GroupModel groupModel) {
        // Remove from cache and notify listeners
        final ConversationModel conversationModel = new GroupConversationModelParser().getCached(groupModel.getId());
        if (conversationModel != null) {
            this.removeFromCache(conversationModel);
        }
    }

    @Override
    public synchronized void removeFromCache(@NonNull DistributionListModel distributionListModel) {
        // Remove from cache and notify listeners
        final ConversationModel conversationModel = new DistributionListConversationModelParser().getCached(distributionListModel.getId());
        if (conversationModel != null) {
            this.removeFromCache(conversationModel);
        }
    }

    @Override
    public synchronized boolean reset() {
        synchronized (this.conversationCache) {
            this.conversationCache.clear();
            logger.debug("Conversation cache reset");
            this.initAllLoaded = false;
        }
        return true;
    }

    @Override
    public boolean hasConversations() {
        synchronized (this.conversationCache) {
            if (!this.conversationCache.isEmpty()) {
                return true;
            }
        }

        long count = this.databaseService.getDistributionListMessageModelFactory().count();
        if (count > 0) {
            return true;
        }

        count = this.databaseService.getMessageModelFactory().count();
        if (count > 0) {
            return true;
        }

        count = this.databaseService.getGroupMessageModelFactory().count();
        return count > 0;
    }

    private void fireOnModifiedConversation(final ConversationModel c) {
        ListenerManager.conversationListeners.handle(listener -> listener.onModified(c, null));
    }

    private ConversationModelParser createParser(AbstractMessageModel m) {
        if (m instanceof GroupMessageModel) {
            return new GroupConversationModelParser();
        } else if (m instanceof DistributionListMessageModel) {
            return new DistributionListConversationModelParser();
        } else if (m instanceof MessageModel) {
            return new ContactConversationModelParser();
        }
        return null;
    }

    private abstract class ConversationModelParser<I, M extends AbstractMessageModel, R> {
        /**
         * Return whether the specified identifier belongs to the specified conversation.
         */
        public abstract boolean belongsTo(ConversationModel conversationModel, I identifier);

        /**
         * Create or update (and return) a {@link ConversationModel} from the specified
         * {@link ConversationResult}.
         *
         * @param result            The conversation result
         * @param conversationModel If not null, an existing conversation model to be updated
         * @param addToCache        Whether to add the conversation model to the cache
         * @return The created or updated conversation model, or null if something went wrong
         */
        public abstract @Nullable ConversationModel parseResult(
            @NonNull ConversationResult result,
            @Nullable ConversationModel conversationModel,
            boolean addToCache
        );

        /**
         * Query the database for conversation results matching the specified identifier.
         */
        public abstract @NonNull List<ConversationResult> select(@NonNull I identifier);

        /**
         * Query the database for all conversation results.
         */
        public abstract @NonNull List<ConversationResult> selectAll(boolean archived);

        /**
         * Return the conversation identifier for the specified message model.
         */
        protected abstract I getIdentifier(M messageModel);

        /**
         * Return the conversation identifier for the specified parent object.
         */
        protected abstract I getIdentifier(R receiverModel);

        /**
         * Get the last update flag from the receiver model
         */
        @Nullable
        protected abstract Date getLastUpdate(@Nullable R receiverModel);

        /**
         * Return the cached conversation for the specified {@param messageModel}.
         */
        public final @Nullable ConversationModel getCached(final @NonNull M messageModel) {
            return this.getCached(this.getIdentifier(messageModel));
        }

        /**
         * Return the cached conversation for the specified {@param identifier}.
         */
        protected final @Nullable ConversationModel getCached(final @Nullable I identifier) {
            if (identifier == null) {
                return null;
            }
            synchronized (conversationCache) {
                return Functional.select(
                    conversationCache,
                    conversationModel -> belongsTo(conversationModel, identifier)
                );
            }
        }

        public final void processAll() {
            List<ConversationResult> res = this.selectAll(false);
            for (ConversationResult r : res) {
                this.parseResult(r, null, true);
            }
        }

        public final List<ConversationModel> processArchived(
            List<ConversationModel> conversationModels,
            @Nullable String searchQuery
        ) {
            List<ConversationResult> res = this.selectAll(true);

            if (!TestUtil.isEmptyOrNull(searchQuery)) {
                for (ConversationResult r : res) {
                    ConversationModel conversationModel = this.parseResult(r, null, false);
                    if (TestUtil.matchesConversationSearch(searchQuery, conversationModel.toString())) {
                        conversationModels.add(conversationModel);
                    }
                }
            } else {
                for (ConversationResult r : res) {
                    conversationModels.add(this.parseResult(r, null, false));
                }
            }
            return conversationModels;
        }

        public final ConversationModel getSelected(final I identifier) {
            final List<ConversationResult> results = this.select(identifier);
            if (!results.isEmpty()) {
                return this.parseResult(results.get(0), null, true);
            }
            return null;
        }

        /**
         * Refresh the conversation data based on an updated receiver model (i.e. a contact,
         * a group or a distribution list).
         * <p>
         * Examples:
         * <p>
         * - When a contact name changes, re-calculate the distribution list name.
         * - When the "lastUpdate" timestamp of a group changes, update the conversation as well.
         */
        @Nullable
        public final ConversationModel refresh(R receiverModel) {
            final I identifier = this.getIdentifier(receiverModel);
            ConversationModel model = this.getCached(identifier);

            final Integer lastPosition;

            boolean newConversationModel = false;
            if (model == null) {
                // Set last position to null as it is a new conversation
                lastPosition = null;
                newConversationModel = true;
                model = this.getSelected(identifier);
                //resort
                sort();
            } else {
                // Refresh name if it's a distribution list
                if (model.isDistributionListConversation() && receiverModel instanceof DistributionListModel) {
                    model.getDistributionList().setName(((DistributionListModel) receiverModel).getName());
                }

                // Refresh lastUpdate
                model.lastUpdate = getLastUpdate(receiverModel);

                // Refresh notificationTriggerPolicyOverride
                if (model.isGroupConversation() && receiverModel instanceof GroupModel && model.getGroup() != null) {
                    model.getGroup().setNotificationTriggerPolicyOverride(
                        ((GroupModel) receiverModel).getNotificationTriggerPolicyOverride()
                    );
                } else if (model.isContactConversation() && receiverModel instanceof ContactModel && model.getContact() != null) {
                    model.getContact().setNotificationTriggerPolicyOverride(
                        ((ContactModel) receiverModel).getNotificationTriggerPolicyOverride()
                    );
                }

                // Get the last position of the model. Note that this is required for the message
                // section fragment to scroll to the updated chat.
                lastPosition = model.getPosition();
            }

            if (model == null) {
                return null;
            }
            final ConversationModel finalModel = model;
            if (newConversationModel) {
                logger.debug("refresh modified parent NEW");
                ListenerManager.conversationListeners.handle(listener -> listener.onNew(finalModel));
            } else {
                logger.debug("refresh modified parent MODIFIED");
                ListenerManager.conversationListeners.handle(listener -> listener.onModified(finalModel, lastPosition));
            }

            return model;
        }

        /**
         * Refresh the conversation data based on an updated message model.
         * <p>
         * Examples:
         * <p>
         * - Update message count.
         * - Update unread status.
         */
        public final ConversationModel refresh(@Nullable M modifiedMessageModel) {
            if (modifiedMessageModel == null) {
                return null;
            }

            // Look up conversation in cache
            I index = this.getIdentifier(modifiedMessageModel);
            ConversationModel model = this.getCached(index);

            // On cache miss, get the conversation from the DB
            boolean newConversationModel = false;
            if (model == null) {
                newConversationModel = true;
                model = this.getSelected(index);
            }

            // If conversation was not found, give up
            if (model == null) {
                logger.warn("Could not refresh conversation for message model, conversation not found");
                return null;
            }

            boolean isLatestMessageCandidate = !modifiedMessageModel.isStatusMessage()
                || modifiedMessageModel.getType() == MessageType.GROUP_CALL_STATUS;

            // Increase message count if necessary
            if ((model.latestMessage == null
                || model.latestMessage.getId() < modifiedMessageModel.getId())
                && isLatestMessageCandidate
            ) {
                model.messageCount = model.messageCount + 1;
            }

            // If the modified message model is a new message, update the latest message
            if ((model.latestMessage == null
                || model.latestMessage.getId() <= modifiedMessageModel.getId())
                && isLatestMessageCandidate
                && model.latestMessage != modifiedMessageModel
            ) {
                // Set this message as latest message
                model.latestMessage = modifiedMessageModel;
            }

            // Update read/unread state if necessary
            if (model.messageReceiver != null && MessageUtil.isUnread(model.latestMessage)) {
                model.setUnreadCount(model.messageReceiver.getUnreadMessagesCount());
                // For the conversation tag marked-as-unread the trigger source does not matter as it isn't reflected
                conversationTagService.removeTagAndNotify(model, ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL);
            } else {
                // TODO(ANDR-3709): This is not the correct place to mark the conversation as read.
                //  It is an unexpected side-effect and not generally correct, e.g., this would also
                //  incorrectly mark it as read if the last message in a conversation is edited by another user.
                //  Nonetheless, many parts of the code implicitly rely on this behavior, so we can't
                //  easily remove this logic here. A better approach needs to be found eventually.
                if (model.latestMessage == null) {
                    // If there are no messages, mark the conversation as read
                    model.setUnreadCount(0);
                    // For the conversation tag marked-as-unread the trigger source does not matter as it isn't reflected
                    conversationTagService.removeTagAndNotify(model, ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL);
                } else if (model.latestMessage.getId() == modifiedMessageModel.getId() && modifiedMessageModel.isRead()) {
                    // If the current message is the latest message in the conversation
                    // and if it's read, mark the entire conversation as read.
                    model.setUnreadCount(0);
                    // For the conversation tag marked-as-unread the trigger source does not matter as it isn't reflected
                    conversationTagService.removeTagAndNotify(model, ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL);
                }
            }

            final ConversationModel finalModel = model;

            final int oldPosition = model.getPosition();
            sort();

            if (newConversationModel) {
                logger.debug("ConversationModelParser.refresh: Notify conversation listener (NEW)");
                ListenerManager.conversationListeners.handle(listener -> listener.onNew(finalModel));
            } else {
                logger.debug("ConversationModelParser.refresh: Notify conversation listener (MODIFIED), pos={}", finalModel.getPosition());
                ListenerManager.conversationListeners.handle(listener -> {
                    listener.onModified(finalModel, oldPosition != finalModel.getPosition() ? oldPosition : null);
                });
            }

            return model;
        }

        public final void markConversationAsRead(@NonNull R receiverModel) {
            // Look up conversation in cache
            I index = this.getIdentifier(receiverModel);
            ConversationModel conversationModel = this.getCached(index);

            // On cache miss, get the conversation from the DB
            if (conversationModel == null) {
                conversationModel = this.getSelected(index);
            }

            // If conversation was not found, give up
            if (conversationModel == null) {
                logger.warn("Could not mark conversation as read for receiver model, conversation not found");
                return;
            }

            conversationModel.setUnreadCount(0);
            // For the conversation tag marked-as-unread the trigger source does not matter as it isn't reflected
            conversationTagService.removeTagAndNotify(conversationModel, ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL);
        }

        public final void markConversationAsRead(@NonNull M message) {
            // Look up conversation in cache
            I index = this.getIdentifier(message);
            ConversationModel conversationModel = this.getCached(index);

            // On cache miss, get the conversation from the DB
            if (conversationModel == null) {
                conversationModel = this.getSelected(index);
            }

            // If conversation was not found, give up
            if (conversationModel == null) {
                logger.warn("Could not mark conversation as read for message model, conversation not found");
                return;
            }

            conversationModel.setUnreadCount(0);
            // For the conversation tag marked-as-unread the trigger source does not matter as it isn't reflected
            conversationTagService.removeTagAndNotify(conversationModel, ConversationTag.MARKED_AS_UNREAD, TriggerSource.LOCAL);
        }

        public final void messageDeleted(@NonNull M messageModel) {
            final ConversationModel model = this.getCached(messageModel);
            //if the newest message is deleted, reload
            if (model != null && model.latestMessage != null) {
                if (model.latestMessage.getId() >= messageModel.getId()) {
                    updateLatestConversationMessageAfterDelete(model);

                    final int oldPosition = model.getPosition();
                    sort();

                    ListenerManager.conversationListeners.handle(
                        listener -> listener.onModified(model, oldPosition != model.getPosition() ? oldPosition : null)
                    );
                }
            }
        }

        /**
         * See {@link #parse(String, String[])} for docs.
         */
        protected List<ConversationResult> parse(String query) {
            return parse(query, null);
        }

        /**
         * Run an SQL query and parse the resulting cursor.
         * <p>
         * The query must return rows with the following columns:
         * <p>
         * - Index 0: The conversation identifier (see {@link ConversationResult#identifier}
         * - Index 1: The message count (see {@link ConversationResult#messageCount}
         * - Index 2: The lastUpdate timestamp (see {@link ConversationResult#lastUpdate}
         * - Index 3: The nullable latest message ID (see {@link ConversationResult#latestMessageId}
         */
        protected List<ConversationResult> parse(String query, String[] args) {
            final List<ConversationResult> results = new ArrayList<>();

            try (Cursor c = databaseService.getReadableDatabase().rawQuery(query, args)) {
                if (c == null) {
                    return results;
                }
                while (c.moveToNext()) {
                    final String identifier = c.getString(0);
                    final long messageCount = c.getLong(1);
                    final Date lastUpdate = new Date(c.getLong(2));
                    final Integer latestMessageId = c.isNull(3) ? null : c.getInt(3);
                    results.add(new ConversationResult(identifier, messageCount, lastUpdate, latestMessageId));
                }
            }

            return results;
        }

        /**
         * If {@code conversationModel} is null, create a new {@link ConversationModel}, cache it if
         * requested, and return it.
         * Otherwise, update the {@code messageReceiver} in the existing {@code conversationModel} and return it.
         */
        protected @NonNull ConversationModel createOrUpdateConversationModel(
            @Nullable ConversationModel conversationModel,
            @NonNull ReceiverModel receiverModel,
            @NonNull MessageReceiver<?> messageReceiver,
            boolean addToCache
        ) {
            if (conversationModel != null) {
                conversationModel.messageReceiver = messageReceiver;
                return conversationModel;
            }

            final ConversationModel newConversationModel = new ConversationModel(context, messageReceiver);

            synchronized (conversationCache) {
                Optional<ConversationModel> optionalConversationModel = conversationCache.stream()
                    .filter(model -> model.getUid().equals(newConversationModel.getUid()))
                    .findFirst();

                if (optionalConversationModel.isPresent()) {
                    ConversationModel existingConversationModel = optionalConversationModel.get();
                    existingConversationModel.messageReceiver = messageReceiver;
                    logger.warn("The conversation already existed and was updated");
                    return existingConversationModel;
                }

                // Add to cache, but only for non-archived and non-hidden conversations
                if (addToCache && !receiverModel.isArchived() && !receiverModel.isHidden()) {
                    conversationCache.add(newConversationModel);
                }
            }

            return newConversationModel;
        }
    }

    private class ContactConversationModelParser extends ConversationModelParser<String, MessageModel, ch.threema.data.models.ContactModel> {
        @Override
        public boolean belongsTo(ConversationModel conversationModel, String identity) {
            return conversationModel.getContact() != null &&
                conversationModel.getContact().getIdentity().equals(identity);
        }

        @Override
        public @NonNull List<ConversationResult> select(@NonNull String identity) {
            return this.parse(
                "WITH message_info AS (" +
                    "SELECT identity, COUNT(*) AS messageCount, MAX(id) AS latestMessageId " +
                    "FROM message " +
                    "WHERE isSaved = 1 AND isStatusMessage = 0 " +
                    "GROUP BY identity" +
                    ") " +
                    "SELECT c.identity, IFNULL(m.messageCount, 0) AS messageCount, c.lastUpdate, m.latestMessageId " +
                    "FROM contacts c " +
                    "LEFT JOIN message_info m ON c.identity = m.identity " +
                    "WHERE c.lastUpdate IS NOT NULL AND c.identity = ?",
                new String[]{identity}
            );
        }

        @Override
        public @NonNull List<ConversationResult> selectAll(boolean archived) {
            return this.parse(
                "WITH message_info AS (" +
                    "SELECT identity, COUNT(*) AS messageCount, MAX(id) AS latestMessageId " +
                    "FROM message " +
                    "WHERE isSaved = 1 AND isStatusMessage = 0 " +
                    "GROUP BY identity" +
                    ") " +
                    "SELECT c.identity, IFNULL(m.messageCount, 0) AS messageCount, c.lastUpdate, m.latestMessageId " +
                    "FROM contacts c " +
                    "LEFT JOIN message_info m ON c.identity = m.identity " +
                    "WHERE c.lastUpdate IS NOT NULL AND c.acquaintanceLevel != 1 AND c.isArchived = " + (archived ? "1" : "0")
            );
        }

        @Override
        public @Nullable ConversationModel parseResult(
            @NonNull ConversationResult result,
            @Nullable ConversationModel conversationModel,
            boolean addToCache
        ) {
            // Look up contact and create receiver
            final ContactModel contactModel = contactService.getByIdentity(result.identifier);
            if (contactModel == null) {
                logger.warn("ContactConversationModelParser: Contact with identity {} not found", result.identifier);
                return null;
            }
            final ContactMessageReceiver receiver = contactService.createReceiver(contactModel);

            // If no conversation model was passed in (to be updated), create a new model
            conversationModel = this.createOrUpdateConversationModel(
                conversationModel,
                contactModel,
                receiver,
                addToCache
            );

            // Update the rest of the conversation information
            conversationModel.messageCount = result.messageCount;
            conversationModel.lastUpdate = result.lastUpdate;
            if (result.messageCount > 0) {
                final MessageModel latestMessage = messageService.getContactMessageModel(
                    result.latestMessageId
                );
                conversationModel.latestMessage = latestMessage;

                if (MessageUtil.isUnread(latestMessage)) {
                    // Update unread message count only if the "newest" message is unread
                    conversationModel.setUnreadCount(receiver.getUnreadMessagesCount());
                }
            }

            return conversationModel;
        }

        @Override
        protected String getIdentifier(MessageModel messageModel) {
            return messageModel != null ? messageModel.getIdentity() : null;
        }

        @Override
        protected String getIdentifier(ch.threema.data.models.ContactModel contactModel) {
            return contactModel != null ? contactModel.getIdentity() : null;
        }

        @Nullable
        @Override
        protected Date getLastUpdate(@Nullable ch.threema.data.models.ContactModel receiverModel) {
            if (receiverModel == null) {
                return null;
            }
            return contactService.getLastUpdate(receiverModel.getIdentity());
        }
    }

    private class GroupConversationModelParser extends ConversationModelParser<Integer, GroupMessageModel, GroupModel> {
        @Override
        public boolean belongsTo(ConversationModel conversationModel, Integer groupId) {
            return conversationModel.getGroup() != null &&
                conversationModel.getGroup().getId() == groupId;
        }

        @Override
        public @Nullable ConversationModel parseResult(
            @NonNull ConversationResult result,
            @Nullable ConversationModel conversationModel,
            boolean addToCache
        ) {
            // Look up group and create receiver
            final GroupModel groupModel = groupService.getById(Integer.parseInt(result.identifier));
            if (groupModel == null) {
                logger.warn("GroupConversationModelParser: Group with ID {} not found", result.identifier);
                return null;
            }
            final GroupMessageReceiver receiver = groupService.createReceiver(groupModel);

            // If no conversation model was passed in (to be updated), create a new model
            conversationModel = this.createOrUpdateConversationModel(
                conversationModel,
                groupModel,
                receiver,
                addToCache
            );

            // Update the rest of the conversation information
            conversationModel.messageCount = result.messageCount;
            conversationModel.lastUpdate = result.lastUpdate;
            if (result.messageCount > 0) {
                final GroupMessageModel latestMessage = messageService.getGroupMessageModel(
                    result.latestMessageId
                );
                conversationModel.latestMessage = latestMessage;

                if (MessageUtil.isUnread(latestMessage)) {
                    // Update unread message count only if the "newest" message is unread
                    conversationModel.setUnreadCount(receiver.getUnreadMessagesCount());
                }
            }

            return conversationModel;
        }

        @Override
        public @NonNull List<ConversationResult> select(@NonNull Integer groupId) {
            // Note: Don't exclude groups without lastUpdate, groups should always be visible
            return this.parse(
                "WITH message_info AS (" +
                    "SELECT groupId, COUNT(*) as messageCount, MAX(id) as latestMessageId " +
                    "FROM m_group_message " +
                    "WHERE isSaved = 1 AND (isStatusMessage = 0 OR type = ?) " +
                    "GROUP BY groupId" +
                    ") " +
                    "SELECT g.id, IFNULL(m.messageCount, 0) AS messageCount, IFNULL(g.lastUpdate, 0), m.latestMessageId " +
                    "FROM m_group g " +
                    "LEFT JOIN message_info m ON g.id = m.groupId " +
                    "WHERE g.id = ?",
                new String[]{String.valueOf(MessageType.GROUP_CALL_STATUS.ordinal()), String.valueOf(groupId)}
            );
        }

        @Override
        public @NonNull List<ConversationResult> selectAll(boolean archived) {
            // Note: Don't exclude groups without lastUpdate, groups should always be visible
            return this.parse(
                "WITH message_info AS (" +
                    "SELECT groupId, COUNT(*) as messageCount, MAX(id) as latestMessageId " +
                    "FROM m_group_message " +
                    "WHERE isSaved = 1 AND (isStatusMessage = 0 OR type = ?) " +
                    "GROUP BY groupId" +
                    ") " +
                    "SELECT g.id, IFNULL(m.messageCount, 0) AS messageCount, IFNULL(g.lastUpdate, 0), m.latestMessageId " +
                    "FROM m_group g " +
                    "LEFT JOIN message_info m ON g.id = m.groupId " +
                    "WHERE g.isArchived = " + (archived ? "1" : "0"),
                new String[]{String.valueOf(MessageType.GROUP_CALL_STATUS.ordinal())}
            );
        }

        @Override
        protected Integer getIdentifier(GroupMessageModel messageModel) {
            return messageModel != null ? messageModel.getGroupId() : null;
        }

        @Override
        protected Integer getIdentifier(GroupModel groupModel) {
            return groupModel != null ? groupModel.getId() : null;
        }

        @Nullable
        @Override
        protected Date getLastUpdate(@Nullable GroupModel receiverModel) {
            if (receiverModel == null) {
                return null;
            }
            return receiverModel.getLastUpdate();
        }
    }


    private class DistributionListConversationModelParser extends ConversationModelParser<Long, DistributionListMessageModel, DistributionListModel> {
        @Override
        public boolean belongsTo(ConversationModel conversationModel, Long distributionListId) {
            return conversationModel.getDistributionList() != null &&
                conversationModel.getDistributionList().getId() == distributionListId;
        }

        @Override
        public @Nullable ConversationModel parseResult(
            @NonNull ConversationResult result,
            @Nullable ConversationModel conversationModel,
            boolean addToCache
        ) {
            // Look up distribution list and create receiver
            final DistributionListModel distributionListModel = distributionListService.getById(Long.parseLong(result.identifier));
            if (distributionListModel == null) {
                logger.warn("DistributionListConversationModelParser: Distribution list with ID {} not found", result.identifier);
                return null;
            }
            final DistributionListMessageReceiver receiver = distributionListService.createReceiver(distributionListModel);

            // If no conversation model was passed in (to be updated), create a new model
            conversationModel = this.createOrUpdateConversationModel(
                conversationModel,
                distributionListModel,
                receiver,
                addToCache
            );

            // Update the rest of the conversation information
            conversationModel.messageCount = result.messageCount;
            conversationModel.lastUpdate = result.lastUpdate;
            if (result.messageCount > 0) {
                final DistributionListMessageModel latestMessage = messageService.getDistributionListMessageModel(
                    result.latestMessageId
                );
                conversationModel.latestMessage = latestMessage;
            }

            // Distribution lists cannot have unread messages
            conversationModel.setUnreadCount(0);

            return conversationModel;
        }

        @Override
        public @NonNull List<ConversationResult> select(@NonNull Long distributionListId) {
            // Note: Don't exclude distribution lists without lastUpdate, distribution lists should always be visible
            return this.parse(
                "WITH message_info AS (" +
                    "SELECT distributionListId, COUNT(*) as messageCount, MAX(id) as latestMessageId " +
                    "FROM distribution_list_message " +
                    "WHERE isSaved = 1 AND isStatusMessage = 0 " +
                    "GROUP BY distributionListId" +
                    ") " +
                    "SELECT d.id, IFNULL(m.messageCount, 0) AS messageCount, IFNULL(d.lastUpdate, 0), m.latestMessageId " +
                    "FROM distribution_list d " +
                    "LEFT JOIN message_info m ON d.id = m.distributionListId " +
                    "WHERE d.id = ?",
                new String[]{String.valueOf(distributionListId)}
            );
        }

        @Override
        public @NonNull List<ConversationResult> selectAll(boolean archived) {
            // Note: Don't exclude distribution lists without lastUpdate, distribution lists should always be visible
            return this.parse(
                "WITH message_info AS (" +
                    "SELECT distributionListId, COUNT(*) as messageCount, MAX(id) as latestMessageId " +
                    "FROM distribution_list_message " +
                    "WHERE isSaved = 1 AND isStatusMessage = 0 " +
                    "GROUP BY distributionListId" +
                    ") " +
                    "SELECT d.id, IFNULL(m.messageCount, 0) AS messageCount, IFNULL(d.lastUpdate, 0), m.latestMessageId " +
                    "FROM distribution_list d " +
                    "LEFT JOIN message_info m ON d.id = m.distributionListId " +
                    "WHERE d.isHidden != 1 AND d.isArchived = " + (archived ? "1" : "0")
            );
        }

        @Override
        protected Long getIdentifier(DistributionListMessageModel messageModel) {
            return messageModel != null ? messageModel.getDistributionListId() : null;
        }

        @Override
        protected Long getIdentifier(DistributionListModel distributionListModel) {
            return distributionListModel != null ? distributionListModel.getId() : null;
        }

        @Nullable
        @Override
        protected Date getLastUpdate(@Nullable DistributionListModel receiverModel) {
            if (receiverModel == null) {
                return null;
            }
            return receiverModel.getLastUpdate();
        }
    }


    private void updateLatestConversationMessageAfterDelete(ConversationModel conversationModel) {
        AbstractMessageModel newestMessage;

        newestMessage = Functional.select(
            this.messageService.getMessagesForReceiver(
                conversationModel.messageReceiver,
                new MessageService.MessageFilter() {
                    @Override
                    public long getPageSize() {
                        return 1;
                    }

                    @Override
                    public Integer getPageReferenceId() {
                        return null;
                    }

                    @Override
                    public boolean withStatusMessages() {
                        return false;
                    }

                    @Override
                    public boolean withUnsaved() {
                        return false;
                    }

                    @Override
                    public boolean onlyUnread() {
                        return false;
                    }

                    @Override
                    public boolean onlyDownloaded() {
                        return true;
                    }

                    @Override
                    public MessageType[] types() {
                        return null;
                    }

                    @Override
                    public int[] contentTypes() {
                        return null;
                    }

                    @Override
                    public int[] displayTags() {
                        return null;
                    }
                }
            ),
            type -> true
        );

        conversationModel.latestMessage = newestMessage;

        if (newestMessage == null || (newestMessage.isOutbox() || newestMessage.isRead())) {
            conversationModel.setUnreadCount(0);
        }

        if (newestMessage == null) {
            conversationModel.messageCount = 0;
        }
    }

    @Override
    public void calculateLastUpdateForAllConversations() {
        final SQLiteDatabase db = databaseService.getReadableDatabase();
        this.calculateLastUpdateContacts(db);
        this.calculateLastUpdateGroups(db);
        this.calculateLastUpdateDistributionLists(db);
    }

    private void calculateLastUpdateContacts(@NonNull SQLiteDatabase db) {
        logger.info("Calculate lastUpdate for contacts");

        db.execSQL(
            "UPDATE contacts " +
                "SET lastUpdate = tmp.lastUpdate FROM ( " +
                "    SELECT m.identity, max(m.createdAtUtc) as lastUpdate " +
                "    FROM message m " +
                "    WHERE m.isSaved = 1 " +
                "    GROUP BY m.identity " +
                ") tmp " +
                "WHERE contacts.identity = tmp.identity;"
        );
    }

    private void calculateLastUpdateGroups(@NonNull SQLiteDatabase db) {
        logger.info("Calculate lastUpdate for groups");

        // Set lastUpdate to the create date of the latest message if present
        db.execSQL(
            "UPDATE m_group " +
                "SET lastUpdate = tmp.lastUpdate FROM ( " +
                "    SELECT m.groupId, max(m.createdAtUtc) as lastUpdate " +
                "    FROM m_group_message m " +
                "    WHERE m.isSaved = 1 " +
                "    GROUP BY m.groupId " +
                ") tmp " +
                "WHERE m_group.id = tmp.groupId;"
        );

        // Set lastUpdate for groups without messages.
        // `createdAt` is stored in localtime and therefore needs to be converted to UTC.
        db.execSQL(
            "UPDATE m_group " +
                "SET lastUpdate = strftime('%s', createdAt, 'utc') * 1000 " +
                "WHERE lastUpdate IS NULL;"
        );
    }

    private void calculateLastUpdateDistributionLists(@NonNull SQLiteDatabase db) {
        logger.info("Calculate lastUpdate for distribution lists");

        // Set lastUpdate to the create date of the latest message if present
        db.execSQL(
            "UPDATE " + DistributionListModel.TABLE + " " +
                "SET " + DistributionListModel.COLUMN_LAST_UPDATE + " = tmp.lastUpdate FROM ( " +
                "    SELECT m.distributionListId, max(m.createdAtUtc) as lastUpdate " +
                "    FROM " + DistributionListMessageModel.TABLE + " m " +
                "    WHERE m.isSaved = 1 " +
                "    GROUP BY m.distributionListId " +
                ") tmp " +
                "WHERE " + DistributionListModel.TABLE + ".id = tmp.distributionListId;"
        );

        // Set lastUpdate for distribution lists without messages.
        // `createdAt` is stored in localtime and therefore needs to be converted to UTC.
        db.execSQL(
            "UPDATE " + DistributionListModel.TABLE + " " +
                "SET " + DistributionListModel.COLUMN_LAST_UPDATE + " = strftime('%s', createdAt, 'utc') * 1000 " +
                "WHERE " + DistributionListModel.COLUMN_LAST_UPDATE + " IS NULL;"
        );
    }
}
