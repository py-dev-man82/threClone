/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2022-2025 Threema GmbH
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

package ch.threema.app.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AnyThread
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import ch.threema.app.R
import ch.threema.app.activities.ComposeMessageActivity
import ch.threema.app.emojis.EmojiMarkupUtil
import ch.threema.app.services.ContactService
import ch.threema.app.services.DistributionListService
import ch.threema.app.services.GroupService
import ch.threema.app.ui.AvatarListItemUtil
import ch.threema.app.ui.AvatarView
import ch.threema.app.ui.DebouncedOnClickListener
import ch.threema.app.ui.listitemholder.AvatarListItemHolder
import ch.threema.app.utils.AdapterUtil
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.utils.RuntimeUtil
import ch.threema.app.utils.StateBitmapUtil
import ch.threema.app.utils.ViewUtil
import ch.threema.app.utils.getRunningSince
import ch.threema.app.voip.groupcall.GroupCallDescription
import ch.threema.app.voip.groupcall.GroupCallManager
import ch.threema.app.voip.groupcall.GroupCallObserver
import ch.threema.app.voip.groupcall.localGroupId
import ch.threema.base.utils.LoggingUtil
import com.bumptech.glide.RequestManager
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Objects

private val logger = LoggingUtil.getThreemaLogger("MessageListViewHolder")

