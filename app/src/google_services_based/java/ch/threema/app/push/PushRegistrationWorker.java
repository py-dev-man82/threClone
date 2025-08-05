/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2021-2025 Threema GmbH
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

package ch.threema.app.push;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.slf4j.Logger;

import ch.threema.app.utils.PushUtil;
import ch.threema.base.ThreemaException;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.domain.protocol.csp.ProtocolDefines;

public class PushRegistrationWorker extends Worker {
    private final Logger logger = LoggingUtil.getThreemaLogger("PushRegistrationWorker");

    /**
     * Constructor for the PushRegistrationWorker.
     * <p>
     * Note: This constructor is called by the WorkManager, so don't add additional parameters!
     */
    public PushRegistrationWorker(@NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        var appContext = getApplicationContext();
        Data workerFlags = getInputData();
        final boolean clearToken = workerFlags.getBoolean(PushService.EXTRA_CLEAR_TOKEN, false);
        final boolean withCallback = workerFlags.getBoolean(PushService.EXTRA_WITH_CALLBACK, false);
        logger.debug("doWork FCM registration clear {} withCallback {}", clearToken, withCallback);

        FirebaseApp.initializeApp(appContext);

        if (clearToken) {
            String error = PushService.deleteToken(appContext);
            if (withCallback) {
                PushUtil.signalRegistrationFinished(error, true);
            }
        } else {
            FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        logger.error("Unable to get token", task.getException());
                        if (withCallback) {
                            PushUtil.signalRegistrationFinished(task.getException() != null ? task.getException().getMessage() : "unknown", clearToken);
                        }
                        return;
                    }

                    String token = task.getResult();
                    logger.info("Received FCM registration token");
                    logger.debug("FCM push token: {}", token);
                    String error = null;
                    try {
                        PushUtil.sendTokenToServer(token, ProtocolDefines.PUSHTOKEN_TYPE_FCM);
                    } catch (ThreemaException e) {
                        logger.error("Exception", e);
                        error = e.getMessage();
                    }
                    if (withCallback) {
                        PushUtil.signalRegistrationFinished(error, clearToken);
                    }
                });
        }
        // required by the Worker interface but is not used for any error handling in the push registration process
        return Result.success();
    }
}
