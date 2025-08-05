/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2013-2025 Threema GmbH
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

package ch.threema.app.fragments;

import static ch.threema.app.AppConstants.EMAIL_LINKED_PLACEHOLDER;
import static ch.threema.app.AppConstants.PHONE_LINKED_PLACEHOLDER;

import android.animation.LayoutTransition;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;

import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.activities.ExportIDActivity;
import ch.threema.app.activities.ProfilePicRecipientsActivity;
import ch.threema.app.activities.ThreemaActivity;
import ch.threema.app.asynctasks.DeleteIdentityAsyncTask;
import ch.threema.app.asynctasks.LinkWithEmailAsyncTask;
import ch.threema.app.dialogs.GenericAlertDialog;
import ch.threema.app.dialogs.GenericProgressDialog;
import ch.threema.app.dialogs.PasswordEntryDialog;
import ch.threema.app.dialogs.PublicKeyDialog;
import ch.threema.app.dialogs.SimpleStringAlertDialog;
import ch.threema.app.dialogs.TextEntryDialog;
import ch.threema.app.emojis.EmojiTextView;
import ch.threema.app.listeners.ProfileListener;
import ch.threema.app.listeners.SMSVerificationListener;
import ch.threema.app.managers.ListenerManager;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.routines.CheckIdentityRoutine;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.ContactService.ProfilePictureSharePolicy;
import ch.threema.app.services.FileService;
import ch.threema.app.services.IdListService;
import ch.threema.app.services.LocaleService;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.services.QRCodeServiceImpl;
import ch.threema.app.services.UserService;
import ch.threema.app.tasks.TaskCreator;
import ch.threema.app.ui.AvatarEditView;
import ch.threema.app.ui.InsetSides;
import ch.threema.app.ui.QRCodePopup;
import ch.threema.app.restrictions.AppRestrictionUtil;
import ch.threema.app.ui.ViewExtensionsKt;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.DialogUtil;
import ch.threema.app.utils.HiddenChatUtil;
import ch.threema.app.utils.LocaleUtil;
import ch.threema.app.utils.LogUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.ShareUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.domain.protocol.api.LinkMobileNoException;
import ch.threema.domain.protocol.csp.ProtocolDefines;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.localcrypto.MasterKeyLockedException;

/**
 * This is one of the tabs in the home screen. It shows the user's profile.
 */