class MessageListViewHolder(
    itemView: View,
    private val context: Context,
    private val clickListener: MessageListViewHolderClickListener,
    private val groupCallManager: GroupCallManager,
    private val params: MessageListItemParams,
    private val strings: MessageListItemStrings,
    private val requestManager: RequestManager,
) : RecyclerView.ViewHolder(itemView), GroupCallObserver {
    interface MessageListViewHolderClickListener {
        fun onItemClick(view: View, position: Int)
        fun onItemLongClick(view: View, position: Int): Boolean
        fun onAvatarClick(view: View, position: Int)
        fun onJoinGroupCallClick(position: Int)
    }

    class MessageListItemParams(
        @ColorInt
        val regularColor: Int,
        @ColorInt
        val backgroundColor: Int,
        val isTablet: Boolean,
        val emojiMarkupUtil: EmojiMarkupUtil,
        val contactService: ContactService,
        val groupService: GroupService,
        val distributionListService: DistributionListService,
        var highlightUid: String?,
        val stateBitmapUtil: StateBitmapUtil?,
        val showLastUpdate: Boolean,
    )

    class MessageListItemStrings(
        val notes: String,
        val groups: String,
        val distributionLists: String,
        val stateSent: String,
        val draftText: String,
    )

    lateinit var listItem: View
    private lateinit var unreadBar: View
    private lateinit var unreadCountView: MaterialButton
    private lateinit var pinBar: View
    private lateinit var pinIcon: ImageView
    private lateinit var fromView: TextView
    private lateinit var dateView: TextView
    private lateinit var subjectView: TextView
    private lateinit var deliveryView: ImageView
    private lateinit var attachmentView: ImageView
    private lateinit var listItemFG: View
    private lateinit var latestMessageContainer: View
    private lateinit var typingContainer: View
    private lateinit var groupMemberName: TextView
    private lateinit var muteStatus: ImageView
    private lateinit var hiddenStatus: ImageView
    lateinit var avatarView: AvatarView
    private lateinit var avatarListItemHolder: AvatarListItemHolder
    private lateinit var ongoingGroupCallContainer: View
    private lateinit var joinGroupCallButton: MaterialButton
    private lateinit var ongoingCallDivider: TextView
    private lateinit var ongoingCallText: TextView
    private lateinit var groupCallDuration: Chronometer

    private var isGroupCallOngoing = false
    private var isGroupCallJoined = false

    var messageListAdapterItem: MessageListAdapterItem? = null
        set(value) {
            if (value != null) {
                initializeMessageListView(value)
                value.groupModel?.let {
                    logger.debug("Adding group call observer {}", it)
                    groupCallManager.addGroupCallObserver(it.localGroupId, this)
                }
            }
            field = value
        }

    init {
        initLayout(itemView)
        initializeOnClickListeners()
    }

    override fun onGroupCallUpdate(call: GroupCallDescription?) {
        if (!ConfigUtils.isGroupCallsEnabled()) {
            return
        }

        if (call != null && messageListAdapterItem?.groupModel?.getDatabaseId() == call.getGroupIdInt()
                .toLong() && messageListAdapterItem?.isPrivateChat != true
        ) {
            updateGroupCallDuration(call)
        } else {
            stopGroupCallDuration()
        }
        if (
            call == null && (isGroupCallOngoing || isGroupCallJoined) ||
            call != null && (!isGroupCallOngoing || groupCallManager.isJoinedCall(call) != isGroupCallJoined)
        ) {
            RuntimeUtil.runOnUiThread {
                messageListAdapterItem?.let { initializeMessageListView(it) }
            }
        }
    }

    private fun initLayout(view: View) {
        listItem = view.findViewById(R.id.list_item)
        unreadBar = view.findViewById(R.id.unread_bar)
        unreadCountView = view.findViewById(R.id.unread_count)
        pinBar = view.findViewById(R.id.pin_bar)
        pinIcon = view.findViewById(R.id.pin_icon)
        fromView = view.findViewById(R.id.from)
        dateView = view.findViewById(R.id.date)
        subjectView = view.findViewById(R.id.subject)
        deliveryView = view.findViewById(R.id.delivery)
        attachmentView = view.findViewById(R.id.attachment)
        listItemFG = view.findViewById(R.id.list_item_fg)
        latestMessageContainer = view.findViewById(R.id.latest_message_container)
        typingContainer = view.findViewById(R.id.typing_container)
        groupMemberName = view.findViewById(R.id.group_member_name)
        muteStatus = view.findViewById(R.id.mute_status)
        hiddenStatus = view.findViewById(R.id.hidden_status)
        avatarView = view.findViewById(R.id.avatar_view)
        avatarListItemHolder = AvatarListItemHolder()
        avatarListItemHolder.avatarView = avatarView
        avatarListItemHolder.avatarLoadingAsyncTask = null
        ongoingGroupCallContainer = view.findViewById(R.id.ongoing_group_call_container)
        ongoingCallText = view.findViewById(R.id.ongoing_call_text)
        joinGroupCallButton = view.findViewById(R.id.join_group_call_button)
        ongoingCallDivider = view.findViewById(R.id.ongoing_call_divider)
        groupCallDuration = view.findViewById(R.id.group_call_duration)
    }

    private fun initializeOnClickListeners() {
        listItem.setOnClickListener(
            object : DebouncedOnClickListener(500) {
                override fun onDebouncedClick(v: View) {
                    // position may have changed after the item was bound. query current position from holder
                    val currentPos = layoutPosition
                    if (currentPos >= 0) {
                        logger.info("Message clicked")
                        clickListener.onItemClick(v, currentPos)
                    }
                }
            },
        )
        listItem.setOnLongClickListener { v: View ->
            // position may have changed after the item was bound. query current position from holder
            val currentPos = layoutPosition
            if (currentPos >= 0) {
                return@setOnLongClickListener clickListener.onItemLongClick(v, currentPos)
            }
            false
        }

        avatarView.setOnClickListener { v: View ->
            // position may have changed after the item was bound. query current position from holder
            val currentPos = layoutPosition
            if (currentPos >= 0) {
                clickListener.onAvatarClick(v, currentPos)
            }
        }

        joinGroupCallButton.setOnClickListener {
            val currentPos = layoutPosition
            if (currentPos >= 0) {
                clickListener.onJoinGroupCallClick(currentPos)
            }
        }
    }

    fun initializeMessageListItemDateView(item: MessageListAdapterItem) {
        dateView.text = item.latestMessageDate
        dateView.contentDescription = item.latestMessageDateContentDescription
        dateView.visibility = VISIBLE
        TextViewCompat.setTextAppearance(dateView, R.style.Threema_TextAppearance_List_ThirdLine)
    }

    @SuppressLint("SimpleDateFormat")
    private fun initializeMessageListView(messageListAdapterItem: MessageListAdapterItem) {
        // Show or hide pin tag
        val isPinTagged = messageListAdapterItem.isPinTagged
        ViewUtil.show(pinBar, isPinTagged)
        ViewUtil.show(pinIcon, isPinTagged)

        val latestMessage = messageListAdapterItem.latestMessage

        var text = messageListAdapterItem.conversationModel.messageReceiver.displayName
        if (params.showLastUpdate) {
            // For debugging purposes, developers can enable the "show lastUpdate" option in the developer settings.
            val formattedLastUpdate = messageListAdapterItem.conversationModel.lastUpdate?.let {
                SimpleDateFormat("yyMMdd-HHmmss").format(it)
            } ?: "[null]"
            text = "$formattedLastUpdate | $text"
        }
        fromView.text = text

        val draft = messageListAdapterItem.getDraft()

        val isHidden = messageListAdapterItem.isPrivateChat

        // Initialize subject
        subjectView.visibility = VISIBLE
        subjectView.text = params.emojiMarkupUtil.formatBodyTextString(
            context,
            draft?.toString() ?: messageListAdapterItem.latestMessageSubject,
            100,
        )

        groupMemberName.text =
            if (isHidden) "" else messageListAdapterItem.latestMessageGroupMemberName

        if (draft != null) {
            initializeDraft()
        } else if (latestMessage != null) {
            initializeLatestMessage(messageListAdapterItem)
        } else {
            initializeEmptyChat()
        }

        initializeUnreadAppearance(messageListAdapterItem)

        initializeMuteAppearance(messageListAdapterItem)

        initializeDeliveryView(messageListAdapterItem, isHidden, draft != null)

        initializeGroupCallIndicator(messageListAdapterItem)

        messageListAdapterItem.latestMessage?.isDeleted?.let {
            initializeDeletedAppearance(
                isDeleted = it,
                isOutbox = messageListAdapterItem.latestMessage.isOutbox,
            )
        }

        initializeHiddenAppearance(isHidden)

        AdapterUtil.styleConversation(fromView, messageListAdapterItem.conversationModel)

        AvatarListItemUtil.loadAvatar(
            messageListAdapterItem.conversationModel,
            params.contactService,
            params.groupService,
            params.distributionListService,
            avatarListItemHolder,
            requestManager,
        )

        updateTypingIndicator(messageListAdapterItem.isTyping, isHidden)

        if (params.isTablet) {
            // handle selection in multi-pane mode
            if (params.highlightUid != null && params.highlightUid == messageListAdapterItem.uid && context is ComposeMessageActivity) {
                listItemFG.setBackgroundResource(R.color.settings_multipane_selection_bg)
            } else {
                listItemFG.setBackgroundColor(params.backgroundColor)
            }
        }
    }

    private fun initializeUnreadAppearance(messageListAdapterItem: MessageListAdapterItem) {
        val unreadCountText = messageListAdapterItem.unreadCountText
        if (unreadCountText != null) {
            TextViewCompat.setTextAppearance(
                fromView,
                R.style.Threema_TextAppearance_List_FirstLine_Bold,
            )
            TextViewCompat.setTextAppearance(
                subjectView,
                R.style.Threema_TextAppearance_List_SecondLine_Bold,
            )
            TextViewCompat.setTextAppearance(
                groupMemberName,
                R.style.Threema_TextAppearance_List_SecondLine_Bold,
            )
            unreadCountView.text = unreadCountText
            unreadCountView.visibility = VISIBLE
            unreadBar.visibility = VISIBLE
        } else {
            TextViewCompat.setTextAppearance(
                fromView,
                R.style.Threema_TextAppearance_List_FirstLine,
            )
            TextViewCompat.setTextAppearance(
                subjectView,
                R.style.Threema_TextAppearance_List_SecondLine,
            )
            TextViewCompat.setTextAppearance(
                groupMemberName,
                R.style.Threema_TextAppearance_List_SecondLine,
            )
            unreadCountView.visibility = GONE
            unreadBar.visibility = GONE
        }
    }

    private fun initializeDraft() {
        attachmentView.visibility = GONE
        deliveryView.visibility = GONE
        dateView.text = strings.draftText
        dateView.contentDescription = null
        TextViewCompat.setTextAppearance(
            dateView,
            R.style.Threema_TextAppearance_List_ThirdLine_Red,
        )
        dateView.visibility = VISIBLE
    }

    private fun initializeLatestMessage(messageListAdapterItem: MessageListAdapterItem) {
        // Set the date of the latest message
        initializeMessageListItemDateView(messageListAdapterItem)

        val viewElement = messageListAdapterItem.latestMessageViewElement
        // Configure subject
        if (viewElement?.icon != null) {
            attachmentView.visibility = VISIBLE
            attachmentView.setImageResource(viewElement.icon)
            attachmentView.setColorFilter(
                when {
                    viewElement.color != null -> ContextCompat.getColor(context, viewElement.color)
                    else -> params.regularColor
                },
            )
            attachmentView.contentDescription =
                Objects.requireNonNullElse(viewElement.placeholder, "")
        } else {
            attachmentView.visibility = GONE
        }
        subjectView.contentDescription = viewElement?.contentDescription ?: ""
    }

    private fun initializeEmptyChat() {
        attachmentView.visibility = GONE
        dateView.visibility = GONE
        dateView.contentDescription = null
    }

    private fun initializeDeliveryView(
        messageListAdapterItem: MessageListAdapterItem,
        isHiddenChat: Boolean,
        hasDraft: Boolean,
    ) {
        if (isHiddenChat || hasDraft) {
            deliveryView.visibility = GONE
        } else {
            deliveryView.visibility = VISIBLE
            @DrawableRes
            val deliveryIconRes: Int? = messageListAdapterItem.conversationModel.getConversationIconRes()
            if (deliveryIconRes != null) {
                deliveryView.setImageResource(deliveryIconRes)
                deliveryView.contentDescription = when {
                    messageListAdapterItem.isContactConversation -> strings.stateSent
                    messageListAdapterItem.isNotesGroup() -> strings.notes
                    messageListAdapterItem.isGroupConversation -> strings.groups
                    else -> strings.distributionLists
                }
                deliveryView.setColorFilter(params.regularColor)
            } else {
                if (messageListAdapterItem.latestMessage != null) {
                    // In case there is a latest message but no icon is set, we need to get the
                    // icon for the current message state
                    params.stateBitmapUtil?.setStateDrawable(
                        context,
                        messageListAdapterItem.latestMessage,
                        deliveryView,
                        ConfigUtils.getColorFromAttribute(context, R.attr.colorOnSurface),
                    )
                } else {
                    deliveryView.visibility = GONE
                }
            }
        }
    }

    private fun initializeMuteAppearance(messageListAdapterItem: MessageListAdapterItem) {
        @DrawableRes
        val muteStatusResource: Int? = messageListAdapterItem.muteStatusDrawableRes
        if (muteStatusResource != null) {
            muteStatus.visibility = VISIBLE
            muteStatus.setImageResource(muteStatusResource)
            muteStatus.setColorFilter(params.regularColor)
        } else {
            muteStatus.visibility = GONE
        }
    }

    private fun initializeHiddenAppearance(isHiddenChat: Boolean) {
        if (isHiddenChat) {
            hiddenStatus.visibility = VISIBLE
            subjectView.setText(R.string.private_chat_subject)
            attachmentView.visibility = GONE
            dateView.visibility = INVISIBLE
            deliveryView.visibility = GONE
        } else {
            hiddenStatus.visibility = GONE
        }
    }

    private fun initializeDeletedAppearance(
        isDeleted: Boolean,
        isOutbox: Boolean,
    ) {
        if (isDeleted) {
            subjectView.setText(R.string.message_was_deleted)
            subjectView.setTextColor(
                ResourcesCompat.getColorStateList(
                    context.resources,
                    if (isOutbox) R.color.bubble_send_text_colorstatelist else R.color.bubble_receive_text_colorstatelist,
                    context.theme,
                ),
            )
            subjectView.setTypeface(subjectView.typeface, Typeface.ITALIC)
            attachmentView.visibility = GONE
            deliveryView.visibility = GONE
        }
    }

    /**
     * Initializes the view holder regarding ongoing group calls. If a group call is running, it
     * makes the join group call button visible and disables all the views that would be hidden
     * by the button.
     */
    private fun initializeGroupCallIndicator(messageListAdapterItem: MessageListAdapterItem) {
        val group = messageListAdapterItem.groupModel
        if (group != null &&
            !messageListAdapterItem.isNotesGroup() &&
            messageListAdapterItem.isGroupMember()
        ) {
            val call: GroupCallDescription? =
                groupCallManager.getCurrentChosenCall(group.localGroupId)
            if (call != null) {
                val isJoined = groupCallManager.isJoinedCall(call)

                isGroupCallOngoing = true
                isGroupCallJoined = isJoined

                // Initialize group call related views
                joinGroupCallButton.visibility = VISIBLE
                joinGroupCallButton.setText(
                    if (isJoined) {
                        R.string.voip_gc_open_call
                    } else {
                        R.string.voip_gc_join_call
                    },
                )
                ongoingCallText.setText(
                    if (isJoined) {
                        R.string.voip_gc_in_call
                    } else {
                        R.string.voip_gc_ongoing_call
                    },
                )
                ongoingGroupCallContainer.visibility = VISIBLE

                // Make views invisible that are only displayed when no group call is happening
                groupMemberName.visibility = GONE
                unreadCountView.visibility = GONE
                pinIcon.visibility = GONE
                typingContainer.visibility = GONE
                deliveryView.visibility = GONE
                subjectView.visibility = GONE
                dateView.visibility = GONE
                attachmentView.visibility = GONE
                muteStatus.visibility = GONE
            } else {
                joinGroupCallButton.visibility = GONE
                ongoingGroupCallContainer.visibility = GONE

                isGroupCallOngoing = false
                isGroupCallJoined = false
            }
        } else {
            joinGroupCallButton.visibility = GONE
            ongoingGroupCallContainer.visibility = GONE
        }
    }

    private fun updateTypingIndicator(isTyping: Boolean, isHidden: Boolean) {
        val isTypingIndicatorHidden = !isTyping || isHidden
        latestMessageContainer.visibility = if (isTypingIndicatorHidden) VISIBLE else GONE
        typingContainer.visibility = if (isTypingIndicatorHidden) GONE else VISIBLE
    }

    @AnyThread
    fun updateGroupCallDuration(call: GroupCallDescription) {
        val runningSince = getRunningSince(call, context)
        startGroupCallDuration(runningSince)
    }

    @AnyThread
    private fun startGroupCallDuration(base: Long) {
        RuntimeUtil.runOnUiThread {
            groupCallDuration.apply {
                this.base = base
                start()
                visibility = VISIBLE
            }
            ongoingCallDivider.visibility = VISIBLE
        }
    }

    @AnyThread
    private fun stopGroupCallDuration() {
        RuntimeUtil.runOnUiThread {
            groupCallDuration.apply {
                stop()
                visibility = GONE
            }
            ongoingCallDivider.visibility = GONE
        }
    }
}
