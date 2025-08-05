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

package ch.threema.app.preference.service;

import android.content.Context;
import android.net.Uri;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.home.HomeActivity;
import ch.threema.app.services.ContactService;
import ch.threema.app.threemasafe.ThreemaSafeServerInfo;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.domain.protocol.api.work.WorkDirectoryCategory;
import ch.threema.domain.protocol.api.work.WorkOrganization;

public interface PreferenceService {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ImageScale_DEFAULT, ImageScale_SMALL, ImageScale_MEDIUM, ImageScale_LARGE, ImageScale_XLARGE, ImageScale_ORIGINAL, ImageScale_SEND_AS_FILE})
    @interface ImageScale {
    }

    int ImageScale_DEFAULT = -1;
    int ImageScale_SMALL = 0;
    int ImageScale_MEDIUM = 1;
    int ImageScale_LARGE = 2;
    int ImageScale_XLARGE = 3;
    int ImageScale_ORIGINAL = 4;
    int ImageScale_SEND_AS_FILE = 5;

    @IntDef({VideoSize_DEFAULT, VideoSize_SMALL, VideoSize_MEDIUM, VideoSize_ORIGINAL, VideoSize_SEND_AS_FILE})
    @interface VideoSize {
    }

    int VideoSize_DEFAULT = -1;
    int VideoSize_SMALL = 0;
    int VideoSize_MEDIUM = 1;
    int VideoSize_ORIGINAL = 2;
    int VideoSize_SEND_AS_FILE = 3;

    @IntDef({StarredMessagesSortOrder_DATE_DESCENDING, StarredMessagesSortOrder_DATE_ASCENDING})
    @interface StarredMessagesSortOrder {
    }

    int StarredMessagesSortOrder_DATE_DESCENDING = 0;
    int StarredMessagesSortOrder_DATE_ASCENDING = 1;

    @IntDef({EmojiStyle_DEFAULT, EmojiStyle_ANDROID})
    @interface EmojiStyle {
    }
    int EmojiStyle_DEFAULT = 0;
    int EmojiStyle_ANDROID = 1;

    String LockingMech_NONE = "none";
    String LockingMech_PIN = "pin";
    String LockingMech_SYSTEM = "system";
    String LockingMech_BIOMETRIC = "biometric";

    int PROFILEPIC_RELEASE_NOBODY = 0;
    int PROFILEPIC_RELEASE_EVERYONE = 1;
    int PROFILEPIC_RELEASE_ALLOW_LIST = 2;

    int PRIVACY_POLICY_ACCEPT_NONE = 0;
    int PRIVACY_POLICY_ACCEPT_EXCPLICIT = 1;
    int PRIVACY_POLICY_ACCEPT_IMPLICIT = 2;
    int PRIVACY_POLICY_ACCEPT_UPDATE = 3;

    String VIDEO_CODEC_HW = "hw";
    String VIDEO_CODEC_NO_VP8 = "no-vp8";
    String VIDEO_CODEC_NO_H264HIP = "no-h264hip";
    String VIDEO_CODEC_SW = "sw";

    boolean isSyncContacts();

    @NonNull
    ContactSyncPolicySetting getContactSyncPolicySetting();

    boolean isBlockUnknown();

    @NonNull
    UnknownContactPolicySetting getUnknownContactPolicySetting();

    boolean areReadReceiptsEnabled();

    @NonNull
    ReadReceiptPolicySetting getReadReceiptPolicySetting();

    boolean isTypingIndicatorEnabled();

    @NonNull
    TypingIndicatorPolicySetting getTypingIndicatorPolicySetting();

    boolean isVoipEnabled();

    @NonNull
    O2oCallPolicySetting getO2oCallPolicySetting();

    boolean getForceTURN();

    @NonNull
    O2oCallConnectionPolicySetting getO2oCallConnectionPolicySetting();

    boolean areVideoCallsEnabled();

    @NonNull
    O2oCallVideoPolicySetting getO2oCallVideoPolicySetting();

    boolean areGroupCallsEnabled();

    @NonNull
    GroupCallPolicySetting getGroupCallPolicySetting();

    boolean areScreenshotsDisabled();

    @NonNull
    ScreenshotPolicySetting getScreenshotPolicySetting();

    boolean isIncognitoKeyboardRequested();

    @NonNull
    KeyboardDataCollectionPolicySetting getKeyboardDataCollectionPolicySetting();

    boolean isCustomWallpaperEnabled();

    void setCustomWallpaperEnabled(boolean enabled);

    boolean isEnterToSend();

    boolean isInAppSounds();

    boolean isInAppVibrate();

    @ImageScale
    int getImageScale();

    int getVideoSize();

    String getSerialNumber();

    void setSerialNumber(String serialNumber);

    @Nullable
    String getLicenseUsername();

    void setLicenseUsername(String username);

    String getLicensePassword();

    void setLicensePassword(String password);

    @Nullable
    String getOnPremServer();

    void setOnPremServer(String server);

    LinkedList<Integer> getRecentEmojis();

    LinkedList<String> getRecentEmojis2();

    void setRecentEmojis(LinkedList<Integer> list);

    void setRecentEmojis2(LinkedList<String> list);

    int getEmojiSearchIndexVersion();

    void setEmojiSearchIndexVersion(int version);

    /**
     * Whether to use Threema Push instead of another push service.
     */
    boolean useThreemaPush();

    /**
     * Whether to use Threema Push instead of another push service.
     */
    void setUseThreemaPush(boolean enabled);

    boolean isSaveMedia();

    boolean isPinSet();

    boolean setPin(String newCode);

    boolean isPinCodeCorrect(String pinCode);

    long getTransmittedFeatureMask();

    void setTransmittedFeatureMask(long featureMask);

    long getLastFeatureMaskTransmission();

    void setLastFeatureMaskTransmission(long timestamp);

    @NonNull
    String[] getList(String listName);

    @NonNull
    String[] getList(String listName, boolean encrypted);

    void setList(String listName, String[] elements);

    /* save list to preferences without triggering a listener */
    void setListQuietly(String listName, String[] elements);

    void setListQuietly(@NonNull String listName, @NonNull String[] elements, boolean encrypted);

    HashMap<Integer, String> getHashMap(String listName, boolean encrypted);

    void setHashMap(String listName, HashMap<Integer, String> hashMap);

    HashMap<String, String> getStringHashMap(String listName, boolean encrypted);

    void setStringHashMap(String listName, HashMap<String, String> hashMap);

    /**
     * value in seconds!
     */
    int getPinLockGraceTime();

    int getIDBackupCount();

    void incrementIDBackupCount();

    void resetIDBackupCount();

    Date getLastIDBackupReminderDate();

    void setLastIDBackupReminderDate(Date lastIDBackupReminderDate);

    String getContactListSorting();

    boolean isContactListSortingFirstName();

    String getContactFormat();

    boolean isContactFormatFirstNameLastName();

    boolean isDefaultContactPictureColored();

    int getFontStyle();

    void clear();

    List<String[]> write();

    boolean read(List<String[]> values);

    boolean showInactiveContacts();

    boolean getLastOnlineStatus();

    void setLastOnlineStatus(boolean online);

    boolean isLatestVersion(Context context);

    int getLatestVersion();

    void setLatestVersion(Context context);

    /**
     * Check whether the app has been updated since the last check. Note that this returns true for
     * every app update. For the what's new dialog, we use {@link #getLatestVersion()}.
     * Note: This method can only be used once as it returns true only once per update. Currently,
     * it is used in {@link HomeActivity} and must not be used anywhere
     * else.
     */
    boolean checkForAppUpdate(@NonNull Context context);

    boolean getFileSendInfoShown();

    void setFileSendInfoShown(boolean shown);

    int getAppThemeValue();

    int getEmojiStyle();

    void setLockoutDeadline(long deadline);

    void setLockoutTimeout(long timeout);

    long getLockoutDeadline();

    long getLockoutTimeout();

    void setLockoutAttempts(int numWrongConfirmAttempts);

    int getLockoutAttempts();

    boolean isAnimationAutoplay();

    boolean isUseProximitySensor();

    void setAppLogoExpiresAt(Date expiresAt, @ConfigUtils.AppThemeSetting String theme);

    Date getAppLogoExpiresAt(@ConfigUtils.AppThemeSetting String theme);

    boolean isPrivateChatsHidden();

    void setPrivateChatsHidden(boolean hidden);

    String getLockMechanism();

    /**
     * Check if app UI lock is enabled
     *
     * @return true if UI lock is enabled, false otherwise
     */
    boolean isAppLockEnabled();

    void setAppLockEnabled(boolean enabled);

    void setSaveToGallery(Boolean booleanPreset);

    void setLockMechanism(String lockingMech);

    boolean isShowImageAttachPreviewsEnabled();

    void setImageAttachPreviewsEnabled(boolean enable);

    boolean isDirectShare();

    void setMessageDrafts(HashMap<String, String> messageDrafts);

    HashMap<String, String> getMessageDrafts();

    void setQuoteDrafts(HashMap<String, String> quoteDrafts);

    HashMap<String, String> getQuoteDrafts();

    void setAppLogo(@NonNull String url, @ConfigUtils.AppThemeSetting String theme);

    void clearAppLogo(@ConfigUtils.AppThemeSetting String theme);

    void clearAppLogos();

    @Nullable
    String getAppLogo(@ConfigUtils.AppThemeSetting String theme);

    void setCustomSupportUrl(String supportUrl);

    String getCustomSupportUrl();

    HashMap<String, String> getDiverseEmojiPrefs();

    void setDiverseEmojiPrefs(HashMap<String, String> diverseEmojis);

    boolean isWebClientEnabled();

    void setWebClientEnabled(boolean enabled);

    void setPushToken(String fcmToken);

    String getPushToken();

    int getProfilePicRelease();

    void setProfilePicRelease(int value);

    long getProfilePicUploadDate();

    void setProfilePicUploadData(@Nullable ContactService.ProfilePictureUploadData data);

    /**
     * Get the stored profile picture upload data. Note that the returned data does not include the
     * bitmap array of the profile picture.
     *
     * @return the stored profile picture upload data or null if there is no stored data or an error
     * occurred while reading the data
     */
    @Nullable
    ContactService.ProfilePictureUploadData getProfilePicUploadData();

    boolean getProfilePicReceive();

    @NonNull
    String getAECMode();

    @NonNull
    String getVideoCodec();

    /**
     * If true, then mobile POTS calls should be rejected while a Threema call is active.
     */
    boolean isRejectMobileCalls();

    /**
     * Set whether or not a mobile POTS calls should be rejected while a Threema call is active.
     * <p>
     * Note that this requires the "manage phone call" permission.
     */
    void setRejectMobileCalls(boolean value);

    /**
     * This preference corresponds to the troubleshooting setting "IPv6 for messages"
     *
     * @return true if ipv6 is enabled for messages, false otherwise
     */
    boolean isIpv6Preferred();

    boolean allowWebrtcIpv6();

    Set<String> getMobileAutoDownload();

    Set<String> getWifiAutoDownload();

    void setRatingReference(String reference);

    @Nullable
    String getRatingReference();

    String getRatingReviewText();

    void setRatingReviewText(String review);

    void setPrivacyPolicyAccepted(Date date, int source);

    Date getPrivacyPolicyAccepted();

    void clearPrivacyPolicyAccepted();

    boolean getIsGroupCallsTooltipShown();

    void setGroupCallsTooltipShown(boolean shown);

    boolean getIsWorkHintTooltipShown();

    void setIsWorkHintTooltipShown(boolean shown);

    boolean getIsFaceBlurTooltipShown();

    void setFaceBlurTooltipShown(boolean shown);

    void setThreemaSafeEnabled(boolean value);

    boolean getThreemaSafeEnabled();

    void setThreemaSafeMasterKey(byte[] masterKey);

    byte[] getThreemaSafeMasterKey();

    void setThreemaSafeServerInfo(@Nullable ThreemaSafeServerInfo serverInfo);

    @NonNull
    ThreemaSafeServerInfo getThreemaSafeServerInfo();

    void setThreemaSafeUploadDate(Date date);

    @Nullable
    Date getThreemaSafeUploadDate();

    boolean getShowUnreadBadge();

    void setThreemaSafeErrorCode(int code);

    int getThreemaSafeErrorCode();

    /**
     * Set the earliest date where the threema safe backup failed. Only set this if there are
     * changes for the backup available. Don't update the date when there is already a date set as
     * this is the first occurrence of a failed backup. Override this date with null, when a safe
     * backup has been created successfully.
     *
     * @param date the date when the safe backup first failed
     */
    void setThreemaSafeErrorDate(@Nullable Date date);

    /**
     * Get the first date where the safe backup failed. If this is null, then the last safe backup
     * was successful.
     *
     * @return the date of the first failed safe backup
     */
    @Nullable
    Date getThreemaSafeErrorDate();

    void setThreemaSafeServerMaxUploadSize(long maxBackupBytes);

    long getThreemaSafeServerMaxUploadSize();

    void setThreemaSafeServerRetention(int days);

    int getThreemaSafeServerRetention();

    void setThreemaSafeBackupSize(int size);

    int getThreemaSafeBackupSize();

    void setThreemaSafeHashString(String hashString);

    String getThreemaSafeHashString();

    void setThreemaSafeBackupDate(Date date);

    Date getThreemaSafeBackupDate();

    void setWorkSyncCheckInterval(int checkInterval);

    int getWorkSyncCheckInterval();

    /**
     * Store the interval for the identity state sync in seconds.
     *
     * @param syncIntervalS The sync interval in seconds
     */
    void setIdentityStateSyncInterval(int syncIntervalS);

    /**
     * @return The identity state sync interval in seconds
     */
    int getIdentityStateSyncIntervalS();

    boolean getIsExportIdTooltipShown();

    void setThreemaSafeMDMConfig(String mdmConfigHash);

    String getThreemaSafeMDMConfig();

    void setWorkDirectoryEnabled(boolean enabled);

    boolean getWorkDirectoryEnabled();

    void setWorkDirectoryCategories(List<WorkDirectoryCategory> categories);

    List<WorkDirectoryCategory> getWorkDirectoryCategories();

    void setWorkOrganization(WorkOrganization organization);

    WorkOrganization getWorkOrganization();

    void setLicensedStatus(boolean licensed);

    boolean getLicensedStatus();

    void setShowDeveloperMenu(boolean show);

    boolean showDeveloperMenu();

    Uri getDataBackupUri();

    void setDataBackupUri(Uri newUri);

    Date getLastDataBackupDate();

    void setLastDataBackupDate(Date date);

    String getMatchToken();

    void setMatchToken(String matchToken);

    boolean isAfterWorkDNDEnabled();

    void setAfterWorkDNDEnabled(boolean enabled);

    void setCameraFlashMode(int flashMode);

    int getCameraFlashMode();

    void setPipPosition(int pipPosition);

    int getPipPosition();

    @Nullable
    String getVideoCallsProfile();

    void setBallotOverviewHidden(boolean hidden);

    boolean getBallotOverviewHidden();

    void setGroupRequestsOverviewHidden(boolean hidden);

    boolean getGroupRequestsOverviewHidden();

    int getVideoCallToggleTooltipCount();

    void incremenetVideoCallToggleTooltipCount();

    boolean getCameraPermissionRequestShown();

    void setCameraPermissionRequestShown(boolean shown);

    void setVoiceRecorderBluetoothDisabled(boolean isEnabled);

    boolean getVoiceRecorderBluetoothDisabled();

    void setAudioPlaybackSpeed(float newSpeed);

    float getAudioPlaybackSpeed();

    int getMultipleRecipientsTooltipCount();

    void incrementMultipleRecipientsTooltipCount();

    boolean isGroupCallSendInitEnabled();

    boolean skipGroupCallCreateDelay();

    long getBackupWarningDismissedTime();

    void setBackupWarningDismissedTime(long time);

    @StarredMessagesSortOrder
    int getStarredMessagesSortOrder();

    void setStarredMessagesSortOrder(@StarredMessagesSortOrder int order);

    void setAutoDeleteDays(int i);

    int getAutoDeleteDays();

    // TODO(ANDR-2816): Remove
    void removeLastNotificationRationaleShown();

    void getMediaGalleryContentTypes(boolean[] contentTypes);

    void setMediaGalleryContentTypes(boolean[] contentTypes);

    int getEmailSyncHashCode();

    int getPhoneNumberSyncHashCode();

    void setEmailSyncHashCode(int emailsHash);

    void setPhoneNumberSyncHashCode(int phoneNumbersHash);

    void setTimeOfLastContactSync(long timeMs);

    long getTimeOfLastContactSync();

    boolean showConversationLastUpdate();

    Date getLastShortcutUpdateDate();

    void setLastShortcutUpdateDate(Date date);

    /**
     * Set the last timestamp when the notification permission has been requested.
     */
    void setLastNotificationPermissionRequestTimestamp(long timestamp);

    /**
     * Get the last timestamp when the notification permission has been requested. If the
     * notification permission has not yet been requested, 0 is returned.
     */
    long getLastNotificationPermissionRequestTimestamp();

    /**
     * @return The saved {@code Instant} or {@code null} if not present.
     */
    @Nullable
    Instant getLastMultiDeviceGroupCheckTimestamp();

    void setLastMultiDeviceGroupCheckTimestamp(final @NonNull Instant timestamp);

    /**
     * Get the synchronized boolean setting based on the provided key. If there is no synchronized
     * setting with this key, null is returned.
     */
    @Nullable
    SynchronizedBooleanSetting getSynchronizedBooleanSettingByKey(@NonNull String key);

    /**
     * Reload the synchronized boolean settings from the preference store.
     * TODO(PRD-152): Note that this method may be removed once the logic regarding mdm handling is
     *  refactored.
     */
    void reloadSynchronizedBooleanSettings();

    // TODO(ANDR-3887): remove
    boolean shouldShowUnsupportedAndroidVersionWarning();

    // TODO(ANDR-3887): remove
    void setUnsupportedAndroidVersionDismissedNow();
}
