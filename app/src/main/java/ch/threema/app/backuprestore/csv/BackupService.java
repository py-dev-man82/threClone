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

package ch.threema.app.backuprestore.csv;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
import static ch.threema.app.utils.IntentDataUtil.PENDING_INTENT_FLAG_IMMUTABLE;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.documentfile.provider.DocumentFile;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.json.JSONObject;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import ch.threema.app.BuildConfig;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.activities.DummyActivity;
import ch.threema.app.home.HomeActivity;
import ch.threema.app.backuprestore.BackupRestoreDataConfig;
import ch.threema.app.backuprestore.RandomUtil;
import ch.threema.app.collections.Functional;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.notifications.NotificationChannels;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.DistributionListService;
import ch.threema.app.services.FileService;
import ch.threema.app.services.GroupService;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.services.UserService;
import ch.threema.app.services.ballot.BallotService;
import ch.threema.app.utils.BackupUtils;
import ch.threema.app.utils.CSVWriter;
import ch.threema.app.utils.Counter;
import ch.threema.app.utils.FileHandlingZipOutputStream;
import ch.threema.app.utils.MessageUtil;
import ch.threema.app.utils.MimeUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.StringConversionUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.base.ThreemaException;
import ch.threema.base.crypto.HashedNonce;
import ch.threema.base.crypto.NonceFactory;
import ch.threema.base.crypto.NonceScope;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.base.utils.Utils;
import ch.threema.data.repositories.EmojiReactionsRepository;
import ch.threema.domain.identitybackup.IdentityBackupGenerator;
import ch.threema.storage.DatabaseService;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.DistributionListMessageModel;
import ch.threema.storage.models.DistributionListModel;
import ch.threema.storage.models.GroupMessageModel;
import ch.threema.storage.models.GroupModel;
import ch.threema.storage.models.MessageModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.ballot.BallotChoiceModel;
import ch.threema.storage.models.ballot.BallotModel;
import ch.threema.storage.models.ballot.BallotVoteModel;
import ch.threema.storage.models.ballot.GroupBallotModel;
import ch.threema.storage.models.ballot.IdentityBallotModel;
import ch.threema.storage.models.ballot.LinkBallotModel;
import ch.threema.storage.models.data.media.AudioDataModel;
import ch.threema.storage.models.data.media.FileDataModel;
import ch.threema.storage.models.data.media.VideoDataModel;

public class BackupService extends Service {
    private static final Logger logger = LoggingUtil.getThreemaLogger("BackupService");

    public static final String BACKUP_PROGRESS_INTENT = "backup_progress_intent";
    public static final String BACKUP_PROGRESS = "backup_progress";
    public static final String BACKUP_PROGRESS_STEPS = "backup_progress_steps";
    public static final String BACKUP_PROGRESS_MESSAGE = "backup_progress_message";
    public static final String BACKUP_PROGRESS_ERROR_MESSAGE = "backup_progress_error_message";

    private static final int MEDIA_STEP_FACTOR_VIDEOS_AND_FILES = 12;
    private static final int MEDIA_STEP_FACTOR_THUMBNAILS = 3;
    private static final int NONCES_PER_STEP = 50;
    private static final int NONCES_CHUNK_SIZE = 2500;
    private static final int REACTIONS_PER_STEP = 50;
    private static final int REACTION_STEP_THRESHOLD = 500;

    private static final String EXTRA_ID_CANCEL = "cnc";
    public static final String EXTRA_BACKUP_RESTORE_DATA_CONFIG = "ebrdc";

    private static final int BACKUP_NOTIFICATION_ID = 991772;
    public static final int BACKUP_COMPLETION_NOTIFICATION_ID = 991773;
    private static final long FILE_SETTLE_DELAY = 5000;

    private static final String INCOMPLETE_BACKUP_FILENAME_PREFIX = "INCOMPLETE-";

    private static final int FG_SERVICE_TYPE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;

    private long currentProgressStep = 0;
    private long processSteps = 0;

    private static boolean backupSuccess = false;
    private static boolean isCanceled = false;
    private static boolean isRunning = false;

    private ServiceManager serviceManager;
    private ContactService contactService;
    private FileService fileService;
    private UserService userService;
    private GroupService groupService;
    private BallotService ballotService;
    private DistributionListService distributionListService;
    private DatabaseService databaseService;
    private PreferenceService preferenceService;
    private PowerManager.WakeLock wakeLock;
    private NotificationManagerCompat notificationManagerCompat;
    private NonceFactory nonceFactory;
    private EmojiReactionsRepository reactionsRepository;

    private NotificationCompat.Builder notificationBuilder;

    private int latestPercentStep = -1;
    private long startTime = 0;

    private static DocumentFile backupFile = null;
    private BackupRestoreDataConfig config = null;
    private final HashMap<Integer, String> groupUidMap = new HashMap<>();
    private final Iterator<Integer> randomIterator = RandomUtil.getDistinctRandomIterator();

    public static boolean isRunning() {
        return isRunning;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            isCanceled = intent.getBooleanExtra(EXTRA_ID_CANCEL, false);

            if (!isCanceled) {
                config = (BackupRestoreDataConfig) intent.getSerializableExtra(EXTRA_BACKUP_RESTORE_DATA_CONFIG);

                if (config == null || userService.getIdentity() == null || userService.getIdentity().isEmpty()) {
                    safeStopSelf();
                    return START_NOT_STICKY;
                }

                logger.info("Starting backup (backupMedia={})", config.backupMedia());
                // acquire wake locks
                logger.debug("Acquiring wakelock");
                PowerManager powerManager = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                if (powerManager != null) {
                    String tag = BuildConfig.APPLICATION_ID + ":backup";
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M && Build.MANUFACTURER.equals("Huawei")) {
                        // Huawei will not kill your app if your Wakelock has a well known tag
                        // see https://dontkillmyapp.com/huawei
                        tag = "LocationManagerService";
                    }
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
                    if (wakeLock != null) {
                        wakeLock.acquire(DateUtils.DAY_IN_MILLIS);
                    }
                }
                logger.info("Acquiring wakelock success={}", wakeLock != null && wakeLock.isHeld());

                boolean success = false;
                Date now = new Date();
                DocumentFile zipFile = null;
                Uri backupUri = this.fileService.getBackupUri();

                if (backupUri == null) {
                    showBackupErrorNotification("Destination directory has not been selected yet");
                    safeStopSelf();
                    return START_NOT_STICKY;
                }

                String filename = "threema-backup_" + now.getTime() + "_1";

                if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(backupUri.getScheme())) {
                    zipFile = DocumentFile.fromFile(new File(backupUri.getPath(), INCOMPLETE_BACKUP_FILENAME_PREFIX + filename + ".zip"));
                    success = true;
                } else {
                    DocumentFile directory = DocumentFile.fromTreeUri(getApplicationContext(), backupUri);
                    if (directory != null && directory.exists()) {
                        try {
                            zipFile = directory.createFile(MimeUtil.MIME_TYPE_ZIP, INCOMPLETE_BACKUP_FILENAME_PREFIX + filename);
                            if (zipFile != null && zipFile.canWrite()) {
                                success = true;
                            }
                        } catch (Exception e) {
                            logger.error("Could not create backup file", e);
                        }
                    }
                }

                if (zipFile == null || !success) {
                    showBackupErrorNotification(getString(R.string.backup_data_no_permission));
                    safeStopSelf();
                    return START_NOT_STICKY;
                }

                backupFile = zipFile;

                showPersistentNotification();

                // close connection
                try {
                    serviceManager.stopConnection();
                } catch (InterruptedException e) {
                    showBackupErrorNotification("BackupService interrupted");
                    stopSelf();
                    return START_NOT_STICKY;
                }

                new AsyncTask<Void, Void, Boolean>() {
                    @Override
                    protected Boolean doInBackground(Void... params) {
                        return backup();
                    }

                    @Override
                    protected void onPostExecute(Boolean success) {
                        stopSelf();
                    }
                }.execute();