public class MyIDFragment extends MainFragment
    implements
    View.OnClickListener,
    GenericAlertDialog.DialogClickListener,
    TextEntryDialog.TextEntryDialogClickListener,
    PasswordEntryDialog.PasswordEntryDialogClickListener {
    private static final Logger logger = LoggingUtil.getThreemaLogger("MyIDFragment");

    private static final int MAX_REVOCATION_PASSWORD_LENGTH = 256;
    private static final int LOCK_CHECK_REVOCATION = 33;
    private static final int LOCK_CHECK_DELETE_ID = 34;
    private static final int LOCK_CHECK_EXPORT_ID = 35;

    private ServiceManager serviceManager;
    private UserService userService;
    private PreferenceService preferenceService;
    private LocaleService localeService;
    private ContactService contactService;
    private FileService fileService;
    private IdListService profilePicRecipientsService;
    private TaskCreator taskCreator;

    private AvatarEditView avatarView;
    private EmojiTextView nicknameTextView;
    private boolean hidden = false;
    private View fragmentView;

    private boolean isReadonlyProfile = false;
    private boolean isDisabledProfilePicReleaseSettings = false;

    private static final String DIALOG_TAG_EDIT_NICKNAME = "cedit";
    private static final String DIALOG_TAG_SET_REVOCATION_KEY = "setRevocationKey";
    private static final String DIALOG_TAG_LINKED_EMAIL = "linkedEmail";
    private static final String DIALOG_TAG_LINKED_MOBILE = "linkedMobile";
    private static final String DIALOG_TAG_REALLY_DELETE = "reallyDeleteId";
    private static final String DIALOG_TAG_DELETE_ID = "deleteId";
    private static final String DIALOG_TAG_LINKED_MOBILE_CONFIRM = "cfm";
    private static final String DIALOG_TAG_REVOKING = "revk";

    private final SMSVerificationListener smsVerificationListener = new SMSVerificationListener() {
        @Override
        public void onVerified() {
            RuntimeUtil.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updatePendingState(getView(), false);
                }
            });
        }

        @Override
        public void onVerificationStarted() {
            RuntimeUtil.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updatePendingState(getView(), false);
                }
            });
        }
    };

    private final ProfileListener profileListener = new ProfileListener() {
        @Override
        public void onAvatarChanged(@NonNull TriggerSource triggerSource) {
            // a profile picture has been set so it's safe to assume user wants others to see his pic
            if (triggerSource == TriggerSource.LOCAL &&
                !isDisabledProfilePicReleaseSettings &&
                preferenceService != null &&
                preferenceService.getProfilePicRelease() == PreferenceService.PROFILEPIC_RELEASE_NOBODY
            ) {
                preferenceService.setProfilePicRelease(PreferenceService.PROFILEPIC_RELEASE_EVERYONE);
                // Sync new policy setting to device group (if md is active)
                if (serviceManager.getMultiDeviceManager().isMultiDeviceActive()) {
                    taskCreator.scheduleReflectUserProfileShareWithPolicySyncTask(ProfilePictureSharePolicy.Policy.EVERYONE);
                }
                RuntimeUtil.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isAdded() && !isDetached() && fragmentView != null) {
                            MaterialAutoCompleteTextView spinner = fragmentView.findViewById(R.id.picrelease_spinner);
                            if (spinner != null) {
                                spinner.setText((CharSequence) spinner.getAdapter().getItem(preferenceService.getProfilePicRelease()), false);
                            }
                        }
                    }
                });
            }
        }

        @Override
        public void onNicknameChanged(String newNickname) {
            RuntimeUtil.runOnUiThread(() -> reloadNickname());
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);
    }

    @Override
    public void onResume() {
        super.onResume();

        setupPicReleaseSpinner();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (!this.requiredInstances()) {
            logger.error("could not instantiate required objects");
            return null;
        }

        fragmentView = getView();

        if (fragmentView == null) {
            fragmentView = inflater.inflate(R.layout.fragment_my_id, container, false);


            this.updatePendingState(fragmentView, true);

            LayoutTransition l = new LayoutTransition();
            l.enableTransitionType(LayoutTransition.CHANGING);
            ViewGroup viewGroup = fragmentView.findViewById(R.id.fragment_id_container);
            viewGroup.setLayoutTransition(l);

            ViewExtensionsKt.applyDeviceInsetsAsPadding(
                viewGroup,
                InsetSides.horizontal()
            );

            if (ConfigUtils.isWorkRestricted()) {
                Boolean value = AppRestrictionUtil.getBooleanRestriction(getString(R.string.restriction__readonly_profile));
                if (value != null) {
                    isReadonlyProfile = value;
                }

                value = AppRestrictionUtil.getBooleanRestriction(getString(R.string.restriction__disable_send_profile_picture));
                if (value != null) {
                    isDisabledProfilePicReleaseSettings = value;
                }
            }

            fragmentView.findViewById(R.id.policy_explain).setVisibility(isReadonlyProfile || AppRestrictionUtil.isBackupsDisabled(ThreemaApplication.getAppContext()) || AppRestrictionUtil.isIdBackupsDisabled(ThreemaApplication.getAppContext()) ? View.VISIBLE : View.GONE);

            final MaterialButton picReleaseConfImageView = fragmentView.findViewById(R.id.picrelease_config);
            picReleaseConfImageView.setOnClickListener(this);
            picReleaseConfImageView.setVisibility(preferenceService.getProfilePicRelease() == PreferenceService.PROFILEPIC_RELEASE_ALLOW_LIST ? View.VISIBLE : View.GONE);

            configureEditWithButton(fragmentView.findViewById(R.id.linked_email_layout), fragmentView.findViewById(R.id.change_email), isReadonlyProfile);
            configureEditWithButton(fragmentView.findViewById(R.id.linked_mobile_layout), fragmentView.findViewById(R.id.change_mobile), isReadonlyProfile);
            configureEditWithButton(fragmentView.findViewById(R.id.delete_id_layout), fragmentView.findViewById(R.id.delete_id), isReadonlyProfile);
            configureEditWithButton(fragmentView.findViewById(R.id.export_id_layout), fragmentView.findViewById(R.id.export_id), (AppRestrictionUtil.isBackupsDisabled(ThreemaApplication.getAppContext()) ||
                AppRestrictionUtil.isIdBackupsDisabled(ThreemaApplication.getAppContext())));

            if (ConfigUtils.isOnPremBuild()) {
                fragmentView.findViewById(R.id.revocation_key_layout).setVisibility(View.GONE);
            } else {
                configureEditWithButton(fragmentView.findViewById(R.id.revocation_key_layout), fragmentView.findViewById(R.id.revocation_key), isReadonlyProfile);
            }

            if (userService != null && userService.getIdentity() != null) {
                ((TextView) fragmentView.findViewById(R.id.my_id)).setText(userService.getIdentity());
                if (ConfigUtils.isOnPremBuild()) {
                    fragmentView.findViewById(R.id.my_id_share).setVisibility(View.GONE);
                } else {
                    fragmentView.findViewById(R.id.my_id_share).setOnClickListener(this);
                }
                fragmentView.findViewById(R.id.my_id_qr).setOnClickListener(this);

                fragmentView.findViewById(R.id.public_key_button).setOnClickListener(
                    v -> {
                        logger.info("Show my public key clicked");
                        PublicKeyDialog.newInstance(
                            getString(R.string.public_key_for, userService.getIdentity()),
                            userService.getPublicKey()
                        ).show(getParentFragmentManager(), "pk");
                    }
                );
            }

            this.avatarView = fragmentView.findViewById(R.id.avatar_edit_view);
            this.avatarView.setFragment(this);
            this.avatarView.setIsMyProfilePicture(true);
            this.avatarView.setContactModel(contactService.getMe());

            this.nicknameTextView = fragmentView.findViewById(R.id.nickname);

            if (isReadonlyProfile) {
                this.fragmentView.findViewById(R.id.profile_edit).setVisibility(View.GONE);
                this.avatarView.setEditable(false);
            } else {
                this.fragmentView.findViewById(R.id.profile_edit).setVisibility(View.VISIBLE);
                this.fragmentView.findViewById(R.id.profile_edit).setOnClickListener(this);
            }

            setupPicReleaseSpinner();

            if (isDisabledProfilePicReleaseSettings) {
                fragmentView.findViewById(R.id.picrelease_spinner).setVisibility(View.GONE);
                fragmentView.findViewById(R.id.picrelease_config).setVisibility(View.GONE);
                fragmentView.findViewById(R.id.picrelease_text).setVisibility(View.GONE);
            }

            reloadNickname();
        }

        ListenerManager.profileListeners.add(this.profileListener);

        return fragmentView;
    }

    private void setupPicReleaseSpinner() {
        if (fragmentView != null && preferenceService != null) {
            MaterialAutoCompleteTextView spinner = fragmentView.findViewById(R.id.picrelease_spinner);
            if (spinner != null) {
                ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                    requireContext(),
                    R.array.picrelease_choices,
                    android.R.layout.simple_spinner_dropdown_item
                );
                spinner.setAdapter(adapter);
                spinner.setText(adapter.getItem(preferenceService.getProfilePicRelease()), false);
                spinner.setOnItemClickListener((parent, view, position, id) -> onPicReleaseSpinnerItemClicked(view, position));
            }
        }
    }

    private void onPicReleaseSpinnerItemClicked(View view, int position) {
        final @Nullable ProfilePictureSharePolicy.Policy sharePolicy = ProfilePictureSharePolicy.Policy.fromIntOrNull(position);
        if (sharePolicy == null) {
            logger.error("Failed to get concrete enum value of type ProfilePictureSharePolicy.Policy for ordinal value {}", position);
            return;
        }

        final int oldPosition = preferenceService.getProfilePicRelease();
        preferenceService.setProfilePicRelease(position);

        fragmentView.findViewById(R.id.picrelease_config)
            .setVisibility(position == PreferenceService.PROFILEPIC_RELEASE_ALLOW_LIST ? View.VISIBLE : View.GONE);

        // Only continue of the value actually changes from before
        if (position == oldPosition) {
            return;
        }

        if (sharePolicy == ProfilePictureSharePolicy.Policy.ALLOW_LIST) {
            launchProfilePictureRecipientsSelector(view);
            if (serviceManager.getMultiDeviceManager().isMultiDeviceActive()) {
                // Sync new policy setting with currently set allow list values into device group (if md is active)
                taskCreator.scheduleReflectUserProfileShareWithAllowListSyncTask(
                    new HashSet<>(Arrays.asList(profilePicRecipientsService.getAll()))
                );
            }
        } else {
            if (serviceManager.getMultiDeviceManager().isMultiDeviceActive()) {
                // Sync new policy setting to device group (if md is active)
                taskCreator.scheduleReflectUserProfileShareWithPolicySyncTask(sharePolicy);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        ListenerManager.smsVerificationListeners.add(this.smsVerificationListener);
    }

    @Override
    public void onStop() {
        ListenerManager.smsVerificationListeners.remove(this.smsVerificationListener);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        ListenerManager.profileListeners.remove(this.profileListener);
        super.onDestroyView();
    }

    private void updatePendingState(final View fragmentView, boolean force) {
        if (!this.requiredInstances()) {
            return;
        }

        // update texts and enforce another update if the status of one value is pending
        if (updatePendingStateTexts(fragmentView) || force) {
            new Thread(
                new CheckIdentityRoutine(
                    userService,
                    success -> {
                        //update after routine
                        RuntimeUtil.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updatePendingStateTexts(fragmentView);
                            }
                        });
                    },
                    TriggerSource.LOCAL
                )
            ).start();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private boolean updatePendingStateTexts(View fragmentView) {
        boolean pending = false;

        if (!this.requiredInstances()) {
            return false;
        }

        if (!isAdded() || isDetached() || isRemoving()) {
            return false;
        }

        //update email linked text
        TextView linkedEmailText = fragmentView.findViewById(R.id.linked_email);
        String email = this.userService.getLinkedEmail();
        email = EMAIL_LINKED_PLACEHOLDER.equals(email) ? getString(R.string.unchanged) : email;

        switch (userService.getEmailLinkingState()) {
            case UserService.LinkingState_LINKED:
                linkedEmailText.setText(email + " (" + getString(R.string.verified) + ")");
                //nothing;
                break;
            case UserService.LinkingState_PENDING:
                linkedEmailText.setText(email + " (" + getString(R.string.pending) + ")");
                pending = true;
                break;
            default:
                linkedEmailText.setText(getString(R.string.not_linked));

        }
        linkedEmailText.invalidate();

        //update mobile text
        TextView linkedMobileText = fragmentView.findViewById(R.id.linked_mobile);

        // default
        linkedMobileText.setText(getString(R.string.not_linked));

        String mobileNumber = this.userService.getLinkedMobile();
        mobileNumber = PHONE_LINKED_PLACEHOLDER.equals(mobileNumber) ? getString(R.string.unchanged) : mobileNumber;

        switch (userService.getMobileLinkingState()) {
            case UserService.LinkingState_LINKED:
                if (mobileNumber != null) {
                    final String newMobileNumber = mobileNumber;
                    // lookup phone numbers asynchronously
                    new AsyncTask<TextView, Void, String>() {
                        private TextView textView;

                        @Override
                        protected String doInBackground(TextView... params) {
                            textView = params[0];

                            if (isAdded() && getContext() != null) {
                                final String verified = getContext().getString(R.string.verified);
                                return localeService.getHRPhoneNumber(newMobileNumber) + " (" + verified + ")";
                            }
                            return null;
                        }

                        @Override
                        protected void onPostExecute(String result) {
                            if (isAdded() && !isDetached() && !isRemoving() && getContext() != null) {
                                textView.setText(result);
                            }
                        }
                    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, linkedMobileText);
                }
                break;
            case UserService.LinkingState_PENDING:
                pending = true;

                final String newMobileNumber = this.userService.getLinkedMobile(true);
                if (newMobileNumber != null) {
                    new AsyncTask<TextView, Void, String>() {
                        private TextView textView;

                        @Override
                        protected String doInBackground(TextView... params) {
                            if (isAdded() && getContext() != null && params != null) {
                                textView = params[0];
                                return (localeService != null ? localeService.getHRPhoneNumber(newMobileNumber) : "") + " (" + getContext().getString(R.string.pending) + ")";
                            }
                            return null;
                        }

                        @Override
                        protected void onPostExecute(String result) {
                            if (isAdded() && !isDetached() && !isRemoving() && getContext() != null && textView != null) {
                                textView.setText(result);
                            }
                        }
                    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, linkedMobileText);
                }
                break;
            default:

        }
        linkedMobileText.invalidate();

        if (!ConfigUtils.isOnPremBuild()) {
            //revocation key
            TextView revocationKey = fragmentView.findViewById(R.id.revocation_key_sum);
            new AsyncTask<TextView, Void, String>() {
                private TextView textView;

                @Override
                protected String doInBackground(TextView... params) {
                    if (isAdded()) {
                        textView = params[0];
                        Date revocationKeyLastSet = userService.getLastRevocationKeySet();
                        if (!isDetached() && !isRemoving() && getContext() != null) {
                            if (revocationKeyLastSet != null) {
                                return getContext().getString(R.string.revocation_key_set_at, LocaleUtil.formatTimeStampString(getContext(), revocationKeyLastSet.getTime(), true));
                            } else {
                                return getContext().getString(R.string.revocation_key_not_set);
                            }
                        }
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(String result) {
                    if (isAdded() && !isDetached() && !isRemoving() && getContext() != null && textView != null) {
                        textView.setText(result);
                    }
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, revocationKey);
        }
        return pending;
    }

    private void configureEditWithButton(RelativeLayout l, MaterialButton button, boolean disable) {
        if (disable) {
            button.setVisibility(View.INVISIBLE);
        } else {
            button.setOnClickListener(this);
        }
    }

    private void deleteIdentity() {
        if (!this.requiredInstances()) {
            return;
        }

        new DeleteIdentityAsyncTask(getFragmentManager(), new Runnable() {
            @Override
            public void run() {
                ConfigUtils.clearAppData(ThreemaApplication.getAppContext());
                System.exit(0);
            }
        }).execute();
    }

    private void setRevocationPassword() {
        DialogFragment dialogFragment = PasswordEntryDialog.newInstance(
            R.string.revocation_key_title,
            R.string.revocation_explain,
            R.string.password_hint,
            R.string.ok,
            R.string.cancel,
            8,
            MAX_REVOCATION_PASSWORD_LENGTH,
            R.string.backup_password_again_summary,
            0, 0, PasswordEntryDialog.ForgotHintType.NONE);
        dialogFragment.setTargetFragment(this, 0);
        dialogFragment.show(getFragmentManager(), DIALOG_TAG_SET_REVOCATION_KEY);
    }

    @Override
    public void onClick(View v) {
        int neutral;
        final int id = v.getId();

        if (id == R.id.change_email) {
            logger.info("Change email clicked");
            neutral = 0;
            if (this.userService.getEmailLinkingState() != UserService.LinkingState_NONE) {
                neutral = R.string.unlink;
            }

            TextEntryDialog textEntryDialog = TextEntryDialog.newInstance(
                R.string.wizard2_email_linking,
                R.string.wizard2_email_hint,
                R.string.ok,
                neutral,
                R.string.cancel,
                userService.getLinkedEmail(),
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, TextEntryDialog.INPUT_FILTER_TYPE_NONE);
            textEntryDialog.setTargetFragment(this, 0);
            textEntryDialog.show(getFragmentManager(), DIALOG_TAG_LINKED_EMAIL);
        } else if (id == R.id.change_mobile) {
            logger.info("Change mobile clicked");
            String presetNumber = serviceManager.getLocaleService().getHRPhoneNumber(userService.getLinkedMobile());
            neutral = 0;
            if (this.userService.getMobileLinkingState() != UserService.LinkingState_NONE) {
                neutral = R.string.unlink;
            } else {
                presetNumber = localeService.getCountryCodePhonePrefix();
                if (!TestUtil.isEmptyOrNull(presetNumber)) {
                    presetNumber += " ";
                }
            }
            TextEntryDialog textEntryDialog1 = TextEntryDialog.newInstance(
                R.string.wizard2_phone_linking,
                R.string.wizard2_phone_hint,
                R.string.ok,
                neutral,
                R.string.cancel,
                presetNumber,
                InputType.TYPE_CLASS_PHONE,
                TextEntryDialog.INPUT_FILTER_TYPE_PHONE);
            textEntryDialog1.setTargetFragment(this, 0);
            textEntryDialog1.show(getFragmentManager(), DIALOG_TAG_LINKED_MOBILE);
        } else if (id == R.id.revocation_key) {
            if (!preferenceService.getLockMechanism().equals(PreferenceService.LockingMech_NONE)) {
                HiddenChatUtil.launchLockCheckDialog(null, this, preferenceService, LOCK_CHECK_REVOCATION);
            } else {
                logger.info("Set revocation key clicked");
                setRevocationPassword();
            }
        } else if (id == R.id.delete_id) {
            // ask for pin before entering
            if (!preferenceService.getLockMechanism().equals(PreferenceService.LockingMech_NONE)) {
                HiddenChatUtil.launchLockCheckDialog(null, this, preferenceService, LOCK_CHECK_DELETE_ID);
            } else {
                logger.info("Delete ID clicked");
                confirmIdDelete();
            }
        } else if (id == R.id.export_id) {
            // ask for pin before entering
            if (!preferenceService.getLockMechanism().equals(PreferenceService.LockingMech_NONE)) {
                HiddenChatUtil.launchLockCheckDialog(null, this, preferenceService, LOCK_CHECK_EXPORT_ID);
            } else {
                logger.info("Export ID clicked");
                startActivity(new Intent(getContext(), ExportIDActivity.class));
            }
        } else if (id == R.id.picrelease_config) {
            launchProfilePictureRecipientsSelector(v);
        } else if (id == R.id.profile_edit) {
            logger.info("Edit nickname clicked, showing dialog");
            TextEntryDialog nicknameEditDialog = TextEntryDialog.newInstance(R.string.set_nickname_title,
                R.string.wizard3_nickname_hint,
                R.string.ok, 0,
                R.string.cancel,
                userService.getPublicNickname(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                0,
                ProtocolDefines.PUSH_FROM_LEN);
            nicknameEditDialog.setTargetFragment(this, 0);
            nicknameEditDialog.show(getFragmentManager(), DIALOG_TAG_EDIT_NICKNAME);
        } else if (id == R.id.my_id_qr) {
            logger.info("My ID clicked, showing QR code");
            new QRCodePopup(getContext(), getActivity().getWindow().getDecorView(), getActivity()).show(v, null, QRCodeServiceImpl.QR_TYPE_ID);
        } else if (id == R.id.my_id_share) {
            logger.info("Share clicked");
            ShareUtil.shareContact(getContext(), null);
        }
    }

    private void launchProfilePictureRecipientsSelector(View v) {
        getActivity().startActivityForResult(new Intent(getContext(), ProfilePicRecipientsActivity.class), 55);
    }

    private void confirmIdDelete() {
        DialogFragment dialogFragment = GenericAlertDialog.newInstance(
            R.string.delete_id_title,
            R.string.delete_id_message,
            R.string.delete_id_title,
            R.string.cancel);
        ((GenericAlertDialog) dialogFragment).setTargetFragment(this);
        dialogFragment.show(getFragmentManager(), DIALOG_TAG_DELETE_ID);
    }

    @SuppressLint("StaticFieldLeak")
    private void launchMobileVerification(final String normalizedPhoneNumber) {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                try {
                    userService.linkWithMobileNumber(normalizedPhoneNumber, TriggerSource.LOCAL);
                } catch (LinkMobileNoException e) {
                    return e.getMessage();
                } catch (Exception e) {
                    logger.error("Exception", e);
                    return e.getMessage();
                }
                return null;
            }

            @Override
            protected void onPostExecute(String result) {
                if (isAdded() && !isDetached() && !isRemoving() && getContext() != null) {
                    if (TestUtil.isEmptyOrNull(result)) {
                        Toast.makeText(getContext(), R.string.verification_started, Toast.LENGTH_LONG).show();
                    } else {
                        FragmentManager fragmentManager = getFragmentManager();
                        if (fragmentManager != null) {
                            updatePendingStateTexts(getView());
                            SimpleStringAlertDialog.newInstance(R.string.verify_title, result).show(fragmentManager, "ve");
                        }
                    }
                }
            }
        }.execute();
    }

    @UiThread
    private void reloadNickname() {
        this.nicknameTextView.setText(!TestUtil.isEmptyOrNull(userService.getPublicNickname()) ? userService.getPublicNickname() : userService.getIdentity());
    }

    @SuppressLint("StaticFieldLeak")
    private void setRevocationKey(String text) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected void onPreExecute() {
                GenericProgressDialog.newInstance(R.string.revocation_key_title, R.string.please_wait).show(getFragmentManager(), DIALOG_TAG_REVOKING);
            }

            @Override
            protected Boolean doInBackground(Void... voids) {
                try {
                    return userService.setRevocationKey(text);
                } catch (Exception x) {
                    logger.error("Exception", x);
                }
                return false;
            }

            @Override
            protected void onPostExecute(Boolean success) {
                updatePendingStateTexts(getView());
                DialogUtil.dismissDialog(getFragmentManager(), DIALOG_TAG_REVOKING, true);
                if (!success) {
                    Toast.makeText(getContext(), getString(R.string.error) + ": " + getString(R.string.revocation_key_not_set), Toast.LENGTH_LONG).show();
                }
            }
        }.execute();
    }

    /*
     * DialogFragment callbacks
     */

    @Override
    public void onYes(String tag, Object data) {
        switch (tag) {
            case DIALOG_TAG_DELETE_ID:
                logger.info("Showing second ID deletion warning");
                GenericAlertDialog dialogFragment = GenericAlertDialog.newInstance(
                    R.string.delete_id_title,
                    R.string.delete_id_message2,
                    R.string.delete_id_title,
                    R.string.cancel);
                dialogFragment.setTargetFragment(this);
                dialogFragment.show(getFragmentManager(), DIALOG_TAG_REALLY_DELETE);
                break;
            case DIALOG_TAG_REALLY_DELETE:
                logger.info("Delete ID confirmed");
                deleteIdentity();
                break;
            case DIALOG_TAG_LINKED_MOBILE_CONFIRM:
                logger.info("Verify mobile confirmed");
                launchMobileVerification((String) data);
                break;
            default:
                break;
        }
    }

    @Override
    public void onYes(@NonNull String tag, @NonNull String text) {
        switch (tag) {
            case DIALOG_TAG_LINKED_MOBILE:
                logger.info("Link mobile clicked, showing confirm dialog");
                final String normalizedPhoneNumber = localeService.getNormalizedPhoneNumber(text);
                GenericAlertDialog alertDialog = GenericAlertDialog.newInstance(R.string.wizard2_phone_number_confirm_title, String.format(getString(R.string.wizard2_phone_number_confirm), normalizedPhoneNumber), R.string.ok, R.string.cancel);
                alertDialog.setData(normalizedPhoneNumber);
                alertDialog.setTargetFragment(this);
                alertDialog.show(getFragmentManager(), DIALOG_TAG_LINKED_MOBILE_CONFIRM);
                break;
            case DIALOG_TAG_LINKED_EMAIL:
                logger.info("Link email clicked");
                new LinkWithEmailAsyncTask(getContext(), getFragmentManager(), text, () -> updatePendingStateTexts(getView())).execute();
                break;
            case DIALOG_TAG_EDIT_NICKNAME:
                logger.info("New nickname set");
                // Update public nickname
                String newNickname = text.trim();
                if (!newNickname.equals(userService.getPublicNickname())) {
                    userService.setPublicNickname(newNickname, TriggerSource.LOCAL);
                }
                reloadNickname();
                break;
            default:
                break;
        }
    }

    @Override
    public void onYes(String tag, final String text, boolean isChecked, Object data) {
        if (tag.equals(DIALOG_TAG_SET_REVOCATION_KEY)) {
            logger.info("Revocation key set");
            setRevocationKey(text);
        }
    }

    @Override
    public void onNo(String tag) {
    }

    @Override
    public void onNeutral(String tag) {
        switch (tag) {
            case DIALOG_TAG_LINKED_MOBILE:
                logger.info("Unlinking mobile");
                new Thread(() -> {
                    try {
                        userService.unlinkMobileNumber(TriggerSource.LOCAL);
                    } catch (Exception e) {
                        LogUtil.exception(e, getActivity());
                    } finally {
                        RuntimeUtil.runOnUiThread(() -> updatePendingStateTexts(getView()));
                    }
                }).start();
                break;
            case DIALOG_TAG_LINKED_EMAIL:
                logger.info("Unlinking email");
                new LinkWithEmailAsyncTask(getContext(), getFragmentManager(), "", () -> updatePendingStateTexts(getView())).execute();
                break;
            default:
                break;
        }
    }

    final protected boolean requiredInstances() {
        if (!this.checkInstances()) {
            this.instantiate();
        }
        return this.checkInstances();
    }

    protected boolean checkInstances() {
        return TestUtil.required(
            this.serviceManager,
            this.fileService,
            this.userService,
            this.preferenceService,
            this.localeService);
    }

    protected void instantiate() {
        this.serviceManager = ThreemaApplication.getServiceManager();

        if (this.serviceManager != null) {
            try {
                this.contactService = this.serviceManager.getContactService();
                this.userService = this.serviceManager.getUserService();
                this.fileService = this.serviceManager.getFileService();
                this.preferenceService = this.serviceManager.getPreferenceService();
                this.localeService = this.serviceManager.getLocaleService();
                this.taskCreator = this.serviceManager.getTaskCreator();
                this.profilePicRecipientsService = this.serviceManager.getProfilePicRecipientsService();
            } catch (MasterKeyLockedException e) {
                logger.debug("Master Key locked!");
            }
        }
    }

    public void onLogoClicked() {
        if (getView() != null) {
            NestedScrollView scrollView = getView().findViewById(R.id.fragment_id_container);
            if (scrollView != null) {
                logger.info("Logo clicked, scrolling to top");
                scrollView.scrollTo(0, 0);
            }
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && hidden != this.hidden) {
            updatePendingState(getView(), false);
        }
        this.hidden = hidden;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        logger.info("saveInstance");
        super.onSaveInstanceState(outState);
    }

    /* callbacks from AvatarEditView */

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (this.avatarView != null) {
            this.avatarView.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        switch (requestCode) {
            case ThreemaActivity.ACTIVITY_ID_VERIFY_MOBILE:
                if (resultCode != Activity.RESULT_OK) {
                    // todo: make sure its status is unlinked if linking failed
                }
                updatePendingState(getView(), false);
                break;
            case LOCK_CHECK_DELETE_ID:
                if (resultCode == Activity.RESULT_OK) {
                    confirmIdDelete();
                }
                break;
            case LOCK_CHECK_EXPORT_ID:
                if (resultCode == Activity.RESULT_OK) {
                    startActivity(new Intent(getContext(), ExportIDActivity.class));
                }
                break;
            case LOCK_CHECK_REVOCATION:
                if (resultCode == Activity.RESULT_OK) {
                    setRevocationPassword();
                }
                break;
            default:
                if (this.avatarView != null) {
                    this.avatarView.onActivityResult(requestCode, resultCode, intent);
                }
                break;
        }
    }

}
