/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2015-2025 Threema GmbH
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

package ch.threema.app.contactdetails;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.RequestManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import ch.threema.app.BuildConfig;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.dialogs.PublicKeyDialog;
import ch.threema.app.glide.AvatarOptions;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.services.BlockedIdentitiesService;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.ExcludedSyncIdentitiesService;
import ch.threema.app.services.GroupService;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.ui.SectionHeaderView;
import ch.threema.app.ui.VerificationLevelImageView;
import ch.threema.app.utils.AndroidContactUtil;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.ViewUtil;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.data.models.ContactModelData;
import ch.threema.domain.models.ReadReceiptPolicy;
import ch.threema.domain.models.TypingIndicatorPolicy;
import ch.threema.domain.models.WorkVerificationLevel;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.protobuf.csp.e2e.fs.Terminate;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.GroupModel;

/**
 * The adapter for contact details.
 * <p>
 * It is comprised of two parts:
 * <p>
 * - The header, which contains the Threema ID, nickname, privacy settings, etc
 * - The items, which contain the group memberships
 * <p>
 * Note that this adapter does not need to be reactive. It is simply recreated by the activity
 * when data changes.
 */
public class ContactDetailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final Logger logger = LoggingUtil.getThreemaLogger("ContactDetailAdapter");

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final Context context;
    private ContactService contactService;
    private GroupService groupService;
    @NonNull
    private final PreferenceService preferenceService;
    @NonNull
    private final ExcludedSyncIdentitiesService excludedSyncIdentitiesService;
    @NonNull
    private final BlockedIdentitiesService blockedIdentitiesService;
    private final @NonNull ch.threema.data.models.ContactModel contactModel;
    private final @NonNull ContactModelData contactModelData;
    private final List<GroupModel> values;
    private OnClickListener onClickListener;
    private final @NonNull RequestManager requestManager;

    public static class ItemHolder extends RecyclerView.ViewHolder {
        public final @NonNull View view;
        public final @NonNull TextView nameView;
        public final @NonNull ImageView avatarView, statusView;

        public ItemHolder(@NonNull View view) {
            super(view);
            this.view = view;
            this.nameView = Objects.requireNonNull(itemView.findViewById(R.id.contact_name));
            this.avatarView = Objects.requireNonNull(itemView.findViewById(R.id.contact_avatar));
            this.statusView = Objects.requireNonNull(itemView.findViewById(R.id.status));
        }
    }

    public class HeaderHolder extends RecyclerView.ViewHolder {
        private final @NonNull VerificationLevelImageView verificationLevelImageView;
        private final @NonNull SectionHeaderView jobTitleHeaderView;
        private final @NonNull TextView jobTitleTextView;
        private final @NonNull SectionHeaderView departmentHeaderView;
        private final @NonNull TextView departmentTextView;
        private final @NonNull TextView threemaIdView;
        private final @NonNull ContactAvailabilityView availabilityView;
        private final @NonNull MaterialSwitch synchronize;
        private final @NonNull View nicknameContainer, synchronizeContainer;
        private final @NonNull ImageView syncSourceIcon;
        private final @NonNull TextView publicNickNameView;
        private final @NonNull LinearLayout groupMembershipTitle;
        private final @NonNull MaterialAutoCompleteTextView readReceiptsSpinner, typingIndicatorsSpinner;
        private final @NonNull View clearForwardSecuritySection;
        private final @NonNull MaterialButton clearForwardSecurityButton;
        private int onThreemaIDClickCount = 0;

        public HeaderHolder(View view) {
            super(view);

            this.jobTitleHeaderView = Objects.requireNonNull(itemView.findViewById(R.id.header_job_title));
            this.jobTitleTextView = Objects.requireNonNull(itemView.findViewById(R.id.value_job_title));
            this.departmentHeaderView = Objects.requireNonNull(itemView.findViewById(R.id.header_department));
            this.departmentTextView = Objects.requireNonNull(itemView.findViewById(R.id.value_department));
            this.threemaIdView = Objects.requireNonNull(itemView.findViewById(R.id.threema_id));
            this.availabilityView = Objects.requireNonNull(itemView.findViewById(R.id.availability_status));
            this.verificationLevelImageView = Objects.requireNonNull(itemView.findViewById(R.id.verification_level_image));
            ImageView verificationLevelIconView = Objects.requireNonNull(itemView.findViewById(R.id.verification_information_icon));
            this.synchronize = Objects.requireNonNull(itemView.findViewById(R.id.synchronize_contact));
            this.synchronizeContainer = Objects.requireNonNull(itemView.findViewById(R.id.synchronize_contact_container));
            this.nicknameContainer = Objects.requireNonNull(itemView.findViewById(R.id.nickname_container));
            this.publicNickNameView = Objects.requireNonNull(itemView.findViewById(R.id.public_nickname));
            this.groupMembershipTitle = Objects.requireNonNull(itemView.findViewById(R.id.group_members_title_container));
            this.syncSourceIcon = Objects.requireNonNull(itemView.findViewById(R.id.sync_source_icon));
            this.readReceiptsSpinner = Objects.requireNonNull(itemView.findViewById(R.id.read_receipts_spinner));
            this.typingIndicatorsSpinner = Objects.requireNonNull(itemView.findViewById(R.id.typing_indicators_spinner));
            this.clearForwardSecuritySection = Objects.requireNonNull(itemView.findViewById(R.id.clear_forward_security_section));
            this.clearForwardSecurityButton = Objects.requireNonNull(itemView.findViewById(R.id.clear_forward_security));

            verificationLevelIconView.setOnClickListener(v -> {
                if (onClickListener != null) {
                    onClickListener.onVerificationInfoClick(v);
                }
            });

            threemaIdView.setOnLongClickListener(ignored -> {
                copyTextToClipboard(contactModelData.identity, R.string.contact_details_id_copied);
                return true;
            });

            if (!BuildConfig.AVAILABILITY_STATUS_ENABLED) {
                availabilityView.setVisibility(View.GONE);
            }

            // When clicking ten times on the Threema ID, the clear forward security session button
            // becomes visible.
            threemaIdView.setOnClickListener(v -> {
                onThreemaIDClickCount++;
                if (onThreemaIDClickCount >= 10) {
                    onThreemaIDClickCount = 0;
                    clearForwardSecuritySection.setVisibility(View.VISIBLE);
                    clearForwardSecurityButton.setOnClickListener(clearButton -> {
                        ContactModel contactModel = contactService.getByIdentity(contactModelData.identity);
                        if (contactModel == null) {
                            logger.error("Contact model is null. Cannot schedule fs session deletion task.");
                            return;
                        }

                        try {
                            ThreemaApplication.requireServiceManager()
                                .getTaskCreator()
                                .scheduleDeleteAndTerminateFSSessionsTaskAsync(
                                    contactModel,
                                    Terminate.Cause.RESET
                                );
                            Toast.makeText(clearButton.getContext(), R.string.forward_security_cleared, Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(clearButton.getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });

            publicNickNameView.setOnLongClickListener(ignored -> {
                copyTextToClipboard(contactModelData.nickname, R.string.contact_details_nickname_copied);
                return true;
            });


            itemView.findViewById(R.id.public_key_button).setOnClickListener(v -> {
                if (context instanceof AppCompatActivity) {
                    PublicKeyDialog
                        .newInstance(context.getString(R.string.public_key_for, contactModelData.identity), contactModelData.publicKey)
                        .show(((AppCompatActivity) context).getSupportFragmentManager(), "pk");
                }
            });
        }

        private void copyTextToClipboard(String data, @StringRes int toastResId) {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(null, data);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(context, toastResId, Toast.LENGTH_SHORT).show();
        }
    }

    @UiThread
    public ContactDetailAdapter(
        Context context,
        List<GroupModel> values,
        @NonNull ch.threema.data.models.ContactModel contactModel,
        @NonNull ContactModelData contactModelData,
        @NonNull RequestManager requestManager
    ) {
        this.context = context;
        this.values = values;
        this.contactModel = contactModel;
        this.contactModelData = contactModelData;
        this.requestManager = requestManager;

        ServiceManager serviceManager = ThreemaApplication.requireServiceManager();
        this.excludedSyncIdentitiesService = serviceManager.getExcludedSyncIdentitiesService();
        this.blockedIdentitiesService = serviceManager.getBlockedIdentitiesService();
        this.preferenceService = serviceManager.getPreferenceService();

        try {
            this.contactService = serviceManager.getContactService();
            this.groupService = serviceManager.getGroupService();
        } catch (Exception e) {
            logger.error("Failed to set up services", e);
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_ITEM) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact_detail, parent, false);

            return new ItemHolder(v);
        } else if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.header_contact_detail, parent, false);

            return new HeaderHolder(v);
        }
        throw new RuntimeException("no matching item type");
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ItemHolder) {
            ItemHolder itemHolder = (ItemHolder) holder;
            final GroupModel groupModel = getItem(position);

            this.groupService.loadAvatarIntoImage(
                groupModel,
                itemHolder.avatarView,
                AvatarOptions.PRESET_DEFAULT_FALLBACK,
                requestManager
            );

            String groupName = groupModel.getName();
            if (groupName == null || groupName.isEmpty()) {
                groupName = groupService.getMembersString(groupModel);
            }
            itemHolder.nameView.setText(groupName);

            if (groupService.isGroupCreator(groupModel)) {
                itemHolder.statusView.setImageResource(R.drawable.ic_group_outline);
            } else {
                itemHolder.statusView.setImageResource(R.drawable.ic_group_filled);
            }
            itemHolder.view.setOnClickListener(v -> onClickListener.onItemClick(v, groupModel));
        } else {
            HeaderHolder headerHolder = (HeaderHolder) holder;

            String identityAdditional = null;
            switch (this.contactModelData.activityState) {
                case ACTIVE:
                    if (blockedIdentitiesService.isBlocked(contactModelData.identity)) {
                        identityAdditional = context.getString(R.string.blocked);
                    }
                    break;
                case INACTIVE:
                    identityAdditional = context.getString(R.string.contact_state_inactive);
                    break;
                case INVALID:
                    identityAdditional = context.getString(R.string.contact_state_invalid);
                    break;
            }

            final boolean shouldShowJobTitle = contactModelData.workVerificationLevel == WorkVerificationLevel.WORK_SUBSCRIPTION_VERIFIED
                && contactModelData.jobTitle != null && !contactModelData.jobTitle.isBlank();
            ViewUtil.show(headerHolder.jobTitleHeaderView, shouldShowJobTitle);
            ViewUtil.show(headerHolder.jobTitleTextView, shouldShowJobTitle);
            if (shouldShowJobTitle) {
                headerHolder.jobTitleTextView.setText(contactModelData.jobTitle);
            }

            final boolean shouldShowDepartment = contactModelData.workVerificationLevel == WorkVerificationLevel.WORK_SUBSCRIPTION_VERIFIED
                && contactModelData.department != null && !contactModelData.department.isBlank();
            ViewUtil.show(headerHolder.departmentHeaderView, shouldShowDepartment);
            ViewUtil.show(headerHolder.departmentTextView, shouldShowDepartment);
            if (shouldShowDepartment) {
                headerHolder.departmentTextView.setText(contactModelData.department);
            }

            headerHolder.threemaIdView.setText(
                contactModelData.identity + (identityAdditional != null ? " (" + identityAdditional + ")" : "")
            );
            headerHolder.verificationLevelImageView.setVerificationLevel(
                contactModelData.verificationLevel,
                contactModelData.workVerificationLevel
            );
            headerHolder.verificationLevelImageView.setVisibility(View.VISIBLE);

            boolean isSyncExcluded = excludedSyncIdentitiesService.isExcluded(contactModelData.identity);

            if (preferenceService.isSyncContacts()
                && (contactModelData.isLinkedToAndroidContact() || isSyncExcluded)
                && ConfigUtils.isPermissionGranted(ThreemaApplication.getAppContext(), Manifest.permission.READ_CONTACTS)
            ) {
                headerHolder.synchronizeContainer.setVisibility(View.VISIBLE);

                Drawable icon = null;
                try {
                    icon = AndroidContactUtil.getInstance().getAccountIcon(contactModelData.androidContactLookupKey);
                } catch (SecurityException e) {
                    logger.error("Could not access android account icon", e);
                }
                if (icon != null) {
                    headerHolder.syncSourceIcon.setImageDrawable(icon);
                    headerHolder.syncSourceIcon.setVisibility(View.VISIBLE);
                } else {
                    headerHolder.syncSourceIcon.setVisibility(View.GONE);
                }

                headerHolder.synchronize.setChecked(isSyncExcluded);
                headerHolder.synchronize.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        excludedSyncIdentitiesService.excludeFromSync(contactModelData.identity, TriggerSource.LOCAL);
                    } else {
                        excludedSyncIdentitiesService.removeExcludedIdentity(contactModelData.identity, TriggerSource.LOCAL);
                    }
                });
            } else {
                headerHolder.synchronizeContainer.setVisibility(View.GONE);
            }

            final String nicknameString = contactModelData.nickname;
            if (nicknameString != null && !nicknameString.isEmpty()) {
                headerHolder.publicNickNameView.setText(nicknameString);
            } else {
                headerHolder.nicknameContainer.setVisibility(View.GONE);
            }

            if (!values.isEmpty()) {
                headerHolder.groupMembershipTitle.setVisibility(View.VISIBLE);
            } else {
                headerHolder.groupMembershipTitle.setVisibility(View.GONE);
            }

            initializeReadReceiptsSpinner(headerHolder);
            initializeTypingIndicatorSpinner(headerHolder);
        }
    }

    @Override
    public int getItemCount() {
        return values.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (isPositionHeader(position)) {
            return TYPE_HEADER;
        }
        return TYPE_ITEM;
    }

    private boolean isPositionHeader(int position) {
        return position == 0;
    }

    private GroupModel getItem(int position) {
        return values.get(position - 1);
    }

    public void setOnClickListener(OnClickListener listener) {
        onClickListener = listener;
    }

    public interface OnClickListener {
        void onItemClick(View v, GroupModel groupModel);

        void onVerificationInfoClick(View v);
    }

    private void initializeReadReceiptsSpinner(@NonNull HeaderHolder headerHolder) {
        final String[] choices = context.getResources().getStringArray(R.array.receipts_override_choices);
        choices[0] = context.getString(R.string.receipts_override_choice_default,
            choices[preferenceService.areReadReceiptsEnabled() ? 1 : 2]);

        int initialReadReceiptPosition;
        switch (contactModelData.readReceiptPolicy) {
            case SEND:
                initialReadReceiptPosition = 1;
                break;
            case DONT_SEND:
                initialReadReceiptPosition = 2;
                break;
            case DEFAULT:
            default:
                initialReadReceiptPosition = 0;
                break;
        }
        ArrayAdapter<String> readReceiptsAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, choices);
        headerHolder.readReceiptsSpinner.setAdapter(readReceiptsAdapter);
        headerHolder.readReceiptsSpinner.setText(choices[initialReadReceiptPosition], false);
        headerHolder.readReceiptsSpinner.setOnItemClickListener((parent, view, readReceiptPosition, id) -> {
            switch (readReceiptPosition) {
                case 0:
                    contactModel.setReadReceiptPolicyFromLocal(ReadReceiptPolicy.DEFAULT);
                    break;
                case 1:
                    contactModel.setReadReceiptPolicyFromLocal(ReadReceiptPolicy.SEND);
                    break;
                case 2:
                    contactModel.setReadReceiptPolicyFromLocal(ReadReceiptPolicy.DONT_SEND);
                    break;
                default:
                    logger.warn("Invalid position for read receipt policy: {}", readReceiptPosition);
                    break;
            }
        });
    }

    private void initializeTypingIndicatorSpinner(@NonNull HeaderHolder headerHolder) {
        final String[] typingChoices = context.getResources().getStringArray(R.array.receipts_override_choices);
        typingChoices[0] = context.getString(R.string.receipts_override_choice_default,
            typingChoices[preferenceService.isTypingIndicatorEnabled() ? 1 : 2]);

        int initialTypingIndicatorPosition;
        switch (contactModelData.typingIndicatorPolicy) {
            case SEND:
                initialTypingIndicatorPosition = 1;
                break;
            case DONT_SEND:
                initialTypingIndicatorPosition = 2;
                break;
            case DEFAULT:
            default:
                initialTypingIndicatorPosition = 0;
                break;
        }

        ArrayAdapter<String> typingIndicatorAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, typingChoices);
        headerHolder.typingIndicatorsSpinner.setAdapter(typingIndicatorAdapter);
        headerHolder.typingIndicatorsSpinner.setText(typingChoices[initialTypingIndicatorPosition], false);
        headerHolder.typingIndicatorsSpinner.setOnItemClickListener((parent, view, typingIndicatorPosition, id) -> {
            switch (typingIndicatorPosition) {
                case 0:
                    contactModel.setTypingIndicatorPolicyFromLocal(TypingIndicatorPolicy.DEFAULT);
                    break;
                case 1:
                    contactModel.setTypingIndicatorPolicyFromLocal(TypingIndicatorPolicy.SEND);
                    break;
                case 2:
                    contactModel.setTypingIndicatorPolicyFromLocal(TypingIndicatorPolicy.DONT_SEND);
                    break;
                default:
                    logger.warn("Invalid position for typing indicator policy: {}", typingIndicatorPosition);
                    break;
            }
        });
    }

}
