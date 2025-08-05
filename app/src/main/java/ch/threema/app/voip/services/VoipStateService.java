/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2017-2025 Threema GmbH
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

package ch.threema.app.voip.services;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;

import org.slf4j.Logger;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.LocusIdCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.messagereceiver.ContactMessageReceiver;
import ch.threema.app.notifications.NotificationChannels;
import ch.threema.app.notifications.NotificationGroups;
import ch.threema.app.routines.UpdateFeatureLevelRoutine;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.LifetimeService;
import ch.threema.app.services.NotificationPreferenceService;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.ContactUtil;
import ch.threema.app.utils.DNDUtil;
import ch.threema.app.utils.IdUtil;
import ch.threema.app.utils.NameUtil;
import ch.threema.app.voip.CallState;
import ch.threema.app.voip.CallStateSnapshot;
import ch.threema.app.voip.Config;
import ch.threema.app.voip.activities.CallActivity;
import ch.threema.app.voip.managers.VoipListenerManager;
import ch.threema.app.voip.receivers.VoipMediaButtonReceiver;
import ch.threema.app.voip.util.VoipUtil;
import ch.threema.base.ThreemaException;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.protocol.csp.messages.voip.VoipCallAnswerData;
import ch.threema.domain.protocol.csp.messages.voip.VoipCallAnswerMessage;
import ch.threema.domain.protocol.csp.messages.voip.VoipCallHangupData;
import ch.threema.domain.protocol.csp.messages.voip.VoipCallHangupMessage;
import ch.threema.domain.protocol.csp.messages.voip.VoipCallOfferData;
import ch.threema.domain.protocol.csp.messages.voip.VoipCallOfferMessage;
import ch.threema.domain.protocol.csp.messages.voip.VoipCallRingingData;
import ch.threema.domain.protocol.csp.messages.voip.VoipCallRingingMessage;
import ch.threema.domain.protocol.csp.messages.voip.VoipICECandidatesData;
import ch.threema.domain.protocol.csp.messages.voip.VoipICECandidatesMessage;
import ch.threema.domain.protocol.csp.messages.voip.VoipMessage;
import ch.threema.domain.protocol.csp.messages.voip.features.VideoFeature;
import ch.threema.storage.models.ContactModel;
import java8.util.concurrent.CompletableFuture;

import static ch.threema.app.notifications.NotificationIDs.INCOMING_CALL_NOTIFICATION_ID;
import static ch.threema.app.ThreemaApplication.getAppContext;
import static ch.threema.app.utils.IntentDataUtil.PENDING_INTENT_FLAG_IMMUTABLE;
import static ch.threema.app.utils.IntentDataUtil.PENDING_INTENT_FLAG_MUTABLE;
import static ch.threema.app.voip.activities.CallActivity.EXTRA_ACCEPT_INCOMING_CALL;
import static ch.threema.app.voip.services.CallRejectWorkerKt.KEY_CALL_ID;
import static ch.threema.app.voip.services.CallRejectWorkerKt.KEY_CONTACT_IDENTITY;
import static ch.threema.app.voip.services.CallRejectWorkerKt.KEY_REJECT_REASON;
import static ch.threema.app.voip.services.VoipCallService.ACTION_ICE_CANDIDATES;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_ACTIVITY_MODE;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_CALL_ID;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_CANCEL_WEAR;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_CANDIDATES;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_CONTACT_IDENTITY;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_IS_INITIATOR;

/**
 * The service keeping track of VoIP call state.
 * <p>
 * This class is (intended to be) thread safe.
 */
@AnyThread
public class VoipStateService implements AudioManager.OnAudioFocusChangeListener {
    private static final Logger logger = LoggingUtil.getThreemaLogger("VoipStateService");
    private final static String LIFETIME_SERVICE_TAG = "VoipStateService";

    public static final int VIDEO_RENDER_FLAG_NONE = 0x00;
    public static final int VIDEO_RENDER_FLAG_INCOMING = 0x01;
    public static final int VIDEO_RENDER_FLAG_OUTGOING = 0x02;

    // system managers
    private final AudioManager audioManager;
    private final NotificationManagerCompat notificationManagerCompat;

    // Threema services
    private final ContactService contactService;
    private final NotificationPreferenceService notificationPreferenceService;
    private final LifetimeService lifetimeService;

    // App context
    private final Context appContext;

    // State
    private volatile Boolean initiator = null;
    private final CallState callState = new CallState();
    private Long callStartTimestamp = null;
    private boolean isPeerRinging = false;
    private final List<Long> messageIds = new ArrayList<>();

    // Map that stores incoming offers
    private final HashMap<Long, VoipCallOfferData> offerMap = new HashMap<>();

    // Flag for designating current user configuration
    private int videoRenderMode = VIDEO_RENDER_FLAG_NONE;

    // Candidate cache
    private final Map<String, List<VoipICECandidatesData>> candidatesCache;

    // Call cache
    private final Set<Long> recentCallIds = new HashSet<>();

    // Notifications
    private final List<String> callNotificationTags = new ArrayList<>();

    // Video
    private @Nullable VideoContext videoContext;
    private @NonNull CompletableFuture<VideoContext> videoContextFuture = new CompletableFuture<>();

    // Pending intents
    private @Nullable PendingIntent acceptIntent;
    private final @Nullable PendingIntent mediaButtonPendingIntent;

    // Connection status
    private boolean connectionAcquired = false;

    // Timeouts
    private static final int RINGING_TIMEOUT_SECONDS = 60;
    private static final int VOIP_CONNECTION_LINGER = 1000 * 5;

    private final AtomicBoolean timeoutReject = new AtomicBoolean(true);

    public VoipStateService(
            ContactService contactService,
            NotificationPreferenceService notificationPreferenceService,
            LifetimeService lifetimeService,
            final Context appContext
    ) {
        this.contactService = contactService;
        this.notificationPreferenceService = notificationPreferenceService;
        this.lifetimeService = lifetimeService;
        this.appContext = appContext;
        this.candidatesCache = new HashMap<>();
        this.notificationManagerCompat = NotificationManagerCompat.from(appContext);
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setClass(appContext, VoipMediaButtonReceiver.class);
        this.mediaButtonPendingIntent = PendingIntent.getBroadcast(appContext, 0, mediaButtonIntent, PENDING_INTENT_FLAG_MUTABLE);
    }

    //region Logging

    // Note: Because the VoipStateService is not tied to a single call ID, we need to specify
    //       the call ID for every logging call. These helper methods provide some boilerplate
    //       code to make this easier.

    private static void logCallTrace(long callId, String message) {
        logger.trace("[cid={}]: {}", callId, message);
    }

    private static void logCallTrace(long callId, @NonNull String message, Object... arguments) {
        logger.trace("[cid=" + callId + "]: " + message, arguments);
    }

    private static void logCallDebug(long callId, String message) {
        logger.debug("[cid={}]: {}", callId, message);
    }

    private static void logCallDebug(long callId, @NonNull String message, Object... arguments) {
        logger.debug("[cid=" + callId + "]: " + message, arguments);
    }

    private static void logCallInfo(long callId, String message) {
        logger.info("[cid={}]: {}", callId, message);
    }