                return START_STICKY;
            } else {
                Toast.makeText(this, R.string.backup_data_cancelled, Toast.LENGTH_LONG).show();
            }
        } else {
            logger.debug("onStartCommand intent == null");

            onFinished(null);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        logger.info("onCreate");

        super.onCreate();

        isRunning = true;

        serviceManager = ThreemaApplication.getServiceManager();
        if (serviceManager == null) {
            safeStopSelf();
            return;
        }

        try {
            fileService = serviceManager.getFileService();
            databaseService = serviceManager.getDatabaseService();
            contactService = serviceManager.getContactService();
            groupService = serviceManager.getGroupService();
            distributionListService = serviceManager.getDistributionListService();
            userService = serviceManager.getUserService();
            ballotService = serviceManager.getBallotService();
            preferenceService = serviceManager.getPreferenceService();
            nonceFactory = serviceManager.getNonceFactory();
            reactionsRepository = serviceManager.getModelRepositories().getEmojiReaction();
        } catch (Exception e) {
            logger.error("Exception while setting up backup service", e);
            safeStopSelf();
            return;
        }

        notificationManagerCompat = NotificationManagerCompat.from(this);
    }

    @Override
    public void onDestroy() {
        logger.info("onDestroy success={} canceled={}", backupSuccess, isCanceled);

        if (isCanceled) {
            onFinished(getString(R.string.backup_data_cancelled));
        }
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        logger.info("onLowMemory");
        super.onLowMemory();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        logger.debug("onTaskRemoved");

        Intent intent = new Intent(this, DummyActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private int getStepFactorMedia() {
        return this.config.backupMedia()
            ? MEDIA_STEP_FACTOR_VIDEOS_AND_FILES
            : this.config.backupThumbnails()
                ? MEDIA_STEP_FACTOR_THUMBNAILS
                : 1;
    }

    private boolean backup() {
        String identity = userService.getIdentity();
        try (final OutputStream outputStream = getContentResolver().openOutputStream(backupFile.getUri());
             final FileHandlingZipOutputStream zipOutputStream = FileHandlingZipOutputStream.initializeZipOutputStream(outputStream, config.getPassword())) {
            logger.debug("Creating zip file {}", backupFile.getUri());

            // save settings
            RestoreSettings settings = new RestoreSettings(RestoreSettings.CURRENT_VERSION);
            ByteArrayOutputStream settingsBuffer = null;
            try {
                settingsBuffer = new ByteArrayOutputStream();
                CSVWriter settingsCsv = new CSVWriter(new OutputStreamWriter(settingsBuffer));
                settingsCsv.writeAll(settings.toList());
                settingsCsv.close();
            } finally {
                if (settingsBuffer != null) {
                    try {
                        settingsBuffer.close();
                    } catch (IOException e) { /**/ }
                }
            }

            logger.info("Count required steps for backup creation");

            long requiredStepsContactsAndMessages = this.databaseService.getContactModelFactory().count()
                + this.databaseService.getMessageModelFactory().count()
                + this.databaseService.getGroupModelFactory().count()
                + this.databaseService.getGroupMessageModelFactory().count();

            long requiredStepsDistributionLists = this.databaseService.getDistributionListModelFactory().count()
                + this.databaseService.getDistributionListMessageModelFactory().count();

            long requiredStepsBallots = this.databaseService.getBallotModelFactory().count();

            long requiredBackupSteps = (this.config.backupIdentity() ? 1 : 0)
                + (this.config.backupContactAndMessages() ?
                requiredStepsContactsAndMessages : 0)
                + (this.config.backupDistributionLists() ?
                requiredStepsDistributionLists : 0)
                + (this.config.backupBallots() ?
                requiredStepsBallots : 0);

            if (this.config.backupMedia() || this.config.backupThumbnails()) {
                try {
                    Set<MessageType> fileTypes = this.config.backupMedia() ? MessageUtil.getFileTypes() : MessageUtil.getLowProfileMessageModelTypes();
                    MessageType[] fileTypesArray = fileTypes.toArray(new MessageType[0]);

                    long requiredStepsMedia = this.databaseService.getMessageModelFactory().countByTypes(fileTypesArray);
                    requiredStepsMedia += this.databaseService.getGroupMessageModelFactory().countByTypes(fileTypesArray);

                    if (this.config.backupDistributionLists()) {
                        requiredStepsMedia += this.databaseService.getDistributionListMessageModelFactory().countByTypes(fileTypesArray);
                    }

                    requiredBackupSteps += (requiredStepsMedia * getStepFactorMedia());
                } catch (Exception e) {
                    logger.error("Could not backup media and thumbnails", e);
                }
            }

            if (this.config.backupNonces()) {
                requiredBackupSteps += 1;
                long nonceCount = nonceFactory.getCount(NonceScope.CSP) + nonceFactory.getCount(NonceScope.D2D);
                long requiredStepsNonces = (long) Math.ceil((double) nonceCount / NONCES_PER_STEP);
                requiredBackupSteps += requiredStepsNonces;
            }

            if (this.config.backupReactions()) {
                requiredBackupSteps += 2;
                requiredBackupSteps += reactionsRepository.getContactReactionsCount() / REACTIONS_PER_STEP;
                requiredBackupSteps += reactionsRepository.getGroupReactionsCount() / REACTIONS_PER_STEP;
            }

            this.initProgress(requiredBackupSteps);

            zipOutputStream.addFileFromInputStream(new ByteArrayInputStream(settingsBuffer.toByteArray()), Tags.SETTINGS_FILE_NAME, true);

            if (this.config.backupIdentity()) {
                if (!this.backupIdentity(identity, zipOutputStream)) {
                    return this.cancelBackup(backupFile);
                }
            }

            // backup contacts and messages
            if (this.config.backupContactAndMessages()) {
                if (!this.backupContactsAndMessages(config, zipOutputStream)) {
                    return this.cancelBackup(backupFile);
                }
            }

            // backup groups and messages
            if (this.config.backupGroupsAndMessages()) {
                if (!this.backupGroupsAndMessages(config, zipOutputStream)) {
                    return this.cancelBackup(backupFile);
                }
            }

            // backup distribution lists and messages
            if (this.config.backupDistributionLists()) {
                if (!this.backupDistributionListsAndMessages(config, zipOutputStream)) {
                    return this.cancelBackup(backupFile);
                }
            }

            if (this.config.backupBallots()) {
                if (!this.backupBallots(zipOutputStream)) {
                    return this.cancelBackup(backupFile);
                }
            }

            if (this.config.backupReactions()) {
                if (!this.backupReactions(zipOutputStream)) {
                    return this.cancelBackup(backupFile);
                }
            }

            // Backup nonces
            if (this.config.backupNonces()) {
                if (!this.backupNonces(zipOutputStream)) {
                    return this.cancelBackup(backupFile);
                }
            }

            backupSuccess = true;
            onFinished("");
        } catch (final Exception e) {
            removeBackupFile(backupFile);

            backupSuccess = false;
            onFinished("Error: " + e.getMessage());

            logger.error("Backup could not be created", e);
        }
        return backupSuccess;
    }

    private boolean backupIdentity(String identity, FileHandlingZipOutputStream zipOutputStream) throws ThreemaException, IOException {
        logger.info("Backup identity");
        if (!this.next("backup identity")) {
            return false;
        }

        byte[] privateKey = this.userService.getPrivateKey();
        IdentityBackupGenerator identityBackupGenerator = new IdentityBackupGenerator(identity, privateKey);
        String backupData = identityBackupGenerator.generateBackup(this.config.getPassword());

        zipOutputStream.addFileFromInputStream(IOUtils.toInputStream(backupData), Tags.IDENTITY_FILE_NAME, false);
        return true;
    }

    private boolean next(String subject) {
        return this.next(subject, 1);
    }

    private boolean next(String subject, long increment) {
        logger.debug("step [{}]", subject);
        this.currentProgressStep += (this.currentProgressStep < this.processSteps ? increment : 0);
        this.handleProgress();
        return !isCanceled;
    }

    /**
     * only call progress on 100 steps
     */
    private void handleProgress() {
        int p = (int) (100d / (double) this.processSteps * (double) this.currentProgressStep);
        if (p > this.latestPercentStep) {
            this.latestPercentStep = p;
            String timeRemaining = getRemainingTimeText(latestPercentStep, 100);
            updatePersistentNotification(latestPercentStep, 100, timeRemaining);
            LocalBroadcastManager.getInstance(ThreemaApplication.getAppContext())
                .sendBroadcast(new Intent(BACKUP_PROGRESS_INTENT)
                    .putExtra(BACKUP_PROGRESS, latestPercentStep)
                    .putExtra(BACKUP_PROGRESS_STEPS, 100)
                    .putExtra(BACKUP_PROGRESS_MESSAGE, timeRemaining)
                );
        }
    }

    private void removeBackupFile(DocumentFile zipFile) {
        // remove zip file
        if (zipFile != null && zipFile.exists()) {
            logger.info("Remove backup file {}", zipFile.getUri());
            zipFile.delete();
        }
    }

    private boolean cancelBackup(DocumentFile zipFile) {
        removeBackupFile(zipFile);
        backupSuccess = false;
        onFinished(null);

        return false;
    }

    private void initProgress(long steps) {
        logger.info("Init progress with {} required steps", steps);
        this.currentProgressStep = 0;
        this.processSteps = steps;
        this.latestPercentStep = 0;
        this.startTime = System.currentTimeMillis();
        this.handleProgress();
    }

    /**
     * Create a Backup of all contacts and messages.
     * Backup media if configured.
     */
    private boolean backupContactsAndMessages(
        @NonNull BackupRestoreDataConfig config,
        @NonNull FileHandlingZipOutputStream zipOutputStream
    ) throws ThreemaException, IOException {
        // first, save my own profile pic
        if (this.config.backupAvatars()) {
            logger.info("Backup own avatar");
            try {
                zipOutputStream.addFileFromInputStream(
                    this.fileService.getUserDefinedProfilePictureStream(contactService.getMe().getIdentity()),
                    Tags.CONTACT_AVATAR_FILE_PREFIX + Tags.CONTACT_AVATAR_FILE_SUFFIX_ME,
                    false
                );
            } catch (IOException e) {
                logger.warn("Could not back up own avatar: {}", e.getMessage());
            }
        }

        final String[] contactCsvHeader = {
            Tags.TAG_CONTACT_IDENTITY,
            Tags.TAG_CONTACT_PUBLIC_KEY,
            Tags.TAG_CONTACT_VERIFICATION_LEVEL,
            Tags.TAG_CONTACT_ANDROID_CONTACT_ID,
            Tags.TAG_CONTACT_FIRST_NAME,
            Tags.TAG_CONTACT_LAST_NAME,
            Tags.TAG_CONTACT_NICK_NAME,
            Tags.TAG_CONTACT_LAST_UPDATE,
            Tags.TAG_CONTACT_HIDDEN,
            Tags.TAG_CONTACT_ARCHIVED,
            Tags.TAG_CONTACT_IDENTITY_ID,
        };
        final String[] messageCsvHeader = {
            Tags.TAG_MESSAGE_API_MESSAGE_ID,
            Tags.TAG_MESSAGE_UID,
            Tags.TAG_MESSAGE_IS_OUTBOX,
            Tags.TAG_MESSAGE_IS_READ,
            Tags.TAG_MESSAGE_IS_SAVED,
            Tags.TAG_MESSAGE_MESSAGE_STATE,
            Tags.TAG_MESSAGE_POSTED_AT,
            Tags.TAG_MESSAGE_CREATED_AT,
            Tags.TAG_MESSAGE_MODIFIED_AT,
            Tags.TAG_MESSAGE_TYPE,
            Tags.TAG_MESSAGE_BODY,
            Tags.TAG_MESSAGE_IS_STATUS_MESSAGE,
            Tags.TAG_MESSAGE_CAPTION,
            Tags.TAG_MESSAGE_QUOTED_MESSAGE_ID,
            Tags.TAG_MESSAGE_DELIVERED_AT,
            Tags.TAG_MESSAGE_READ_AT,
            Tags.TAG_GROUP_MESSAGE_STATES,
            Tags.TAG_MESSAGE_DISPLAY_TAGS,
            Tags.TAG_MESSAGE_EDITED_AT,
            Tags.TAG_MESSAGE_DELETED_AT
        };

        final @NonNull Set<String> removedContacts = contactService.getRemovedContacts();

        logger.info("Backup contacts, messages, and contact avatars");
        // Iterate over all contacts. Then backup every contact with the corresponding messages.
        try (final ByteArrayOutputStream contactBuffer = new ByteArrayOutputStream()) {
            try (final CSVWriter contactCsv = new CSVWriter(new OutputStreamWriter(contactBuffer), contactCsvHeader)) {
                for (final ContactModel contactModel : contactService.find(null)) {
                    if (!this.next("backup contact " + contactModel.getIdentity())) {
                        return false;
                    }

                    // Do not include removed contacts in data backup
                    if (removedContacts.contains(contactModel.getIdentity())) {
                        continue;
                    }

                    String identityId = getFormattedUniqueId();

                    // Write contact
                    contactCsv.createRow()
                        .write(Tags.TAG_CONTACT_IDENTITY, contactModel.getIdentity())
                        .write(Tags.TAG_CONTACT_PUBLIC_KEY, Utils.byteArrayToHexString(contactModel.getPublicKey()))
                        .write(Tags.TAG_CONTACT_VERIFICATION_LEVEL, contactModel.verificationLevel.toString())
                        .write(Tags.TAG_CONTACT_ANDROID_CONTACT_ID, contactModel.getAndroidContactLookupKey())
                        .write(Tags.TAG_CONTACT_FIRST_NAME, contactModel.getFirstName())
                        .write(Tags.TAG_CONTACT_LAST_NAME, contactModel.getLastName())
                        .write(Tags.TAG_CONTACT_NICK_NAME, contactModel.getPublicNickName())
                        .write(Tags.TAG_CONTACT_LAST_UPDATE, contactModel.getLastUpdate())
                        .write(Tags.TAG_CONTACT_HIDDEN, contactModel.getAcquaintanceLevel() == ContactModel.AcquaintanceLevel.GROUP)
                        .write(Tags.TAG_CONTACT_ARCHIVED, contactModel.isArchived())
                        .write(Tags.TAG_CONTACT_IDENTITY_ID, identityId)
                        .write();

                    // Back up contact profile pictures
                    if (this.config.backupAvatars()) {
                        try {
                            if (!userService.getIdentity().equals(contactModel.getIdentity())) {
                                zipOutputStream.addFileFromInputStream(
                                    this.fileService.getUserDefinedProfilePictureStream(contactModel.getIdentity()),
                                    Tags.CONTACT_AVATAR_FILE_PREFIX + identityId,
                                    false
                                );
                            }
                        } catch (IOException e) {
                            // avatars are not THAT important, so we don't care if adding them fails
                            logger.warn("Could not back up avatar for contact {}: {}", contactModel.getIdentity(), e.getMessage());
                        }

                        try {
                            zipOutputStream.addFileFromInputStream(
                                this.fileService.getContactDefinedProfilePictureStream(contactModel.getIdentity()),
                                Tags.CONTACT_PROFILE_PIC_FILE_PREFIX + identityId,
                                false
                            );
                        } catch (IOException e) {
                            // profile pics are not THAT important, so we don't care if adding them fails
                            logger.warn("Could not back up profile pic for contact {}: {}", contactModel.getIdentity(), e.getMessage());
                        }
                    }

                    // Back up conversations
                    try (final ByteArrayOutputStream messageBuffer = new ByteArrayOutputStream()) {
                        try (final CSVWriter messageCsv = new CSVWriter(new OutputStreamWriter(messageBuffer), messageCsvHeader)) {

                            List<MessageModel> messageModels = this.databaseService
                                .getMessageModelFactory()
                                .getByIdentityUnsorted(contactModel.getIdentity());

                            for (MessageModel messageModel : messageModels) {
                                if (!this.next("backup message " + messageModel.getId())) {
                                    return false;
                                }

                                String apiMessageId = messageModel.getApiMessageId();

                                if ((apiMessageId != null && !apiMessageId.isEmpty()) || messageModel.getType() == MessageType.VOIP_STATUS) {
                                    messageCsv.createRow()
                                        .write(Tags.TAG_MESSAGE_API_MESSAGE_ID, messageModel.getApiMessageId())
                                        .write(Tags.TAG_MESSAGE_UID, messageModel.getUid())
                                        .write(Tags.TAG_MESSAGE_IS_OUTBOX, messageModel.isOutbox())
                                        .write(Tags.TAG_MESSAGE_IS_READ, messageModel.isRead())
                                        .write(Tags.TAG_MESSAGE_IS_SAVED, messageModel.isSaved())
                                        .write(Tags.TAG_MESSAGE_MESSAGE_STATE, messageModel.getState())
                                        .write(Tags.TAG_MESSAGE_POSTED_AT, messageModel.getPostedAt())
                                        .write(Tags.TAG_MESSAGE_CREATED_AT, messageModel.getCreatedAt())
                                        .write(Tags.TAG_MESSAGE_MODIFIED_AT, messageModel.getModifiedAt())
                                        .write(Tags.TAG_MESSAGE_TYPE, messageModel.getType().toString())
                                        .write(Tags.TAG_MESSAGE_BODY, messageModel.getBody())
                                        .write(Tags.TAG_MESSAGE_IS_STATUS_MESSAGE, messageModel.isStatusMessage())
                                        .write(Tags.TAG_MESSAGE_CAPTION, messageModel.getCaption())
                                        .write(Tags.TAG_MESSAGE_QUOTED_MESSAGE_ID, messageModel.getQuotedMessageId())
                                        .write(Tags.TAG_MESSAGE_DELIVERED_AT, messageModel.getDeliveredAt())
                                        .write(Tags.TAG_MESSAGE_READ_AT, messageModel.getReadAt())
                                        .write(Tags.TAG_MESSAGE_DISPLAY_TAGS, messageModel.getDisplayTags())
                                        .write(Tags.TAG_MESSAGE_EDITED_AT, messageModel.getEditedAt())
                                        .write(Tags.TAG_MESSAGE_DELETED_AT, messageModel.getDeletedAt())
                                        .write();
                                }

                                this.backupMediaFile(
                                    config,
                                    zipOutputStream,
                                    Tags.MESSAGE_MEDIA_FILE_PREFIX,
                                    Tags.MESSAGE_MEDIA_THUMBNAIL_FILE_PREFIX,
                                    messageModel);
                            }
                        }

                        zipOutputStream.addFileFromInputStream(
                            new ByteArrayInputStream(messageBuffer.toByteArray()),
                            Tags.MESSAGE_FILE_PREFIX + identityId + Tags.CSV_FILE_POSTFIX,
                            true
                        );
                    }
                }
            }

            zipOutputStream.addFileFromInputStream(
                new ByteArrayInputStream(contactBuffer.toByteArray()),
                Tags.CONTACTS_FILE_NAME + Tags.CSV_FILE_POSTFIX,
                true
            );
        }

        return true;
    }

    /**
     * Backup all groups with messages and media (if configured).
     */
    private boolean backupGroupsAndMessages(
        @NonNull BackupRestoreDataConfig config,
        @NonNull FileHandlingZipOutputStream zipOutputStream
    ) throws ThreemaException, IOException {
        final String[] groupCsvHeader = {
            Tags.TAG_GROUP_ID,
            Tags.TAG_GROUP_CREATOR,
            Tags.TAG_GROUP_NAME,
            Tags.TAG_GROUP_CREATED_AT,
            Tags.TAG_GROUP_LAST_UPDATE,
            Tags.TAG_GROUP_MEMBERS,
            Tags.TAG_GROUP_ARCHIVED,
            Tags.TAG_GROUP_DESC,
            Tags.TAG_GROUP_DESC_TIMESTAMP,
            Tags.TAG_GROUP_UID,
            Tags.TAG_GROUP_USER_STATE,
        };
        final String[] groupMessageCsvHeader = {
            Tags.TAG_MESSAGE_API_MESSAGE_ID,
            Tags.TAG_MESSAGE_UID,
            Tags.TAG_MESSAGE_IDENTITY,
            Tags.TAG_MESSAGE_IS_OUTBOX,
            Tags.TAG_MESSAGE_IS_READ,
            Tags.TAG_MESSAGE_IS_SAVED,
            Tags.TAG_MESSAGE_MESSAGE_STATE,
            Tags.TAG_MESSAGE_POSTED_AT,
            Tags.TAG_MESSAGE_CREATED_AT,
            Tags.TAG_MESSAGE_MODIFIED_AT,
            Tags.TAG_MESSAGE_TYPE,
            Tags.TAG_MESSAGE_BODY,
            Tags.TAG_MESSAGE_IS_STATUS_MESSAGE,
            Tags.TAG_MESSAGE_CAPTION,
            Tags.TAG_MESSAGE_QUOTED_MESSAGE_ID,
            Tags.TAG_MESSAGE_DELIVERED_AT,
            Tags.TAG_MESSAGE_READ_AT,
            Tags.TAG_GROUP_MESSAGE_STATES,
            Tags.TAG_MESSAGE_DISPLAY_TAGS,
            Tags.TAG_MESSAGE_EDITED_AT,
            Tags.TAG_MESSAGE_DELETED_AT
        };

        final GroupService.GroupFilter groupFilter = new GroupService.GroupFilter() {
            @Override
            public boolean sortByDate() {
                return false;
            }

            @Override
            public boolean sortByName() {
                return false;
            }

            @Override
            public boolean sortAscending() {
                return false;
            }

            @Override
            public boolean includeLeftGroups() {
                return true;
            }
        };

        logger.info("Backup groups, messages and group avatars");
        try (final ByteArrayOutputStream groupBuffer = new ByteArrayOutputStream()) {
            try (final CSVWriter groupCsv = new CSVWriter(new OutputStreamWriter(groupBuffer), groupCsvHeader)) {
                for (final GroupModel groupModel : this.groupService.getAll(groupFilter)) {
                    String groupUid = getFormattedUniqueId();
                    groupUidMap.put(groupModel.getId(), groupUid);

                    if (!this.next("backup group " + groupModel.getApiGroupId())) {
                        return false;
                    }

                    groupCsv.createRow()
                        .write(Tags.TAG_GROUP_ID, groupModel.getApiGroupId())
                        .write(Tags.TAG_GROUP_CREATOR, groupModel.getCreatorIdentity())
                        .write(Tags.TAG_GROUP_NAME, groupModel.getName())
                        .write(Tags.TAG_GROUP_CREATED_AT, groupModel.getCreatedAt())
                        .write(Tags.TAG_GROUP_LAST_UPDATE, groupModel.getLastUpdate())
                        .write(Tags.TAG_GROUP_MEMBERS, this.groupService.getGroupMemberIdentities(groupModel))
                        .write(Tags.TAG_GROUP_ARCHIVED, groupModel.isArchived())
                        .write(Tags.TAG_GROUP_DESC, groupModel.getGroupDesc())
                        .write(Tags.TAG_GROUP_DESC_TIMESTAMP, groupModel.getGroupDescTimestamp())
                        .write(Tags.TAG_GROUP_UID, groupUid)
                        .write(Tags.TAG_GROUP_USER_STATE, groupModel.getUserState() != null ? groupModel.getUserState().value : 0)
                        .write();

                    //check if the group have a photo
                    if (this.config.backupAvatars()) {
                        try {
                            zipOutputStream.addFileFromInputStream(
                                this.fileService.getGroupAvatarStream(groupModel),
                                Tags.GROUP_AVATAR_PREFIX + groupUid,
                                false
                            );
                        } catch (Exception e) {
                            logger.warn("Could not back up group avatar: {}", e.getMessage());
                        }
                    }

                    // Back up group messages
                    try (final ByteArrayOutputStream groupMessageBuffer = new ByteArrayOutputStream()) {
                        try (final CSVWriter groupMessageCsv = new CSVWriter(new OutputStreamWriter(groupMessageBuffer), groupMessageCsvHeader)) {
                            List<GroupMessageModel> groupMessageModels = this.databaseService
                                .getGroupMessageModelFactory()
                                .getByGroupIdUnsorted(groupModel.getId());

                            for (GroupMessageModel groupMessageModel : groupMessageModels) {
                                if (!this.next("backup group message " + groupMessageModel.getUid())) {
                                    return false;
                                }

                                String groupMessageStates = "";
                                if (groupMessageModel.getGroupMessageStates() != null) {
                                    groupMessageStates = new JSONObject(groupMessageModel.getGroupMessageStates()).toString();
                                }

                                groupMessageCsv.createRow()
                                    .write(Tags.TAG_MESSAGE_API_MESSAGE_ID, groupMessageModel.getApiMessageId())
                                    .write(Tags.TAG_MESSAGE_UID, groupMessageModel.getUid())
                                    .write(Tags.TAG_MESSAGE_IDENTITY, groupMessageModel.getIdentity())
                                    .write(Tags.TAG_MESSAGE_IS_OUTBOX, groupMessageModel.isOutbox())
                                    .write(Tags.TAG_MESSAGE_IS_READ, groupMessageModel.isRead())
                                    .write(Tags.TAG_MESSAGE_IS_SAVED, groupMessageModel.isSaved())
                                    .write(Tags.TAG_MESSAGE_MESSAGE_STATE, groupMessageModel.getState())
                                    .write(Tags.TAG_MESSAGE_POSTED_AT, groupMessageModel.getPostedAt())
                                    .write(Tags.TAG_MESSAGE_CREATED_AT, groupMessageModel.getCreatedAt())
                                    .write(Tags.TAG_MESSAGE_MODIFIED_AT, groupMessageModel.getModifiedAt())
                                    .write(Tags.TAG_MESSAGE_TYPE, groupMessageModel.getType())
                                    .write(Tags.TAG_MESSAGE_BODY, groupMessageModel.getBody())
                                    .write(Tags.TAG_MESSAGE_IS_STATUS_MESSAGE, groupMessageModel.isStatusMessage())
                                    .write(Tags.TAG_MESSAGE_CAPTION, groupMessageModel.getCaption())
                                    .write(Tags.TAG_MESSAGE_QUOTED_MESSAGE_ID, groupMessageModel.getQuotedMessageId())
                                    .write(Tags.TAG_MESSAGE_DELIVERED_AT, groupMessageModel.getDeliveredAt())
                                    .write(Tags.TAG_MESSAGE_READ_AT, groupMessageModel.getReadAt())
                                    .write(Tags.TAG_GROUP_MESSAGE_STATES, groupMessageStates)
                                    .write(Tags.TAG_MESSAGE_DISPLAY_TAGS, groupMessageModel.getDisplayTags())
                                    .write(Tags.TAG_MESSAGE_EDITED_AT, groupMessageModel.getEditedAt())
                                    .write(Tags.TAG_MESSAGE_DELETED_AT, groupMessageModel.getDeletedAt())
                                    .write();

                                this.backupMediaFile(
                                    config,
                                    zipOutputStream,
                                    Tags.GROUP_MESSAGE_MEDIA_FILE_PREFIX,
                                    Tags.GROUP_MESSAGE_MEDIA_THUMBNAIL_FILE_PREFIX,
                                    groupMessageModel
                                );
                            }
                        }

                        zipOutputStream.addFileFromInputStream(
                            new ByteArrayInputStream(groupMessageBuffer.toByteArray()),
                            Tags.GROUP_MESSAGE_FILE_PREFIX + groupUid + Tags.CSV_FILE_POSTFIX,
                            true
                        );
                    }
                }
            }

            zipOutputStream.addFileFromInputStream(
                new ByteArrayInputStream(groupBuffer.toByteArray()),
                Tags.GROUPS_FILE_NAME + Tags.CSV_FILE_POSTFIX,
                true
            );
        }

        return true;
    }

    /**
     * Backup the reactions. Note that reactions will be sorted by the referenced messages to enable
     * efficient restoring of the reactions.
     * The sorting occurs directly in the {@link ch.threema.data.storage.EmojiReactionsDao} when the
     * reactions are queried for backup creation.
     */
    private boolean backupReactions(@NonNull FileHandlingZipOutputStream zipOutputStream) throws ThreemaException {
        @Nullable Long contactReactionCount = backupContactReactions(zipOutputStream);
        if (contactReactionCount == null) {
            return false;
        }

        @Nullable Long groupReactionCount = backupGroupReactions(zipOutputStream);
        if (groupReactionCount == null) {
            return false;
        }

        boolean success = writeReactionCounts(contactReactionCount, groupReactionCount, zipOutputStream);
        logger.info("Reaction backup completed");
        return success;
    }

    private boolean writeReactionCounts(
        long contactReactionCount,
        long groupReactionCount,
        @NonNull FileHandlingZipOutputStream zipOutputStream
    ) throws ThreemaException {
        logger.info("Write reaction counts (contactReactions={}, groupReactions={})", contactReactionCount, groupReactionCount);
        final String[] reactionCountsHeader = new String[]{ Tags.TAG_REACTION_COUNT_CONTACTS, Tags.TAG_REACTION_COUNT_GROUPS };

        zipOutputStream.addFile(
            Tags.REACTION_COUNTS_FILE + Tags.CSV_FILE_POSTFIX,
            /* compress */ true,
            outputStream -> {
                try (final CSVWriter csvWriter = new CSVWriter(new OutputStreamWriter(outputStream), reactionCountsHeader)) {
                    csvWriter.createRow()
                        .write(Tags.TAG_REACTION_COUNT_CONTACTS, contactReactionCount)
                        .write(Tags.TAG_REACTION_COUNT_GROUPS, groupReactionCount)
                        .write();
                }
            }
        );
        return true;
    }

    @Nullable
    private Long backupContactReactions(@NonNull FileHandlingZipOutputStream zipOutputStream) throws ThreemaException {
        logger.info("Backup contact reactions");
        if (!next("contact reactions")) {
            logger.info("Backup of contact reactions cancelled");
            return null;
        }

        final String[] contactReactionsCsvHeader = {
            Tags.TAG_REACTION_CONTACT_IDENTITY,
            Tags.TAG_REACTION_API_MESSAGE_ID,
            Tags.TAG_REACTION_SENDER_IDENTITY,
            Tags.TAG_REACTION_EMOJI_SEQUENCE,
            Tags.TAG_REACTION_REACTED_AT
        };

        final Counter rowCounter = new Counter(REACTIONS_PER_STEP);
        zipOutputStream.addFile(
            Tags.CONTACT_REACTIONS_FILE_NAME + Tags.CSV_FILE_POSTFIX,
            true,
            outputStream -> {
                try (final CSVWriter csvWriter = new CSVWriter(new OutputStreamWriter(outputStream), contactReactionsCsvHeader)) {
                    reactionsRepository.iterateAllContactReactionsForBackup(reaction -> {
                        csvWriter.createRow()
                            .write(Tags.TAG_REACTION_CONTACT_IDENTITY, reaction.getContactIdentity())
                            .write(Tags.TAG_REACTION_API_MESSAGE_ID, reaction.getApiMessageId())
                            .write(Tags.TAG_REACTION_SENDER_IDENTITY, reaction.getSenderIdentity())
                            .write(Tags.TAG_REACTION_EMOJI_SEQUENCE, reaction.getEmojiSequence())
                            .write(Tags.TAG_REACTION_REACTED_AT, reaction.getReactedAt())
                            .write();
                        rowCounter.count();
                        long steps = rowCounter.getAndResetSteps(REACTION_STEP_THRESHOLD);
                        if (steps > 0) {
                            // Backing up of reactions cannot be cancelled in this scope (with a reasonable
                            // effort) therefore we only update the progress and ignore the return value.
                            next("backup contact reactions", steps);
                        }
                    });
                    next("backup contact reactions done", rowCounter.getSteps());
                }
            }
        );
        return rowCounter.getCount();
    }


    @Nullable
    private Long backupGroupReactions(@NonNull FileHandlingZipOutputStream zipOutputStream) throws ThreemaException {
        logger.info("Backup group reactions");
        if (!next("group reactions")) {
            logger.info("Backup uf group reactions cancelled");
            return null;
        }

        final String[] contactReactionsCsvHeader = {
            Tags.TAG_REACTION_API_GROUP_ID,
            Tags.TAG_REACTION_GROUP_CREATOR_IDENTITY,
            Tags.TAG_REACTION_API_MESSAGE_ID,
            Tags.TAG_REACTION_SENDER_IDENTITY,
            Tags.TAG_REACTION_EMOJI_SEQUENCE,
            Tags.TAG_REACTION_REACTED_AT
        };

        final Counter rowCounter = new Counter(REACTIONS_PER_STEP);
        zipOutputStream.addFile(
            Tags.GROUP_REACTIONS_FILE_NAME + Tags.CSV_FILE_POSTFIX,
            true,
            outputStream -> {
                try (final CSVWriter csvWriter = new CSVWriter(new OutputStreamWriter(outputStream), contactReactionsCsvHeader)) {
                    reactionsRepository.iterateAllGroupReactionsForBackup(reaction -> {
                        csvWriter.createRow()
                            .write(Tags.TAG_REACTION_API_GROUP_ID, reaction.getApiGroupId())
                            .write(Tags.TAG_REACTION_GROUP_CREATOR_IDENTITY, reaction.getGroupCreatorIdentity())
                            .write(Tags.TAG_REACTION_API_MESSAGE_ID, reaction.getApiMessageId())
                            .write(Tags.TAG_REACTION_SENDER_IDENTITY, reaction.getSenderIdentity())
                            .write(Tags.TAG_REACTION_EMOJI_SEQUENCE, reaction.getEmojiSequence())
                            .write(Tags.TAG_REACTION_REACTED_AT, reaction.getReactedAt())
                            .write();
                        rowCounter.count();
                        long steps = rowCounter.getAndResetSteps(REACTION_STEP_THRESHOLD);
                        if (steps > 0) {
                            // Backing up of reactions cannot be cancelled in this scope (with a reasonable
                            // effort) therefore we only update the progress and ignore the return value.
                            next("backup group reactions", steps);
                        }
                    });
                    next("backup group reactions done", rowCounter.getSteps());
                }
            }
        );
        return rowCounter.getCount();
    }


    /**
     * backup all ballots with votes and choices!
     */
    private boolean backupBallots(
        @NonNull FileHandlingZipOutputStream zipOutputStream
    ) throws ThreemaException, IOException {
        logger.info("Backup polls (formerly known as 'ballots'");
        final String[] ballotCsvHeader = {
            Tags.TAG_BALLOT_ID,
            Tags.TAG_BALLOT_API_ID,
            Tags.TAG_BALLOT_API_CREATOR,
            Tags.TAG_BALLOT_REF,
            Tags.TAG_BALLOT_REF_ID,
            Tags.TAG_BALLOT_NAME,
            Tags.TAG_BALLOT_STATE,
            Tags.TAG_BALLOT_ASSESSMENT,
            Tags.TAG_BALLOT_TYPE,
            Tags.TAG_BALLOT_C_TYPE,
            Tags.TAG_BALLOT_LAST_VIEWED_AT,
            Tags.TAG_BALLOT_CREATED_AT,
            Tags.TAG_BALLOT_MODIFIED_AT,
        };
        final String[] ballotChoiceCsvHeader = {
            Tags.TAG_BALLOT_CHOICE_ID,
            Tags.TAG_BALLOT_CHOICE_BALLOT_UID,
            Tags.TAG_BALLOT_CHOICE_API_ID,
            Tags.TAG_BALLOT_CHOICE_TYPE,
            Tags.TAG_BALLOT_CHOICE_NAME,
            Tags.TAG_BALLOT_CHOICE_VOTE_COUNT,
            Tags.TAG_BALLOT_CHOICE_ORDER,
            Tags.TAG_BALLOT_CHOICE_CREATED_AT,
            Tags.TAG_BALLOT_CHOICE_MODIFIED_AT,
        };
        final String[] ballotVoteCsvHeader = {
            Tags.TAG_BALLOT_VOTE_ID,
            Tags.TAG_BALLOT_VOTE_BALLOT_UID,
            Tags.TAG_BALLOT_VOTE_CHOICE_UID,
            Tags.TAG_BALLOT_VOTE_IDENTITY,
            Tags.TAG_BALLOT_VOTE_CHOICE,
            Tags.TAG_BALLOT_VOTE_CREATED_AT,
            Tags.TAG_BALLOT_VOTE_MODIFIED_AT,
        };

        try (
            final ByteArrayOutputStream ballotCsvBuffer = new ByteArrayOutputStream();
            final ByteArrayOutputStream ballotChoiceCsvBuffer = new ByteArrayOutputStream();
            final ByteArrayOutputStream ballotVoteCsvBuffer = new ByteArrayOutputStream()
        ) {
            try (
                final OutputStreamWriter ballotOsw = new OutputStreamWriter(ballotCsvBuffer);
                final OutputStreamWriter ballotChoiceOsw = new OutputStreamWriter(ballotChoiceCsvBuffer);
                final OutputStreamWriter ballotVoteOsw = new OutputStreamWriter(ballotVoteCsvBuffer);
                final CSVWriter ballotCsv = new CSVWriter(ballotOsw, ballotCsvHeader);
                final CSVWriter ballotChoiceCsv = new CSVWriter(ballotChoiceOsw, ballotChoiceCsvHeader);
                final CSVWriter ballotVoteCsv = new CSVWriter(ballotVoteOsw, ballotVoteCsvHeader)
            ) {

                List<BallotModel> ballots = ballotService.getBallots(new BallotService.BallotFilter() {
                    @Override
                    public MessageReceiver getReceiver() {
                        return null;
                    }

                    @Override
                    public BallotModel.State[] getStates() {
                        return new BallotModel.State[]{BallotModel.State.OPEN, BallotModel.State.CLOSED};
                    }

                    @Override
                    public boolean filter(BallotModel ballotModel) {
                        return true;
                    }
                });

                if (ballots != null) {
                    for (BallotModel ballotModel : ballots) {
                        if (!this.next("ballot " + ballotModel.getId())) {
                            return false;
                        }

                        LinkBallotModel link = ballotService.getLinkedBallotModel(ballotModel);
                        if (link == null) {
                            continue;
                        }

                        String ref;
                        String refId;
                        if (link instanceof GroupBallotModel) {
                            GroupModel groupModel = groupService
                                .getById(((GroupBallotModel) link).getGroupId());

                            if (groupModel == null) {
                                logger.error("invalid group for a ballot");
                                continue;
                            }

                            ref = "GroupBallotModel";
                            refId = groupUidMap.get(groupModel.getId());
                        } else if (link instanceof IdentityBallotModel) {
                            ref = "IdentityBallotModel";
                            refId = ((IdentityBallotModel) link).getIdentity();
                        } else {
                            continue;
                        }

                        ballotCsv.createRow()
                            .write(Tags.TAG_BALLOT_ID, ballotModel.getId())
                            .write(Tags.TAG_BALLOT_API_ID, ballotModel.getApiBallotId())
                            .write(Tags.TAG_BALLOT_API_CREATOR, ballotModel.getCreatorIdentity())
                            .write(Tags.TAG_BALLOT_REF, ref)
                            .write(Tags.TAG_BALLOT_REF_ID, refId)
                            .write(Tags.TAG_BALLOT_NAME, ballotModel.getName())
                            .write(Tags.TAG_BALLOT_STATE, ballotModel.getState())
                            .write(Tags.TAG_BALLOT_ASSESSMENT, ballotModel.getAssessment())
                            .write(Tags.TAG_BALLOT_TYPE, ballotModel.getType())
                            .write(Tags.TAG_BALLOT_C_TYPE, ballotModel.getChoiceType())
                            .write(Tags.TAG_BALLOT_LAST_VIEWED_AT, ballotModel.getLastViewedAt())
                            .write(Tags.TAG_BALLOT_CREATED_AT, ballotModel.getCreatedAt())
                            .write(Tags.TAG_BALLOT_MODIFIED_AT, ballotModel.getModifiedAt())
                            .write();


                        final List<BallotChoiceModel> ballotChoiceModels = this.databaseService
                            .getBallotChoiceModelFactory()
                            .getByBallotId(ballotModel.getId());
                        for (BallotChoiceModel ballotChoiceModel : ballotChoiceModels) {
                            ballotChoiceCsv.createRow()
                                .write(Tags.TAG_BALLOT_CHOICE_ID, ballotChoiceModel.getId())
                                .write(Tags.TAG_BALLOT_CHOICE_BALLOT_UID, BackupUtils.buildBallotUid(ballotModel))
                                .write(Tags.TAG_BALLOT_CHOICE_API_ID, ballotChoiceModel.getApiBallotChoiceId())
                                .write(Tags.TAG_BALLOT_CHOICE_TYPE, ballotChoiceModel.getType())
                                .write(Tags.TAG_BALLOT_CHOICE_NAME, ballotChoiceModel.getName())
                                .write(Tags.TAG_BALLOT_CHOICE_VOTE_COUNT, ballotChoiceModel.getVoteCount())
                                .write(Tags.TAG_BALLOT_CHOICE_ORDER, ballotChoiceModel.getOrder())
                                .write(Tags.TAG_BALLOT_CHOICE_CREATED_AT, ballotChoiceModel.getCreatedAt())
                                .write(Tags.TAG_BALLOT_CHOICE_MODIFIED_AT, ballotChoiceModel.getModifiedAt())
                                .write();

                        }

                        final List<BallotVoteModel> ballotVoteModels = this.databaseService
                            .getBallotVoteModelFactory()
                            .getByBallotId(ballotModel.getId());
                        for (final BallotVoteModel ballotVoteModel : ballotVoteModels) {
                            BallotChoiceModel ballotChoiceModel = Functional.select(ballotChoiceModels, type -> type.getId() == ballotVoteModel.getBallotChoiceId());

                            if (ballotChoiceModel == null) {
                                continue;
                            }

                            ballotVoteCsv.createRow()
                                .write(Tags.TAG_BALLOT_VOTE_ID, ballotVoteModel.getId())
                                .write(Tags.TAG_BALLOT_VOTE_BALLOT_UID, BackupUtils.buildBallotUid(ballotModel))
                                .write(Tags.TAG_BALLOT_VOTE_CHOICE_UID, BackupUtils.buildBallotChoiceUid(ballotChoiceModel))
                                .write(Tags.TAG_BALLOT_VOTE_IDENTITY, ballotVoteModel.getVotingIdentity())
                                .write(Tags.TAG_BALLOT_VOTE_CHOICE, ballotVoteModel.getChoice())
                                .write(Tags.TAG_BALLOT_VOTE_CREATED_AT, ballotVoteModel.getCreatedAt())
                                .write(Tags.TAG_BALLOT_VOTE_MODIFIED_AT, ballotVoteModel.getModifiedAt())
                                .write();

                        }
                    }
                }
            }

            zipOutputStream.addFileFromInputStream(
                new ByteArrayInputStream(ballotCsvBuffer.toByteArray()),
                Tags.BALLOT_FILE_NAME + Tags.CSV_FILE_POSTFIX,
                true
            );
            zipOutputStream.addFileFromInputStream(
                new ByteArrayInputStream(ballotChoiceCsvBuffer.toByteArray()),
                Tags.BALLOT_CHOICE_FILE_NAME + Tags.CSV_FILE_POSTFIX,
                true
            );
            zipOutputStream.addFileFromInputStream(
                new ByteArrayInputStream(ballotVoteCsvBuffer.toByteArray()),
                Tags.BALLOT_VOTE_FILE_NAME + Tags.CSV_FILE_POSTFIX,
                true
            );

        }

        return true;
    }

    private boolean backupNonces(@NonNull FileHandlingZipOutputStream zipOutputStream) {
        logger.info("Backup nonces");

        if (!next("Backup nonces")) {
            return false;
        }

        try {
            int nonceCountCsp = writeNoncesToBackup(
                NonceScope.CSP,
                Tags.NONCE_FILE_NAME_CSP + Tags.CSV_FILE_POSTFIX,
                zipOutputStream
            );

            int nonceCountD2d = writeNoncesToBackup(
                NonceScope.D2D,
                Tags.NONCE_FILE_NAME_D2D + Tags.CSV_FILE_POSTFIX,
                zipOutputStream
            );

            writeNonceCounts(nonceCountCsp, nonceCountD2d, zipOutputStream);

            int remainingCsp = BackupUtils.calcRemainingNoncesProgress(NONCES_CHUNK_SIZE, NONCES_PER_STEP, nonceCountCsp);
            int remainingD2d = BackupUtils.calcRemainingNoncesProgress(NONCES_CHUNK_SIZE, NONCES_PER_STEP, nonceCountD2d);
            next("Backup nonce", (int) Math.ceil(((double) remainingCsp + remainingD2d) / NONCES_PER_STEP));
            logger.info("Nonce backup completed");
        } catch (IOException | ThreemaException e) {
            logger.error("Error with byte array output stream", e);
            return false;
        }

        return true;
    }

    private void writeNonceCounts(
        int nonceCountCsp,
        int nonceCountD2d,
        @NonNull FileHandlingZipOutputStream zipOutputStream
    ) throws IOException, ThreemaException {
        logger.info("Write nonce counts to backup (CSP: {}, D2D: {})", nonceCountCsp, nonceCountD2d);
        final String[] nonceCountHeader = new String[]{Tags.TAG_NONCE_COUNT_CSP, Tags.TAG_NONCE_COUNT_D2D};
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            try (
                OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
                CSVWriter csvWriter = new CSVWriter(outputStreamWriter, nonceCountHeader)
            ) {
                csvWriter.createRow()
                    .write(Tags.TAG_NONCE_COUNT_CSP, nonceCountCsp)
                    .write(Tags.TAG_NONCE_COUNT_D2D, nonceCountD2d)
                    .write();
            }
            zipOutputStream.addFileFromInputStream(
                new ByteArrayInputStream(outputStream.toByteArray()),
                Tags.NONCE_COUNTS_FILE + Tags.CSV_FILE_POSTFIX,
                false
            );
        }
    }

    private int writeNoncesToBackup(
        @NonNull NonceScope scope,
        @NonNull String fileName,
        @NonNull FileHandlingZipOutputStream zipOutputStream
    ) throws ThreemaException, IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            int count = writeNonces(scope, outputStream);
            // Write nonces to zip *after* the CSVWriter has been closed (and therefore flushed)
            zipOutputStream.addFileFromInputStream(
                new ByteArrayInputStream(outputStream.toByteArray()),
                fileName,
                true
            );
            return count;
        }
    }

    private int writeNonces(
        @NonNull NonceScope scope,
        @NonNull ByteArrayOutputStream outputStream
    ) throws ThreemaException, IOException {
        logger.info("Backup {} nonces", scope);
        final String[] nonceHeader = new String[]{Tags.TAG_NONCES};
        int backedUpNonceCount = 0;
        try (
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
            CSVWriter csvWriter = new CSVWriter(outputStreamWriter, nonceHeader)
        ) {
            long start = System.currentTimeMillis();
            long nonceCount = nonceFactory.getCount(scope);
            long numChunks = (long) Math.ceil((double) nonceCount / NONCES_CHUNK_SIZE);
            List<HashedNonce> nonces = new ArrayList<>(NONCES_CHUNK_SIZE);
            for (int i = 0; i < numChunks; i++) {
                nonceFactory.addHashedNoncesChunk(
                    scope,
                    NONCES_CHUNK_SIZE,
                    NONCES_CHUNK_SIZE * i,
                    nonces
                );
                for (HashedNonce hashedNonce : nonces) {
                    String nonce = Utils.byteArrayToHexString(hashedNonce.getBytes());
                    csvWriter.createRow().write(Tags.TAG_NONCES, nonce).write();
                }
                int increment = nonces.size() / NONCES_PER_STEP;
                backedUpNonceCount += nonces.size();
                nonces.clear();
                if (!next("Backup nonce", increment)) {
                    return backedUpNonceCount;
                }
                // Periodically log nonce backup progress for debugging purposes
                if ((i % 10) == 0 || i == numChunks) {
                    logger.info("Nonce backup progress: {} of {} chunks backed up", i, numChunks);
                }
            }
            long end = System.currentTimeMillis();
            logger.info("Created backup for all {} nonces in {} ms", scope, end - start);
        }
        return backedUpNonceCount;
    }

    /**
     * Create the distribution list zip file.
     */
    private boolean backupDistributionListsAndMessages(
        @NonNull BackupRestoreDataConfig config,
        @NonNull FileHandlingZipOutputStream zipOutputStream
    ) throws ThreemaException, IOException {
        final String[] distributionListCsvHeader = {
            Tags.TAG_DISTRIBUTION_LIST_ID,
            Tags.TAG_DISTRIBUTION_LIST_NAME,
            Tags.TAG_DISTRIBUTION_CREATED_AT,
            Tags.TAG_DISTRIBUTION_LAST_UPDATE,
            Tags.TAG_DISTRIBUTION_MEMBERS,
            Tags.TAG_DISTRIBUTION_LIST_ARCHIVED,
        };
        final String[] distributionListMessageCsvHeader = {
            Tags.TAG_MESSAGE_API_MESSAGE_ID,
            Tags.TAG_MESSAGE_UID,
            Tags.TAG_MESSAGE_IDENTITY,
            Tags.TAG_MESSAGE_IS_OUTBOX,
            Tags.TAG_MESSAGE_IS_READ,
            Tags.TAG_MESSAGE_IS_SAVED,
            Tags.TAG_MESSAGE_MESSAGE_STATE,
            Tags.TAG_MESSAGE_POSTED_AT,
            Tags.TAG_MESSAGE_CREATED_AT,
            Tags.TAG_MESSAGE_MODIFIED_AT,
            Tags.TAG_MESSAGE_TYPE,
            Tags.TAG_MESSAGE_BODY,
            Tags.TAG_MESSAGE_IS_STATUS_MESSAGE,
            Tags.TAG_MESSAGE_CAPTION,
            Tags.TAG_MESSAGE_QUOTED_MESSAGE_ID,
            Tags.TAG_MESSAGE_DELIVERED_AT,
            Tags.TAG_MESSAGE_READ_AT,
        };

        logger.info("Backup distribution lists, messages and list avatars");
        try (final ByteArrayOutputStream distributionListBuffer = new ByteArrayOutputStream()) {
            try (final CSVWriter distributionListCsv = new CSVWriter(new OutputStreamWriter(distributionListBuffer), distributionListCsvHeader)) {

                for (DistributionListModel distributionListModel : distributionListService.getAll()) {
                    if (!this.next("distribution list " + distributionListModel.getId())) {
                        return false;
                    }
                    distributionListCsv.createRow()
                        .write(Tags.TAG_DISTRIBUTION_LIST_ID, distributionListModel.getId())
                        .write(Tags.TAG_DISTRIBUTION_LIST_NAME, distributionListModel.getName())
                        .write(Tags.TAG_DISTRIBUTION_CREATED_AT, distributionListModel.getCreatedAt())
                        .write(Tags.TAG_DISTRIBUTION_LAST_UPDATE, distributionListModel.getLastUpdate())
                        .write(Tags.TAG_DISTRIBUTION_MEMBERS, distributionListService.getDistributionListIdentities(distributionListModel))
                        .write(Tags.TAG_DISTRIBUTION_LIST_ARCHIVED, distributionListModel.isArchived())
                        .write();

                    try (final ByteArrayOutputStream messageBuffer = new ByteArrayOutputStream()) {
                        try (final CSVWriter distributionListMessageCsv = new CSVWriter(new OutputStreamWriter(messageBuffer), distributionListMessageCsvHeader)) {

                            final List<DistributionListMessageModel> distributionListMessageModels = this.databaseService
                                .getDistributionListMessageModelFactory()
                                .getByDistributionListIdUnsorted(distributionListModel.getId());
                            for (DistributionListMessageModel distributionListMessageModel : distributionListMessageModels) {
                                if (!this.next("distribution list message " + distributionListMessageModel.getId())) {
                                    return false;
                                }
                                distributionListMessageCsv.createRow()
                                    .write(Tags.TAG_MESSAGE_API_MESSAGE_ID, distributionListMessageModel.getApiMessageId())
                                    .write(Tags.TAG_MESSAGE_UID, distributionListMessageModel.getUid())
                                    .write(Tags.TAG_MESSAGE_IDENTITY, distributionListMessageModel.getIdentity())
                                    .write(Tags.TAG_MESSAGE_IS_OUTBOX, distributionListMessageModel.isOutbox())
                                    .write(Tags.TAG_MESSAGE_IS_READ, distributionListMessageModel.isRead())
                                    .write(Tags.TAG_MESSAGE_IS_SAVED, distributionListMessageModel.isSaved())
                                    .write(Tags.TAG_MESSAGE_MESSAGE_STATE, distributionListMessageModel.getState())
                                    .write(Tags.TAG_MESSAGE_POSTED_AT, distributionListMessageModel.getPostedAt())
                                    .write(Tags.TAG_MESSAGE_CREATED_AT, distributionListMessageModel.getCreatedAt())
                                    .write(Tags.TAG_MESSAGE_MODIFIED_AT, distributionListMessageModel.getModifiedAt())
                                    .write(Tags.TAG_MESSAGE_TYPE, distributionListMessageModel.getType())
                                    .write(Tags.TAG_MESSAGE_BODY, distributionListMessageModel.getBody())
                                    .write(Tags.TAG_MESSAGE_IS_STATUS_MESSAGE, distributionListMessageModel.isStatusMessage())
                                    .write(Tags.TAG_MESSAGE_CAPTION, distributionListMessageModel.getCaption())
                                    .write(Tags.TAG_MESSAGE_QUOTED_MESSAGE_ID, distributionListMessageModel.getQuotedMessageId())
                                    .write(Tags.TAG_MESSAGE_DELIVERED_AT, distributionListMessageModel.getDeliveredAt())
                                    .write(Tags.TAG_MESSAGE_READ_AT, distributionListMessageModel.getReadAt())
                                    .write();


                                this.backupMediaFile(
                                    config,
                                    zipOutputStream,
                                    Tags.DISTRIBUTION_LIST_MESSAGE_MEDIA_FILE_PREFIX,
                                    Tags.DISTRIBUTION_LIST_MESSAGE_MEDIA_THUMBNAIL_FILE_PREFIX,
                                    distributionListMessageModel
                                );
                            }
                        }

                        zipOutputStream.addFileFromInputStream(
                            new ByteArrayInputStream(messageBuffer.toByteArray()),
                            Tags.DISTRIBUTION_LIST_MESSAGE_FILE_PREFIX + distributionListModel.getId() + Tags.CSV_FILE_POSTFIX,
                            true
                        );
                    }
                }
            }

            zipOutputStream.addFileFromInputStream(
                new ByteArrayInputStream(distributionListBuffer.toByteArray()),
                Tags.DISTRIBUTION_LISTS_FILE_NAME + Tags.CSV_FILE_POSTFIX,
                true
            );
        }

        return true;
    }


    /**
     * Backup all media files of the given AbstractMessageModel, if {@link MessageUtil#hasDataFile}
     * returns true for the specified {@param messageModel}.
     */
    private void backupMediaFile(
        @NonNull BackupRestoreDataConfig config,
        @NonNull FileHandlingZipOutputStream zipOutputStream,
        @NonNull String filePrefix,
        @NonNull String thumbnailFilePrefix,
        @NonNull AbstractMessageModel messageModel
    ) {
        if (!MessageUtil.hasDataFile(messageModel)) {
            // its not a message model or a media message model
            return;
        }

        if (!this.next("media " + messageModel.getId(), getStepFactorMedia())) {
            return;
        }

        try {
            boolean saveMedia = false;
            boolean saveThumbnail = true;

            switch (messageModel.getType()) {
                case IMAGE:
                    saveMedia = config.backupMedia();
                    // image thumbnails will be generated again on restore - no need to save
                    saveThumbnail = !saveMedia;
                    break;
                case VIDEO:
                    if (config.backupMedia()) {
                        VideoDataModel videoDataModel = messageModel.getVideoData();
                        saveMedia = videoDataModel.isDownloaded();
                    }
                    break;
                case VOICEMESSAGE:
                    if (config.backupMedia()) {
                        AudioDataModel audioDataModel = messageModel.getAudioData();
                        saveMedia = audioDataModel.isDownloaded();
                    }
                    break;
                case FILE:
                    if (config.backupMedia()) {
                        FileDataModel fileDataModel = messageModel.getFileData();
                        saveMedia = fileDataModel.isDownloaded();
                    }
                    break;
                default:
                    return;
            }

            if (saveMedia) {
                InputStream is = this.fileService.getDecryptedMessageStream(messageModel);
                if (is != null) {
                    zipOutputStream.addFileFromInputStream(is, filePrefix + messageModel.getUid(), false);
                } else {
                    logger.debug("Can't add media for message {} ({}): missing file", messageModel.getUid(), messageModel.getPostedAt());
                    // try to save thumbnail if media is missing
                    saveThumbnail = true;
                }
            }

            if (config.backupThumbnails() && saveThumbnail) {
                // save thumbnail every time (if a thumbnail exists)
                InputStream is = this.fileService.getDecryptedMessageThumbnailStream(messageModel);
                if (is != null) {
                    zipOutputStream.addFileFromInputStream(is, thumbnailFilePrefix + messageModel.getUid(), false);
                }
            }
        } catch (Exception x) {
            // Don't abort the whole process, errors for media should not prevent the backup from succeeding
            logger.debug("Can't add media for message {} ({}): {}", messageModel.getUid(), messageModel.getPostedAt(), x.getMessage());
        }
    }

    public void onFinished(@Nullable String message) {
        if (TextUtils.isEmpty(message)) {
            logger.debug("onFinished (success={})", backupSuccess);
        } else {
            logger.debug("onFinished (success={}): {}", backupSuccess, message);
        }

        cancelPersistentNotification();

        if (backupSuccess) {
            // hacky, hacky: delay success notification for a few seconds to allow file system to settle.
            SystemClock.sleep(FILE_SETTLE_DELAY);

            if (backupFile != null) {
                // Rename to reflect that the backup has been completed successfully
                final String filename = backupFile.getName();
                if (filename != null && backupFile.renameTo(filename.replace(INCOMPLETE_BACKUP_FILENAME_PREFIX, ""))) {
                    // make sure media scanner sees this file
                    logger.debug("Sending media scanner broadcast");
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, backupFile.getUri()));

                    // Completed successfully!
                    preferenceService.setLastDataBackupDate(new Date());
                    showBackupSuccessNotification();
                    logger.info("Backup completed");
                } else {
                    logger.error("Backup failed: File could not be renamed");
                    showBackupErrorNotification(null);
                }
            } else {
                logger.error("Backup failed: File does not exist");
                showBackupErrorNotification(null);
            }
        } else {
            logger.error("Backup failed: {}", message);
            showBackupErrorNotification(message);

            // Send broadcast so that the BackupRestoreProgressActivity can display the message
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                new Intent().putExtra(BACKUP_PROGRESS_ERROR_MESSAGE, message)
            );
        }

        // try to reopen connection
        try {
            if (serviceManager != null) {
                serviceManager.startConnection();
            }
        } catch (Exception e) {
            logger.error("Could not start connection", e);
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            logger.debug("Releasing wakelock");
            wakeLock.release();
        }

        stopForeground(true);

        isRunning = false;

        // Send broadcast to indicate that the backup has been completed
        LocalBroadcastManager.getInstance(ThreemaApplication.getAppContext())
            .sendBroadcast(new Intent(BACKUP_PROGRESS_INTENT)
                .putExtra(BACKUP_PROGRESS, 100)
                .putExtra(BACKUP_PROGRESS_STEPS, 100)
            );

        stopSelf();
    }

    @SuppressLint("ForegroundServiceType")
    private void showPersistentNotification() {
        logger.debug("showPersistentNotification");

        Intent cancelIntent = new Intent(this, BackupService.class);
        cancelIntent.putExtra(EXTRA_ID_CANCEL, true);
        PendingIntent cancelPendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            cancelPendingIntent = PendingIntent.getForegroundService(this, (int) System.currentTimeMillis(), cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PENDING_INTENT_FLAG_IMMUTABLE);
        } else {
            cancelPendingIntent = PendingIntent.getService(this, (int) System.currentTimeMillis(), cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PENDING_INTENT_FLAG_IMMUTABLE);
        }

        notificationBuilder = new NotificationCompat.Builder(this, NotificationChannels.NOTIFICATION_CHANNEL_BACKUP_RESTORE_IN_PROGRESS)
            .setContentTitle(getString(R.string.backup_in_progress))
            .setContentText(getString(R.string.please_wait))
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(R.drawable.ic_close_white_24dp, getString(R.string.cancel), cancelPendingIntent);

        Notification notification = notificationBuilder.build();

        startForeground(notification);
    }

    private void startForeground(Notification notification) {
        ServiceCompat.startForeground(
            this,
            BACKUP_NOTIFICATION_ID,
            notification,
            FG_SERVICE_TYPE);
    }

    @SuppressLint("MissingPermission")
    private void updatePersistentNotification(int currentStep, int steps, String timeRemaining) {
        logger.debug("updatePersistentNotification {} of {}", currentStep, steps);

        if (timeRemaining != null) {
            notificationBuilder.setContentText(timeRemaining);
        }

        notificationBuilder.setProgress(steps, currentStep, false);

        if (notificationManagerCompat != null) {
            notificationManagerCompat.notify(BACKUP_NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private String getRemainingTimeText(int currentStep, int steps) {
        final long millisPassed = System.currentTimeMillis() - startTime;
        final long millisRemaining = millisPassed * steps / currentStep - millisPassed + FILE_SETTLE_DELAY;
        String timeRemaining = StringConversionUtil.secondsToString(millisRemaining / DateUtils.SECOND_IN_MILLIS, false);
        return String.format(getString(R.string.time_remaining), timeRemaining);
    }

    private void cancelPersistentNotification() {
        if (notificationManagerCompat != null) {
            notificationManagerCompat.cancel(BACKUP_NOTIFICATION_ID);
        }
    }

    @SuppressLint("MissingPermission")
    private void showBackupErrorNotification(String message) {
        String contentText;

        if (!TestUtil.isEmptyOrNull(message)) {
            contentText = message;
        } else {
            contentText = getString(R.string.backup_or_restore_error_body);
        }

        Intent backupIntent = new Intent(this, HomeActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, (int) System.currentTimeMillis(), backupIntent, PendingIntent.FLAG_UPDATE_CURRENT | PENDING_INTENT_FLAG_IMMUTABLE);

        NotificationCompat.Builder builder =
            new NotificationCompat.Builder(this, NotificationChannels.NOTIFICATION_CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_notification_small)
                .setTicker(getString(R.string.backup_or_restore_error_body))
                .setContentTitle(getString(R.string.backup_or_restore_error))
                .setContentText(contentText)
                .setContentIntent(pendingIntent)
                .setDefaults(Notification.DEFAULT_LIGHTS | Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setAutoCancel(false);

        if (notificationManagerCompat != null) {
            notificationManagerCompat.notify(BACKUP_COMPLETION_NOTIFICATION_ID, builder.build());
        } else {
            RuntimeUtil.runOnUiThread(
                () -> Toast.makeText(getApplicationContext(), R.string.backup_or_restore_error_body, Toast.LENGTH_LONG).show()
            );
        }
    }

    @SuppressLint({"ServiceCast", "MissingPermission"})
    private void showBackupSuccessNotification() {
        logger.debug("showBackupSuccess");

        String text;

        Intent backupIntent = new Intent(this, HomeActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, (int) System.currentTimeMillis(), backupIntent, PendingIntent.FLAG_UPDATE_CURRENT | PENDING_INTENT_FLAG_IMMUTABLE);

        NotificationCompat.Builder builder =
            new NotificationCompat.Builder(this, NotificationChannels.NOTIFICATION_CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_notification_small)
                .setTicker(getString(R.string.backup_or_restore_success_body))
                .setContentTitle(getString(R.string.app_name))
                .setContentIntent(pendingIntent)
                .setDefaults(Notification.DEFAULT_LIGHTS | Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            // Android Q does not allow restart in the background
            text = getString(R.string.backup_or_restore_success_body) + "\n" + getString(R.string.tap_to_start, getString(R.string.app_name));
        } else {
            text = getString(R.string.backup_or_restore_success_body);
        }

        builder.setContentText(text);
        builder.setStyle(new NotificationCompat.BigTextStyle().bigText(text));

        if (notificationManagerCompat == null) {
            notificationManagerCompat = NotificationManagerCompat.from(this);
        }

        notificationManagerCompat.notify(BACKUP_COMPLETION_NOTIFICATION_ID, builder.build());
    }

    /**
     * Show a fake notification before stopping service in order to prevent Context.startForegroundService() did not then call Service.startForeground() crash
     */
    private void safeStopSelf() {
        Notification notification = new NotificationCompat.Builder(this, NotificationChannels.NOTIFICATION_CHANNEL_BACKUP_RESTORE_IN_PROGRESS)
            .setContentTitle("")
            .setContentText("")
            .build();

        startForeground(notification);
        stopForeground(true);
        isRunning = false;

        // Send broadcast after isRunning has been set to false to indicate that there is no backup
        // in progress anymore
        LocalBroadcastManager.getInstance(ThreemaApplication.getAppContext())
            .sendBroadcast(new Intent(BACKUP_PROGRESS_INTENT)
                .putExtra(BACKUP_PROGRESS, 100)
                .putExtra(BACKUP_PROGRESS_STEPS, 100)
            );

        stopSelf();
    }

    /**
     * Return a string representation of the next value in randomIterator
     *
     * @return a 10 character string
     */
    @NonNull
    private String getFormattedUniqueId() {
        return String.format(Locale.US, "%010d", randomIterator.next());
    }
}