    private static void logCallInfo(long callId, @NonNull String message, Object... arguments) {
        logger.info("[cid=" + callId + "]: " + message, arguments);
    }

    private static void logCallWarning(long callId, String message) {
        logger.warn("[cid={}]: {}", callId, message);
    }

    private static void logCallWarning(long callId, @NonNull String message, Object... arguments) {
        logger.warn("[cid=" + callId + "]: " + message, arguments);
    }

    private static void logCallError(long callId, String message) {
        logger.error("[cid={}]: {}", callId, message);
    }

    private static void logCallError(long callId, String message, Throwable t) {
        logger.error("[cid=" + callId + "]: " + message, t);
    }

    private static void logCallError(long callId, @NonNull String message, Object... arguments) {
        logger.error("[cid=" + callId + "]: " + message, arguments);
    }

    //endregion

    //region State transitions

    /**
     * Get the current call state as an immutable snapshot.
     * <p>
     * Note: Does not require locking, since the {@link CallState}
     * class is thread safe.
     */
    public CallStateSnapshot getCallState() {
        return this.callState.getStateSnapshot();
    }

    /**
     * Called for every state transition.
     * <p>
     * Note: Most reactions to state changes should be done in the `setStateXXX` methods.
     * This method should only be used for actions that apply to multiple state transitions.
     *
     * @param oldState The previous call state.
     * @param newState The new call state.
     */
    private void onStateChange(
        @NonNull CallStateSnapshot oldState,
        @NonNull CallStateSnapshot newState
    ) {
        logger.info("Call state change from {} to {}", oldState.getName(), newState.getName());
        logger.debug(
            "  State{{},id={},counter={}} → State{{},id={},counter={}}",
            oldState.getName(), oldState.getCallId(), oldState.getIncomingCallCounter(),
            newState.getName(), newState.getCallId(), newState.getIncomingCallCounter()
        );

        // As soon as the callers state changes from initializing to another state, the callee is
        // not ringing anymore
        if (oldState.isInitializing()) {
            isPeerRinging = false;
        }

        // Clear pending accept intent
        if (!newState.isRinging()) {
            this.acceptIntent = null;
        }

        // Ensure bluetooth media button receiver is registered when a call starts
        if (newState.isRinging() || newState.isInitializing()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.registerMediaButtonEventReceiver(mediaButtonPendingIntent);
            } else {
                audioManager.registerMediaButtonEventReceiver(new ComponentName(appContext, VoipMediaButtonReceiver.class));
            }
        }

        // Ensure bluetooth media button receiver is deregistered when a call ends
        if (newState.isDisconnecting() || newState.isIdle()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.unregisterMediaButtonEventReceiver(mediaButtonPendingIntent);
            } else {
                audioManager.unregisterMediaButtonEventReceiver(new ComponentName(appContext, VoipMediaButtonReceiver.class));
            }
            synchronized (this) {
                messageIds.clear();
            }
        }

        long callId = oldState.getCallId();
        if (callId != 0L) {
            recentCallIds.add(callId);
        }

        // Enable rejecting calls after a timeout
        enableTimeoutReject();
    }

    /**
     * Set the current call state to RINGING.
     */
    public synchronized void setStateRinging(long callId) {
        if (this.callState.isRinging()) {
            return;
        }

        // Transition call state
        final CallStateSnapshot prevState = this.callState.getStateSnapshot();
        this.callState.setRinging(callId);
        this.onStateChange(prevState, this.callState.getStateSnapshot());
    }

    /**
     * Set the current call state to INITIALIZING.
     */
    public synchronized void setStateInitializing(long callId) {
        if (this.callState.isInitializing()) {
            return;
        }

        // Transition call state
        final CallStateSnapshot prevState = this.callState.getStateSnapshot();
        this.callState.setInitializing(callId);
        this.onStateChange(prevState, this.callState.getStateSnapshot());

        // Make sure connection is open
        if (!this.connectionAcquired) {
            this.lifetimeService.acquireUnpauseableConnection(LIFETIME_SERVICE_TAG);
            this.connectionAcquired = true;
        }

        // Send cached candidates and clear cache
        synchronized (this.candidatesCache) {
            logCallInfo(callId, "Processing cached candidates for {} ID(s)", this.candidatesCache.size());

            // Note: We're sending all cached candidates. The broadcast receiver
            // is responsible for dropping the ones that aren't of interest.
            for (Map.Entry<String, List<VoipICECandidatesData>> entry : this.candidatesCache.entrySet()) {
                logCallInfo(
                    callId,
                    "Broadcasting {} candidates data messages from {}",
                    entry.getValue().size(), entry.getKey()
                );
                for (VoipICECandidatesData data : entry.getValue()) {
                    // Broadcast candidates
                    Intent intent = new Intent();
                    intent.setAction(ACTION_ICE_CANDIDATES);
                    intent.putExtra(EXTRA_CALL_ID, data.getCallIdOrDefault(0L));
                    intent.putExtra(EXTRA_CONTACT_IDENTITY, entry.getKey());
                    intent.putExtra(EXTRA_CANDIDATES, data);
                    LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
                }
            }
            this.clearCandidatesCache();
        }
    }

    /**
     * Set the current call state to CALLING.
     */
    public synchronized void setStateCalling(long callId) {
        if (this.callState.isCalling()) {
            return;
        }

        // Transition call state
        final CallStateSnapshot prevState = this.callState.getStateSnapshot();
        this.callState.setCalling(callId);
        this.onStateChange(prevState, this.callState.getStateSnapshot());

        // Record the start timestamp of the call.
        // The SystemClock.elapsedRealtime function (returning milliseconds)
        // is guaranteed to be monotonic.
        this.callStartTimestamp = SystemClock.elapsedRealtime();
    }

    /**
     * Set the current call state to DISCONNECTING.
     */
    public synchronized void setStateDisconnecting(long callId) {
        if (this.callState.isDisconnecting()) {
            return;
        }

        // Transition call state
        final CallStateSnapshot prevState = this.callState.getStateSnapshot();
        this.callState.setDisconnecting(callId);
        this.onStateChange(prevState, this.callState.getStateSnapshot());

        // Reset start timestamp
        this.callStartTimestamp = null;

        // Clear the candidates cache
        this.clearCandidatesCache();
    }

    /**
     * Set the current call state to IDLE.
     */
    public synchronized void setStateIdle() {
        if (this.callState.isIdle()) {
            return;
        }

        // Transition call state
        final CallStateSnapshot prevState = this.callState.getStateSnapshot();
        this.callState.setIdle();
        this.onStateChange(prevState, this.callState.getStateSnapshot());

        // Reset start timestamp
        this.callStartTimestamp = null;

        // Reset initiator flag
        this.initiator = null;

        // Remove offer data
        long callId = prevState.getCallId();
        logger.debug("Removing information for call {} from offerMap", callId);
        this.offerMap.remove(callId);

        // Release Threema connection
        if (this.connectionAcquired) {
            this.lifetimeService.releaseConnectionLinger(LIFETIME_SERVICE_TAG, VOIP_CONNECTION_LINGER);
            this.connectionAcquired = false;
        }
    }

    /**
     * Set the current state of the peer device regarding ringing.
     *
     * @param isPeerRinging the current peer ringing state
     */
    public void setPeerRinging(boolean isPeerRinging) {
        this.isPeerRinging = isPeerRinging;
    }

    /**
     * Check whether the peer device is currently ringing. This function returns {@code true} from
     * the time the other device rings until the call state changes on this device.
     *
     * @return {@code true} if the other device is ringing, {@code false} otherwise
     */
    public boolean isPeerRinging() {
        return this.isPeerRinging;
    }

    //endregion

    /**
     * Return whether the VoIP service is currently initialized as initiator or responder.
     * <p>
     * Note: This is only initialized once a call is being set up. That means that the flag
     * will be `null` when a call is ringing, but hasn't been accepted yet.
     */
    @Nullable
    public Boolean isInitiator() {
        return this.initiator;
    }

    /**
     * Return whether the VoIP service is currently initialized as initiator or responder.
     */
    public void setInitiator(boolean isInitiator) {
        this.initiator = isInitiator;
    }

    /**
     * Create a new accept intent for the specified call ID / identity.
     */
    public static Intent createAcceptIntent(long callId, @NonNull String identity) {
        final Intent intent = new Intent(getAppContext(), CallActivity.class);
        intent.putExtra(EXTRA_CALL_ID, callId);
        intent.putExtra(EXTRA_CONTACT_IDENTITY, identity);
        intent.putExtra(EXTRA_IS_INITIATOR, false);
        intent.putExtra(EXTRA_ACCEPT_INCOMING_CALL, true);
        intent.putExtra(EXTRA_ACTIVITY_MODE, CallActivity.MODE_ACTIVE_CALL);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Create a new reject intent for the specified call ID / identity.
     */
    private static Intent createRejectIntent(long callId, @NonNull String identity) {
        final Intent intent = new Intent(getAppContext(), CallRejectService.class);
        intent.putExtra(EXTRA_CALL_ID, callId);
        intent.putExtra(EXTRA_CONTACT_IDENTITY, identity);
        intent.putExtra(EXTRA_IS_INITIATOR, false);
        intent.putExtra(CallRejectService.EXTRA_REJECT_REASON, VoipCallAnswerData.RejectReason.REJECTED);
        return intent;
    }

    /**
     * Creates a reject work request builder.
     *
     * @param callId       the call id of the call to be rejected
     * @param identity     the contact identity of the call partner
     * @param rejectReason the reject reason
     * @return a work request builder
     */
    private static OneTimeWorkRequest.Builder createRejectWorkRequestBuilder(long callId, @NonNull String identity, byte rejectReason) {
        return new OneTimeWorkRequest.Builder(RejectIntentServiceWorker.class)
            .setInputData(new Data.Builder()
                .putLong(KEY_CALL_ID, callId)
                .putString(KEY_CONTACT_IDENTITY, identity)
                .putByte(KEY_REJECT_REASON, rejectReason
                ).build()
            );
    }

    /**
     * Validate offer data, return true if it's valid.
     */
    private boolean validateOfferData(@Nullable VoipCallOfferData.OfferData offer) {
        if (offer == null) {
            logger.error("Offer data is null");
            return false;
        }
        final String sdpType = offer.getSdpType();
        if (sdpType == null || !sdpType.equals("offer")) {
            logger.error("Offer data is invalid: Sdp type is {}, not offer", sdpType);
            return false;
        }
        final String sdp = offer.getSdp();
        if (sdp == null) {
            logger.error("Offer data is invalid: Sdp is null");
            return false;
        }
        return true;
    }

    /**
     * Return the {@link VoipCallOfferData} associated with this Call ID (if any).
     */
    public @Nullable VoipCallOfferData getCallOffer(long callId) {
        return this.offerMap.get(callId);
    }

    //region Handle call messages

    /**
     * Handle a reject message that has been received. Check whether this message id belongs to a
     * message regarding the current call. If there is no running call or the message id is
     * unrelated to it, we do not need to do anything.
     *
     * @param messageId the message id of the rejected message
     */
    public synchronized void handlePotentialCallMessageReject(@NonNull MessageId messageId) {
        if (!(callState.isIdle() || callState.isDisconnecting()) && messageIds.contains(messageId.getMessageIdLong())) {
            logCallWarning(callState.getCallId(), "Message involved in this call has been rejected. Aborting call.");
            VoipUtil.sendVoipCommand(getAppContext(), VoipCallService.class, VoipCallService.ACTION_HANGUP);
        }
    }

    /**
     * Store the message id of a required message of this call. This is needed in case the message
     * is rejected. Note that only message ids of messages that should lead to abortion of the call
     * should be added here. Message ids where the call id is not equal to the current call are
     * discarded.
     *
     * @param callId    the call id where the message with the given message id belongs to
     * @param messageId the message id that should lead
     */
    public synchronized void addRequiredMessageId(long callId, @NonNull MessageId messageId) {
        if (isCallIdValid(callId)) {
            messageIds.add(messageId.getMessageIdLong());
        }
    }

    /**
     * Handle an incoming VoipCallOfferMessage.
     *
     * @return true if messages was successfully processed
     */
    @WorkerThread
    public synchronized boolean handleCallOffer(@NonNull final VoipCallOfferMessage voipCallOfferMessage) {
        // Unwrap data
        final String callerIdentity = voipCallOfferMessage.getFromIdentity();
        final VoipCallOfferData callOfferData = voipCallOfferMessage.getData();
        if (callOfferData == null) {
            logger.warn("Call offer received from {}. Data is null, ignoring.", callerIdentity);
            return false;
        }
        final long callId = callOfferData.getCallIdOrDefault(0L);
        logCallInfo(
            callId,
            "Call offer received from {} (Features: {})",
            callerIdentity, callOfferData.getFeatures()
        );
        logCallInfo(callId, "{}", callOfferData.getOfferData());

        // Get contact and receiver
        final ContactModel contact = this.contactService.getByIdentity(callerIdentity);
        if (contact == null) {
            logCallError(callId, "Could not fetch contact for identity {}", callerIdentity);
            return false;
        }

        // Handle some reasons for rejecting calls...
        Byte rejectReason = null; // Set to non-null in order to reject the call
        boolean silentReject = false; // Set to true if you don't want a "missed call" chat message
        if (!ConfigUtils.isCallsEnabled()) {
            // Calls disabled
            logCallInfo(callId, "Rejecting call from {} (disabled)", contact.getIdentity());
            rejectReason = VoipCallAnswerData.RejectReason.DISABLED;
            silentReject = true;
        } else if (!this.validateOfferData(callOfferData.getOfferData())) {
            // Offer invalid
            logCallWarning(callId, "Rejecting call from {} (invalid offer)", contact.getIdentity());
            rejectReason = VoipCallAnswerData.RejectReason.UNKNOWN;
            silentReject = true;
        } else if (!this.callState.isIdle()) {
            // Another call is already active
            logCallInfo(callId, "Rejecting call from {} (busy)", contact.getIdentity());
            rejectReason = VoipCallAnswerData.RejectReason.BUSY;
        } else if (VoipUtil.isPSTNCallOngoing(this.appContext)) {
            // A PSTN call is ongoing
            logCallInfo(callId, "Rejecting call from {} (PSTN call ongoing)", contact.getIdentity());
            rejectReason = VoipCallAnswerData.RejectReason.BUSY;
        } else if (DNDUtil.getInstance().isMutedWork()) {
            // Called outside working hours
            logCallInfo(callId, "Rejecting call from {} (called outside of working hours)", contact.getIdentity());
            rejectReason = VoipCallAnswerData.RejectReason.OFF_HOURS;
        } else if (ConfigUtils.hasInvalidCredentials()) {
            logCallInfo(callId, "Rejecting call from {} (credentials have been revoked)", contact.getIdentity());
            rejectReason = VoipCallAnswerData.RejectReason.UNKNOWN;
        }

        if (rejectReason != null) {
            try {
                this.sendRejectCallAnswerMessage(contact, callId, rejectReason, !silentReject);
            } catch (ThreemaException e) {
                logger.error(callId + ": Could not send reject call message", e);
            }
            return true;
        }

        // Prefetch TURN servers
        Config.getTurnServerCache().prefetchTurnServers();

        // Reset fetch cache
        UpdateFeatureLevelRoutine.removeTimeCache(contact.getIdentity());

        // Store offer in offer map
        logger.debug("Adding information for call {} to offerMap", callId);
        this.offerMap.put(callId, callOfferData);

        // If the call is accepted, let VoipCallService know
        // and set flag to cancel on watch to true as this call flow is initiated and handled from the Phone
        final Intent answerIntent = createAcceptIntent(callId, callerIdentity);
        Bundle bundle = new Bundle();
        bundle.putBoolean(EXTRA_CANCEL_WEAR, true);
        answerIntent.putExtras(bundle);
        final PendingIntent accept = PendingIntent.getActivity(
            this.appContext,
            -IdUtil.getTempId(contact),
            answerIntent,
            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT | PENDING_INTENT_FLAG_IMMUTABLE
        );
        this.acceptIntent = accept;

        // If the call is rejected, start the CallRejectService
        final Intent rejectIntent = createRejectIntent(
            callId,
            callerIdentity
        );

        final PendingIntent reject = PendingIntent.getService(
            this.appContext,
            -IdUtil.getTempId(contact),
            rejectIntent,
            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT | PENDING_INTENT_FLAG_IMMUTABLE);

        final ContactMessageReceiver messageReceiver = this.contactService.createReceiver(contact);

        // Set state to RINGING
        this.setStateRinging(callId);

        // Show call notification
        showNotification(contact, accept, reject, voipCallOfferMessage);

        // Update conversation timestamp
        if (voipCallOfferMessage.bumpLastUpdate()) {
            contactService.bumpLastUpdate(callerIdentity);
        }

        // Send "ringing" message to caller
        try {
            this.sendCallRingingMessage(contact, callId);
        } catch (ThreemaException e) {
            logger.error(callId + ": Could not send ringing message", e);
        }

        // Reject the call after a while
        OneTimeWorkRequest rejectWork = createRejectWorkRequestBuilder(callId, callerIdentity, VoipCallAnswerData.RejectReason.TIMEOUT)
            .setInitialDelay(RINGING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();
        WorkManager.getInstance(appContext).enqueue(rejectWork);

        // Notify listeners
        VoipListenerManager.messageListener.handle(listener -> {
            if (listener.handle(callerIdentity)) {
                listener.onOffer(callerIdentity, voipCallOfferMessage.getData());
            }
        });
        VoipListenerManager.callEventListener.handle(listener -> listener.onRinging(callerIdentity));

        return true;
    }

    /**
     * Handle an incoming VoipCallAnswerMessage.
     *
     * @return true if messages was successfully processed
     */
    @WorkerThread
    public synchronized boolean handleCallAnswer(final VoipCallAnswerMessage msg) {
        final VoipCallAnswerData callAnswerData = msg.getData();
        if (callAnswerData != null) {
            // Validate Call ID
            final long callId = callAnswerData.getCallIdOrDefault(0L);
            if (!this.isCallIdValid(callId)) {
                logger.info(
                    "Call answer received for an invalid call ID ({}, local={}), ignoring",
                    callId, this.callState.getCallId()
                );
                return true;
            }

            // Ensure that an answer wasn't already received
            if (this.callState.answerReceived()) {
                logCallWarning(callId, "Received extra answer, ignoring");
                return true;
            }

            // Ensure that action was set
            if (callAnswerData.getAction() == null) {
                logCallWarning(callId, "Call answer received without action, ignoring");
                return true;
            }

            switch (callAnswerData.getAction()) {
                // Call was accepted
                case VoipCallAnswerData.Action.ACCEPT:
                    logCallInfo(callId, "Call answer received from {}: accept", msg.getFromIdentity());
                    logCallInfo(callId, "Answer features: {}", callAnswerData.getFeatures());
                    logCallInfo(callId, "Answer data: {}", callAnswerData.getAnswerData());
                    VoipUtil.sendVoipBroadcast(this.appContext, CallActivity.ACTION_CALL_ACCEPTED);
                    break;

                // Call was rejected
                case VoipCallAnswerData.Action.REJECT:
                    // TODO(ANDR-XXXX): only for tests!
                    VoipListenerManager.callEventListener.handle(listener -> {
                        listener.onRejected(callId, msg.getFromIdentity(), false, callAnswerData.getRejectReason());
                    });
                    logCallInfo(callId, "Call answer received from {}: reject/{}",
                        msg.getFromIdentity(), callAnswerData.getRejectReasonName());
                    break;

                default:
                    logCallInfo(callId, "Call answer received from {}: Unknown action: {}", callAnswerData.getAction());
                    break;
            }

            // Mark answer as received
            this.callState.setAnswerReceived();

            // Notify listeners
            VoipListenerManager.messageListener.handle(listener -> {
                final String identity = msg.getFromIdentity();
                if (listener.handle(identity)) {
                    listener.onAnswer(identity, callAnswerData);
                }
            });
        }

        return true;
    }

    /**
     * Handle an incoming VoipICECandidatesMessage.
     *
     * @return true if messages was successfully processed
     */
    @WorkerThread
    public synchronized boolean handleICECandidates(@NonNull final VoipICECandidatesMessage voipICECandidatesMessage) {
        // Unwrap data
        final VoipICECandidatesData candidatesData = voipICECandidatesMessage.getData();
        if (candidatesData == null) {
            logger.warn("Call ICE candidate message received from {}. Data is null, ignoring", voipICECandidatesMessage.getFromIdentity());
            return false;
        }
        if (candidatesData.getCandidates() == null) {
            logger.warn("Call ICE candidate message received from {}. Candidates are null, ignoring", voipICECandidatesMessage.getFromIdentity());
            return false;
        }

        // Validate Call ID
        final long callId = candidatesData.getCallIdOrDefault(0L);
        if (!this.isCallIdValid(callId)) {
            logger.info(
                "Call ICE candidate message received from {} for an invalid Call ID ({}, local={}), ignoring",
                voipICECandidatesMessage.getFromIdentity(), callId, this.callState.getCallId()
            );
            return false;
        }

        // The "removed" flag is deprecated, see ANDR-1145 / SE-66
        if (candidatesData.isRemoved()) {
            logCallInfo(callId, "Call ICE candidate message received from {} with removed=true, ignoring");
            return false;
        }

        logCallInfo(
            callId,
            "Call ICE candidate message received from {} ({} candidates)",
            voipICECandidatesMessage.getFromIdentity(), candidatesData.getCandidates().length
        );
        for (VoipICECandidatesData.Candidate candidate : candidatesData.getCandidates()) {
            logCallInfo(callId, "  Incoming ICE candidate: {}", candidate.getCandidate());
        }

        // Handle candidates depending on state
        if (this.callState.isIdle() || this.callState.isRinging()) {
            // If the call hasn't been started yet, cache the candidate(s)
            this.cacheCandidate(voipICECandidatesMessage.getFromIdentity(), candidatesData);
        } else if (this.callState.isInitializing() || this.callState.isCalling()) {
            // Otherwise, send candidate(s) directly to call service via broadcast
            Intent intent = new Intent();
            intent.setAction(ACTION_ICE_CANDIDATES);
            intent.putExtra(EXTRA_CALL_ID, voipICECandidatesMessage.getData().getCallIdOrDefault(0L));
            intent.putExtra(EXTRA_CONTACT_IDENTITY, voipICECandidatesMessage.getFromIdentity());
            intent.putExtra(EXTRA_CANDIDATES, candidatesData);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        } else {
            logCallWarning(callId, "Received ICE candidates in invalid call state ({})", this.callState);
            return false;
        }
        return true;
    }

    /**
     * Handle incoming Call Ringing message
     *
     * @return true if message was successfully processed
     */
    @WorkerThread
    public synchronized boolean handleCallRinging(final VoipCallRingingMessage msg) {
        final CallStateSnapshot state = this.callState.getStateSnapshot();

        // Validate Call ID
        //
        // NOTE: Ringing messages from older Threema versions may not have any associated data!
        final long callId = msg.getData() == null
            ? 0L
            : msg.getData().getCallIdOrDefault(0L);
        if (!this.isCallIdValid(callId)) {
            logger.info(
                "Call ringing message received from {} for an invalid Call ID ({}, local={}), ignoring",
                msg.getFromIdentity(), callId, state.getCallId()
            );
            return true;
        }

        logCallInfo(callId, "Call ringing message received from {}", msg.getFromIdentity());

        // Check whether we're in the correct state for a ringing message
        if (!state.isInitializing()) {
            logCallWarning(
                callId,
                "Call ringing message from {} ignored, call state is {}",
                msg.getFromIdentity(), state.getName()
            );
            return true;
        }

        // Notify listeners
        VoipListenerManager.messageListener.handle(listener -> {
            final String identity = msg.getFromIdentity();
            if (listener.handle(identity)) {
                listener.onRinging(identity, msg.getData());
            }
        });

        return true;
    }

    /**
     * Handle remote call hangup messages.
     * A hangup can happen either before or during a call.
     *
     * @return true if message was successfully processed
     */
    @WorkerThread
    public synchronized boolean handleRemoteCallHangup(@NonNull final VoipCallHangupMessage voipCallHangupMessage) {
        // Validate Call ID
        //
        // NOTE: Hangup messages from older Threema versions may not have any associated data!
        // NOTE: If a remote hangup message arrives with an invalid call id that does not appear
        // in the call history, it is a missed call
        final long callId = voipCallHangupMessage.getData() == null
            ? 0L
            : voipCallHangupMessage.getData().getCallIdOrDefault(0L);
        if (!this.isCallIdValid(callId)) {
            if (isMissedCall(voipCallHangupMessage, callId)) {
                handleMissedCall(voipCallHangupMessage, callId, false);
                return true;
            }
            logger.info(
                "Call hangup message received from {} for an invalid Call ID ({}, local={}), ignoring",
                voipCallHangupMessage.getFromIdentity(), callId, this.callState.getCallId()
            );
            return false;
        }

        logCallInfo(callId, "Call hangup message received from {}", voipCallHangupMessage.getFromIdentity());

        final String identity = voipCallHangupMessage.getFromIdentity();

        final CallStateSnapshot prevState = this.callState.getStateSnapshot();
        final Integer duration = getCallDuration();

        // Detect whether this is an incoming or outgoing call.
        //
        // NOTE: When a call hasn't been accepted yet, the `isInitiator` flag is not yet set.
        //       however, in that case we can be sure that it's an incoming call.
        final boolean incoming = this.isInitiator() != Boolean.TRUE;

        // Reset state
        this.setStateIdle();

        // Cancel call notification for that person
        this.cancelCallNotification(voipCallHangupMessage.getFromIdentity(), CallActivity.ACTION_DISCONNECTED);

        // Notify listeners
        VoipListenerManager.messageListener.handle(listener -> {
            if (listener.handle(identity)) {
                listener.onHangup(identity, voipCallHangupMessage.getData());
            }
        });
        if (incoming && (prevState.isIdle() || prevState.isRinging() || prevState.isInitializing())) {
            final boolean accepted = prevState.isInitializing();
            handleMissedCall(voipCallHangupMessage, callId, accepted);
        } else if (prevState.isCalling() && duration != null) {
            VoipListenerManager.callEventListener.handle(listener -> {
                listener.onFinished(callId, voipCallHangupMessage.getFromIdentity(), !incoming, duration);
            });
        }

        return true;
    }

    /**
     * Handle a missed call.
     *
     * @param msg      the hangup message of the missed call
     * @param callId   the call id of the missed call
     * @param accepted whether the call was already accepted (and initializing) or not
     */
    private void handleMissedCall(
        @NonNull final VoipCallHangupMessage msg,
        final long callId,
        boolean accepted
    ) {
        logger.info("Missed call received from {} with call id {}", msg.getFromIdentity(), callId);
        VoipListenerManager.callEventListener.handle(
            listener -> listener.onMissed(callId, msg.getFromIdentity(), accepted, msg.getDate())
        );

        // Update conversation timestamp
        contactService.bumpLastUpdate(msg.getFromIdentity());
    }

    //endregion

    /**
     * Return whether the specified call ID belongs to the current call.
     * <p>
     * NOTE: Do not use this method to validate the call ID in an offer,
     * that doesn't make sense :)
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private synchronized boolean isCallIdValid(long callId) {
        // If the passed in Call ID matches the current Call ID, everything is fine
        final long currentCallId = this.callState.getCallId();
        if (callId == currentCallId) {
            return true;
        }

        // ANDR-1140: If we are the initiator, then we will have initialized the call ID to a
        // random value. If however the remote device does not yet support call IDs, then returned
        // messages will not contain a Call ID. Accept the messages anyways.
        final boolean isInitiator = this.isInitiator() == Boolean.TRUE;
        return isInitiator && callId == 0L;
    }

    /**
     * Check whether this hangup voip message is a missed call.
     *
     * @param msg    the received voip message that is checked for a missed call
     * @param callId the call id
     * @return {@code true} if it is a missed call, {@code false} otherwise
     */
    public boolean isMissedCall(VoipMessage msg, long callId) {
        if (recentCallIds.contains(callId)) {
            logger.info("No missed call: call id {} is contained in recent call ids", callId);
            return false;
        }
        // Limit the check to the last 4 calls. Note that only call status messages with the
        // contact of this hangup message are considered.
        if (contactService.createReceiver(contactService.getByIdentity(msg.getFromIdentity())).hasVoipCallStatus(callId, 4)) {
            logger.info("No missed call: call id {} found in database", callId);
            return false;
        }

        return true;
    }


    /**
     * Send a call offer to the specified contact.
     *
     * @param videoCall Whether to enable video calls in this offer.
     * @throws ThreemaException         if enqueuing the message fails.
     * @throws IllegalArgumentException if the session description is not valid for an offer message.
     * @throws IllegalStateException    if the call state is not INITIALIZING
     */
    public synchronized void sendCallOfferMessage(
        @NonNull ContactModel receiver,
        final long callId,
        @NonNull SessionDescription sessionDescription,
        boolean videoCall
    ) throws ThreemaException, IllegalArgumentException, IllegalStateException {
        switch (sessionDescription.type) {
            case OFFER:
                // OK
                break;
            case ANSWER:
            case PRANSWER:
                throw new IllegalArgumentException("A " + sessionDescription.type +
                    " session description is not valid for an offer message");
        }

        final CallStateSnapshot state = this.callState.getStateSnapshot();
        if (!state.isInitializing()) {
            throw new IllegalStateException("Called sendCallOfferMessage in state " + state.getName());
        }

        // Send call offer message
        final VoipCallOfferData callOfferData = new VoipCallOfferData()
            .setCallId(callId)
            .setOfferData(
                new VoipCallOfferData.OfferData()
                    .setSdpType(sessionDescription.type.canonicalForm())
                    .setSdp(sessionDescription.description)
            );
        if (videoCall) {
            callOfferData.addFeature(new VideoFeature());
        }
        contactService.createReceiver(receiver).sendVoipCallOfferMessage(callOfferData);

        logCallInfo(callId, "Call offer enqueued to {}", receiver.getIdentity());
        logCallInfo(callId, "  Offer features: {}", callOfferData.getFeatures());
        logCallInfo(callId, "  Offer data: {}", callOfferData.getOfferData());
    }

    //region Send call messages

    /**
     * Accept a call from the specified contact.
     *
     * @throws ThreemaException         if enqueuing the message fails.
     * @throws IllegalArgumentException if the session description is not valid for an offer message.
     */
    public void sendAcceptCallAnswerMessage(
        @NonNull ContactModel receiver,
        final long callId,
        @NonNull SessionDescription sessionDescription,
        boolean videoCall
    ) throws ThreemaException, IllegalArgumentException {
        this.sendCallAnswerMessage(
            receiver,
            callId,
            sessionDescription,
            VoipCallAnswerData.Action.ACCEPT,
            null,
            videoCall
        );
    }

    /**
     * Reject a call from the specified contact.
     *
     * @throws ThreemaException if enqueuing the message fails.
     */
    public void sendRejectCallAnswerMessage(
        final @NonNull ContactModel receiver,
        final long callId,
        byte reason
    ) throws ThreemaException, IllegalArgumentException {
        this.sendRejectCallAnswerMessage(receiver, callId, reason, true);
    }

    /**
     * Reject a call from the specified contact.
     *
     * @throws ThreemaException if enqueuing the message fails.
     */
    public void sendRejectCallAnswerMessage(
        final @NonNull ContactModel receiver,
        final long callId,
        byte reason,
        boolean notifyListeners
    ) throws ThreemaException, IllegalArgumentException {
        logCallInfo(callId, "Sending reject call answer message (reason={})", reason);
        this.sendCallAnswerMessage(receiver, callId, null, VoipCallAnswerData.Action.REJECT, reason, null);

        // Notify listeners
        if (notifyListeners) {
            logCallInfo(callId, "Notifying listeners about call rejection");
            VoipListenerManager.callEventListener.handle(listener -> {
                switch (reason) {
                    case VoipCallAnswerData.RejectReason.BUSY:
                    case VoipCallAnswerData.RejectReason.TIMEOUT:
                    case VoipCallAnswerData.RejectReason.OFF_HOURS:
                        listener.onMissed(callId, receiver.getIdentity(), false, null);
                        break;
                    default:
                        listener.onRejected(callId, receiver.getIdentity(), true, reason);
                        break;
                }
            });
        }
    }

    /**
     * Send a call answer method.
     *
     * @param videoCall If set to TRUE, then the `video` call feature
     *                  will be sent along in the answer.
     * @throws ThreemaException
     * @throws IllegalArgumentException
     * @throws IllegalStateException
     */
    private void sendCallAnswerMessage(
        @NonNull ContactModel receiver,
        final long callId,
        @Nullable SessionDescription sessionDescription,
        byte action,
        @Nullable Byte rejectReason,
        @Nullable Boolean videoCall
    ) throws ThreemaException, IllegalArgumentException, IllegalStateException {
        logCallInfo(callId, "Sending call answer message");
        final VoipCallAnswerData callAnswerData = new VoipCallAnswerData()
            .setCallId(callId)
            .setAction(action);

        if (action == VoipCallAnswerData.Action.ACCEPT && sessionDescription != null) {
            switch (sessionDescription.type) {
                case ANSWER:
                case PRANSWER:
                    // OK
                    break;
                case OFFER:
                    throw new IllegalArgumentException("A " + sessionDescription.type +
                        " session description is not valid for an answer message");
            }

            callAnswerData.setAnswerData(
                new VoipCallAnswerData.AnswerData()
                    .setSdpType(sessionDescription.type.canonicalForm())
                    .setSdp(sessionDescription.description)
            );

            if (Boolean.TRUE.equals(videoCall)) {
                callAnswerData.addFeature(new VideoFeature());
            }
        } else if (action == VoipCallAnswerData.Action.REJECT && rejectReason != null) {
            callAnswerData.setRejectReason(rejectReason);
        } else {
            throw new IllegalArgumentException("Invalid action, missing session description or missing reject reason");
        }

        contactService.createReceiver(receiver).sendVoipCallAnswerMessage(callAnswerData);

        logCallInfo(callId, "Call answer enqueued to {}: {}", receiver.getIdentity(), callAnswerData.getAction());
        logCallInfo(callId, "  Answer features: {}", callAnswerData.getFeatures());
    }

    /**
     * Send ice candidates to the specified contact.
     *
     * @throws ThreemaException if enqueuing the message fails.
     */
    synchronized void sendICECandidatesMessage(
        @NonNull ContactModel receiver,
        final long callId,
        @NonNull IceCandidate[] iceCandidates
    ) throws ThreemaException {
        final CallStateSnapshot state = this.callState.getStateSnapshot();
        if (!(state.isRinging() || state.isInitializing() || state.isCalling())) {
            logger.warn("Called sendICECandidatesMessage in state {}, ignoring", state.getName());
            return;
        }

        // Build message
        final List<VoipICECandidatesData.Candidate> candidates = new LinkedList<>();
        for (IceCandidate c : iceCandidates) {
            if (c != null) {
                candidates.add(new VoipICECandidatesData.Candidate(c.sdp, c.sdpMid, c.sdpMLineIndex, null));
            }
        }
        final VoipICECandidatesData voipICECandidatesData = new VoipICECandidatesData()
            .setCallId(callId)
            .setCandidates(candidates.toArray(new VoipICECandidatesData.Candidate[candidates.size()]));

        contactService.createReceiver(receiver).sendVoipICECandidateMessage(voipICECandidatesData);

        // Log
        logCallInfo(callId, "Call ICE candidate message enqueued to {}", receiver.getIdentity());
        for (VoipICECandidatesData.Candidate candidate : Objects.requireNonNull(voipICECandidatesData.getCandidates())) {
            logCallInfo(callId, "  Outgoing ICE candidate: {}", candidate.getCandidate());
        }

    }

    /**
     * Send a ringing message to the specified contact.
     */
    private synchronized void sendCallRingingMessage(
        @NonNull ContactModel contactModel,
        final long callId
    ) throws ThreemaException, IllegalStateException {
        final CallStateSnapshot state = this.callState.getStateSnapshot();
        if (!state.isRinging()) {
            throw new IllegalStateException("Called sendCallRingingMessage in state " + state.getName());
        }

        final VoipCallRingingData callRingingData = new VoipCallRingingData()
            .setCallId(callId);

        contactService.createReceiver(contactModel).sendVoipCallRingingMessage(callRingingData);
        logCallInfo(callId, "Call ringing message enqueued to {}", contactModel.getIdentity());
    }

    /**
     * Send a hangup message to the specified contact.
     */
    synchronized void sendCallHangupMessage(
        final @NonNull ContactModel contactModel,
        final long callId
    ) throws ThreemaException {
        final CallStateSnapshot state = this.callState.getStateSnapshot();
        final String peerIdentity = contactModel.getIdentity();

        final VoipCallHangupData callHangupData = new VoipCallHangupData()
            .setCallId(callId);

        final Integer duration = getCallDuration();
        final boolean outgoing = this.isInitiator() == Boolean.TRUE;

        contactService.createReceiver(contactModel).sendVoipCallHangupMessage(callHangupData);
        logCallInfo(
            callId,
            "Call hangup message enqueued to {} (prevState={}, duration={})",
            contactModel.getIdentity(), state, duration
        );

        // Notify the VoIP call event listener
        if (duration == null && (state.isInitializing() || state.isCalling() || state.isDisconnecting())) {
            // Connection was never established
            VoipListenerManager.callEventListener.handle(
                listener -> {
                    if (outgoing) {
                        listener.onAborted(callId, peerIdentity);
                    } else {
                        listener.onMissed(callId, peerIdentity, true, null);
                    }
                }
            );
        }
        // Note: We don't call listener.onFinished here, that's already being done
        // in VoipCallService#disconnect.
    }

    //endregion

    /**
     * Accept an incoming call.
     *
     * @return true if call was accepted, false otherwise (e.g. if no incoming call was active)
     */
    public boolean acceptIncomingCall() {
        if (this.acceptIntent == null) {
            return false;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                this.acceptIntent.send(options.toBundle());
            } else {
                this.acceptIntent.send();
            }
            this.acceptIntent = null;
            return true;
        } catch (PendingIntent.CanceledException e) {
            logger.error("Cannot send pending accept intent: It was cancelled");
            this.acceptIntent = null;
            return false;
        }
    }

    /**
     * Clear the canddidates cache for the specified identity.
     */
    void clearCandidatesCache(String identity) {
        logger.debug("Clearing candidates cache for {}", identity);
        synchronized (this.candidatesCache) {
            this.candidatesCache.remove(identity);
        }
    }

    /**
     * Clear the candidates cache for all identities.
     */
    private void clearCandidatesCache() {
        logger.debug("Clearing candidates cache for all identities");
        synchronized (this.candidatesCache) {
            this.candidatesCache.clear();
        }
    }

    /**
     * Cancel a pending call notification for the specified identity.
     *
     * @param cancelReason Either CallActivity.ACTION_CANCELLED (if a call was cancelled before
     *                     being established) or CallActivity.ACTION_DISCONNECTED (if a previously
     *                     established call was disconnected).
     */
    void cancelCallNotification(@NonNull String identity, @NonNull String cancelReason) {
        // Cancel fullscreen activity launched by notification first
        VoipUtil.sendVoipBroadcast(appContext, cancelReason);
        appContext.stopService(new Intent(ThreemaApplication.getAppContext(), VoipCallService.class));

        synchronized (this.callNotificationTags) {
            if (this.callNotificationTags.contains(identity)) {
                logger.info("Cancelling call notification for {}", identity);
                this.notificationManagerCompat.cancel(identity, INCOMING_CALL_NOTIFICATION_ID);
                this.callNotificationTags.remove(identity);
            } else {
                logger.warn("No call notification found for {}, number of tags: {}", identity, this.callNotificationTags.size());
                if (this.callNotificationTags.isEmpty()) {
                    this.notificationManagerCompat.cancel(identity, INCOMING_CALL_NOTIFICATION_ID);
                }
            }
        }
    }

    /**
     * Cancel all pending call notifications.
     */
    public void cancelCallNotificationsForNewCall() {
        synchronized (this.callNotificationTags) {
            logger.info("Cancelling all {} call notifications", this.callNotificationTags.size());
            for (String tag : this.callNotificationTags) {
                this.notificationManagerCompat.cancel(tag, INCOMING_CALL_NOTIFICATION_ID);
            }
            this.callNotificationTags.clear();
        }
    }

    /**
     * Return the current call duration in seconds.
     * <p>
     * Return null if the call state is not CALLING.
     */
    @Nullable
    Integer getCallDuration() {
        final Long start = this.callStartTimestamp;
        if (start == null) {
            return null;
        } else {
            final long seconds = (SystemClock.elapsedRealtime() - start) / 1000;
            if (seconds > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) seconds;
        }
    }

    /**
     * Disable automatically rejecting the call after a timeout.
     */
    public synchronized void disableTimeoutReject() {
        this.timeoutReject.set(false);
    }

    /**
     * Enable automatically rejecting the call after a timeout.
     */
    public synchronized void enableTimeoutReject() {
        this.timeoutReject.set(true);
    }

    /**
     * Return if the call should be auto rejected. Normally every call should be rejected after a
     * timeout. If the timeout is reached just after the user accepted the call (but the call did
     * not start yet), then this returns false and the call should not be rejected based on the
     * timeout.
     */
    public synchronized boolean isTimeoutReject() {
        return timeoutReject.get();
    }

    // Private helper methods

    /**
     * Show a call notification.
     */
    @Nullable
    @WorkerThread
    private Notification showNotification(
        @NonNull ContactModel contact,
        @Nullable PendingIntent accept,
        @NonNull PendingIntent reject,
        final VoipCallOfferMessage msg) {
        final long timestamp = System.currentTimeMillis();
        final Bitmap avatar = this.contactService.getAvatar(contact, false);
        final PendingIntent inCallPendingIntent = createLaunchPendingIntent(contact.getIdentity(), msg);
        Notification notification = null;

        if (notificationManagerCompat.areNotificationsEnabled()) {
            final NotificationCompat.Builder nbuilder = new NotificationCompat.Builder(this.appContext, NotificationChannels.NOTIFICATION_CHANNEL_INCOMING_CALLS)
                .setContentTitle(appContext.getString(R.string.voip_notification_title))
                .setContentText(appContext.getString(R.string.voip_notification_text, NameUtil.getDisplayNameOrNickname(contact, true)))
                .setOngoing(true)
                .setWhen(timestamp)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setGroup(NotificationGroups.CALLS)
                .setGroupSummary(false);

            if (!ConfigUtils.supportsNotificationChannels()) {
                // If notification channels are not supported, we fall back to explicitly setting sound and vibration on the notification.
                // On devices that do support notification channels, this would have no effect, so we can skip it there.
                nbuilder.setSound(notificationPreferenceService.getLegacyVoipCallRingtone(), AudioManager.STREAM_RING);
                if (notificationPreferenceService.isLegacyVoipCallVibrate()) {
                    nbuilder.setVibrate(NotificationChannels.VIBRATE_PATTERN_GROUP_CALL);
                }
            }

            // We want a full screen notification
            // Set up the main intent to send the user to the incoming call screen
            nbuilder.setFullScreenIntent(inCallPendingIntent, true);
            nbuilder.setContentIntent(inCallPendingIntent);

            // Icons and colors
            nbuilder
                .setLargeIcon(avatar)
                .setSmallIcon(R.drawable.ic_phone_locked_white_24dp);

            // Alerting
            nbuilder.setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL);

            // Privacy
            nbuilder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                // TODO(ANDR-XXXX)
                .setPublicVersion(new NotificationCompat.Builder(appContext, NotificationChannels.NOTIFICATION_CHANNEL_INCOMING_CALLS)
                    .setContentTitle(appContext.getString(R.string.voip_notification_title))
                    .setContentText(appContext.getString(R.string.notification_hidden_text))
                    .setSmallIcon(R.drawable.ic_phone_locked_white_24dp)
                    .setGroup(NotificationGroups.CALLS)
                    .setGroupSummary(false)
                    .build()
                );

            // Add identity to notification for DND priority override
            String contactLookupUri = contactService.getAndroidContactLookupUriString(contact);
            if (contactLookupUri != null) {
                nbuilder.addPerson(contactLookupUri);
            }

            nbuilder.setLocusId(new LocusIdCompat(ContactUtil.getUniqueIdString(contact.getIdentity())));

            // Actions
            final SpannableString rejectString = new SpannableString(appContext.getString(R.string.voip_reject));
            rejectString.setSpan(new ForegroundColorSpan(Color.RED), 0, rejectString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            final SpannableString acceptString = new SpannableString(appContext.getString(R.string.voip_accept));
            acceptString.setSpan(new ForegroundColorSpan(Color.GREEN), 0, acceptString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            nbuilder.addAction(R.drawable.ic_call_end_grey600_24dp, rejectString, reject)
                .addAction(R.drawable.ic_call_grey600_24dp, acceptString, accept != null ? accept : inCallPendingIntent);

            // Build notification
            notification = nbuilder.build();

            // Set flags
            notification.flags |= NotificationCompat.FLAG_INSISTENT | NotificationCompat.FLAG_NO_CLEAR | NotificationCompat.FLAG_ONGOING_EVENT;

            synchronized (this.callNotificationTags) {
                this.notificationManagerCompat.notify(contact.getIdentity(), INCOMING_CALL_NOTIFICATION_ID, notification);
                this.callNotificationTags.add(contact.getIdentity());
            }
        } else {
            // notifications disabled in system settings - fire inCall pending intent to show CallActivity
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ActivityOptions options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED);
                    inCallPendingIntent.send(options.toBundle());
                } else {
                    inCallPendingIntent.send();
                }
            } catch (PendingIntent.CanceledException e) {
                logger.error("Could not send inCallPendingIntent", e);
            }
        }

        return notification;
    }

    private PendingIntent createLaunchPendingIntent(
        @NonNull String identity,
        @Nullable VoipCallOfferMessage msg
    ) {
        final Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.setClass(appContext, CallActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.setData((Uri.parse("foobar://" + SystemClock.elapsedRealtime())));
        intent.putExtra(EXTRA_ACTIVITY_MODE, CallActivity.MODE_INCOMING_CALL);
        intent.putExtra(EXTRA_CONTACT_IDENTITY, identity);
        intent.putExtra(EXTRA_IS_INITIATOR, false);
        if (msg != null) {
            final VoipCallOfferData data = msg.getData();
            intent.putExtra(EXTRA_CALL_ID, data.getCallIdOrDefault(0L));
        }

        // PendingIntent that can be used to launch the InCallActivity.  The
        // system fires off this intent if the user pulls down the windowshade
        // and clicks the notification's expanded view.  It's also used to
        // launch the InCallActivity immediately when when there's an incoming
        // call (see the "fullScreenIntent" field below).
        return PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PENDING_INTENT_FLAG_IMMUTABLE);
    }

    /**
     * Add a new ICE candidate to the cache.
     */
    private void cacheCandidate(String identity, VoipICECandidatesData data) {
        logCallDebug(data.getCallIdOrDefault(0L), "Caching candidate from {}", identity);
        synchronized (this.candidatesCache) {
            if (this.candidatesCache.containsKey(identity)) {
                List<VoipICECandidatesData> candidates = this.candidatesCache.get(identity);
                candidates.add(data);
            } else {
                List<VoipICECandidatesData> candidates = new LinkedList<>();
                candidates.add(data);
                this.candidatesCache.put(identity, candidates);
            }
        }
    }

    /**
     * Create a new video context.
     * <p>
     * Throws an `IllegalStateException` if a video context already exists.
     */
    void createVideoContext() throws IllegalStateException {
        logger.trace("createVideoContext");
        if (this.videoContext != null) {
            throw new IllegalStateException("Video context already exists");
        }
        this.videoContext = new VideoContext();
        this.videoContextFuture.complete(this.videoContext);
    }

    /**
     * Return a reference to the video context instance.
     */
    @Nullable
    public VideoContext getVideoContext() {
        return this.videoContext;
    }

    /**
     * Return a future that resolves with the video context instance.
     */
    @NonNull
    public CompletableFuture<VideoContext> getVideoContextFuture() {
        return this.videoContextFuture;
    }

    /**
     * Release resources associated with the video context instance.
     * <p>
     * It's safe to call this method multiple times.
     */
    void releaseVideoContext() {
        if (this.videoContext != null) {
            this.videoContext.release();
            this.videoContext = null;
            this.videoContextFuture = new CompletableFuture<>();
        }
    }

    public int getVideoRenderMode() {
        return videoRenderMode;
    }

    public void setVideoRenderMode(int videoRenderMode) {
        this.videoRenderMode = videoRenderMode;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        logger.info("Audio Focus change: " + focusChange);
    }
}
