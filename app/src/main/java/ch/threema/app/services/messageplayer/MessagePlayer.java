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

package ch.threema.app.services.messageplayer;

import static ch.threema.domain.protocol.csp.messages.file.FileData.RENDERING_MEDIA;

import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.slf4j.Logger;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import ch.threema.app.R;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.services.FileService;
import ch.threema.app.services.MessageService;
import ch.threema.app.utils.FileUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.base.ProgressListener;
import ch.threema.base.ThreemaException;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.data.media.MediaMessageDataInterface;

public abstract class MessagePlayer {
    private static final Logger logger = LoggingUtil.getThreemaLogger("MessagePlayer");

    public static final int SOURCE_UNDEFINED = 0;
    public static final int SOURCE_UI_TOGGLE = 1;
    public static final int SOURCE_LIFECYCLE = 2;
    public static final int SOURCE_AUDIOFOCUS = 3;
    public static final int SOURCE_AUDIORECORDER = 4;
    public static final int SOURCE_VOIP = 5;

    private File decryptedFile;
    private DecryptTask decryptTask;
    private boolean isAutoPlayed = false;
    private int downloadProgress = 0;
    private int transcodeProgress = 0;

    protected WeakReference<Activity> currentActivityRef;
    protected MessageReceiver<?> currentMessageReceiver;

    public final static int State_NONE = 0;
    public final static int State_DOWNLOADING = 1;
    public final static int State_DOWNLOADED = 2;
    public final static int State_DECRYPTING = 3;
    public final static int State_DECRYPTED = 4;
    public final static int State_STOPPED = 4;
    public final static int State_PLAYING = 5;
    public final static int State_PAUSE = 6;

    public interface DownloadListener {
        @AnyThread
        default void onStart(AbstractMessageModel messageModel) {
        }

        @AnyThread
        default void onStatusUpdate(AbstractMessageModel messageModel, int progress) {
        }

        @AnyThread
        default void onUnknownProgress(AbstractMessageModel messageModel) {
        }

        @AnyThread
        default void onEnd(AbstractMessageModel messageModel, boolean success, String message) {
        }
    }

    public interface DecryptionListener {
        @MainThread
        void onStart(AbstractMessageModel messageModel);

        @MainThread
        void onEnd(AbstractMessageModel messageModel, boolean success, String message, File decryptedFile);
    }

    public interface PlaybackListener {
        @AnyThread
        void onPlay(AbstractMessageModel messageModel, boolean autoPlay);

        @AnyThread
        void onPause(AbstractMessageModel messageModel);

        @AnyThread
        void onStatusUpdate(AbstractMessageModel messageModel, int position);

        @AnyThread
        void onStop(AbstractMessageModel messageModel);
    }

    public interface PlayerListener {
        @AnyThread
        void onError(String humanReadableMessage);
    }

    protected interface InternalListener {
        @AnyThread
        void onComplete(boolean ok);
    }

    public interface TranscodeListener {
        @AnyThread
        default void onStart() {
        }

        @AnyThread
        default void onStatusUpdate(int progress) {
        }

        @AnyThread
        default void onEnd(boolean success, String message) {
        }
    }

    private final Context context;
    private final Map<String, PlayerListener> playerListeners = new HashMap<>();
    private final Map<String, DownloadListener> downloadListeners = new HashMap<>();
    private final Map<String, DecryptionListener> decryptingListeners = new HashMap<>();
    protected final Map<String, PlaybackListener> playbackListeners = new HashMap<>();
    private final Map<String, TranscodeListener> transcodeListeners = new HashMap<>();
    private final MessageService messageService;
    private final FileService fileService;
    private final AbstractMessageModel messageModel;
    private final MessageReceiver<?> messageReceiver;
    protected int state = State_NONE;

    private class DecryptTask extends AsyncTask<Boolean, Void, File> {
        private boolean autoPlay = false;

        @Override
        protected void onCancelled(File file) {
            super.onCancelled(file);

            logger.debug("decrypt canceled");

            state = State_DOWNLOADED;
            synchronized (decryptingListeners) {
                for (DecryptionListener decryptionListener : decryptingListeners.values()) {
                    decryptionListener.onEnd(messageModel, false, null, null);
                }
            }
            if (file != null && file.exists()) {
                FileUtil.deleteFileOrWarn(file, "Decrypt canceled", logger);
            }
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            logger.debug("decrypt onPreExecute");

            state = State_DECRYPTING;
            synchronized (decryptingListeners) {
                for (DecryptionListener decryptionListener : decryptingListeners.values()) {
                    decryptionListener.onStart(messageModel);
                }
            }
        }

        @Override
        protected File doInBackground(Boolean... params) {
            File file = null;
            autoPlay = params[0];

            logger.debug("decrypt doInBackground {}", messageModel.getId());

            try {
                file = fileService.getDecryptedMessageFile(messageModel);
            } catch (Exception e) {
                logger.error("Exception", e);
            }
            return file;
        }

        @Override
        protected void onPostExecute(File file) {
            super.onPostExecute(file);

            if (file != null && file.exists() && !isCancelled()) {
                state = State_DECRYPTED;
                decryptedFile = file;
                logger.debug("decrypt end {}", messageModel.getId());
            } else {
                state = State_DOWNLOADED;
                decryptedFile = null;
                logger.debug("decrypt failed {}", messageModel.getId());
            }

            synchronized (decryptingListeners) {
                for (DecryptionListener decryptionListener : decryptingListeners.values()) {
                    decryptionListener.onEnd(messageModel, state == State_DECRYPTED,
                        (isCancelled() || autoPlay) ? "" : getContext().getString(R.string.media_file_not_found), decryptedFile);
                }
            }

            if (state == State_DECRYPTED) {
                logger.debug("open after decrypt {}", messageModel.getId());

                if (currentActivityRef != null && currentActivityRef.get() != null && isReceiverMatch(currentMessageReceiver) && !isCancelled()) {
                    state = State_PLAYING;
                    isAutoPlayed = autoPlay;
                    open(decryptedFile);

                    synchronized (playbackListeners) {
                        for (PlaybackListener playbackListener : playbackListeners.values()) {
                            playbackListener.onPlay(messageModel, isAutoPlayed);
                        }
                    }
                }
            }
        }
    }

    protected MessagePlayer(Context context, MessageService messageService, FileService fileService, MessageReceiver messageReceiver, AbstractMessageModel messageModel) {
        this.context = context;
        this.messageService = messageService;
        this.fileService = fileService;
        this.messageModel = messageModel;
        this.messageReceiver = messageReceiver;

        //init the state
        if (this.getData() != null && this.getData().isDownloaded()) {
            this.state = State_DOWNLOADED;
        }
    }

    protected AbstractMessageModel getMessageModel() {
        return this.messageModel;
    }

    protected boolean isReceiverMatch(MessageReceiver receiver) {
        if (TestUtil.required(this.messageReceiver, receiver)) {
            return this.messageReceiver.getUniqueIdString().equals(receiver.getUniqueIdString());
        }
        return false;
    }

    protected Context getContext() {
        return this.context;
    }

    public void setCurrentActivity(Activity activity, MessageReceiver messageReceiver) {
        // attach player to activity
        this.currentActivityRef = new WeakReference<>(activity);
        this.currentMessageReceiver = messageReceiver;
    }

    public boolean release() {
        logger.debug("release");

        //stop first!
        this.stop();

        //remove decrypted file!
        if (this.decryptedFile != null && this.decryptedFile.exists()) {
            FileUtil.deleteFileOrWarn(this.decryptedFile, "release", logger);
            this.decryptedFile = null;
            this.state = State_DOWNLOADED;
        }

        // do not release players that are in the process of downloading
        return this.state != State_DOWNLOADING;
    }

    public boolean stop() {
        logger.debug("stop");
        if (this.state == State_PLAYING) {
            this.state = State_DECRYPTED;
            synchronized (this.playbackListeners) {
                for (PlaybackListener playbackListener : this.playbackListeners.values()) {
                    playbackListener.onStop(messageModel);
                }
            }
        } else if (this.state == State_DECRYPTING) {
            if (this.decryptTask != null) {
                if (!this.decryptTask.isCancelled()) {
                    logger.debug("cancel decrypt");
                    RuntimeUtil.runOnUiThread(() -> decryptTask.cancel(true));
                }
            } else {
                this.state = State_DOWNLOADED;
            }
        }

        return true;
    }

    public boolean cancel() {
        // cancel all operations, including download
        logger.debug("cancel");

        boolean result = this.stop();
        if (this.state == State_DOWNLOADING) {
            RuntimeUtil.runOnWorkerThread(() -> this.messageService.cancelMessageDownload(this.getMessageModel()));
            this.state = State_NONE;
        }

        return result;
    }

    public boolean togglePlayPause() {
        logger.debug("togglePlayPause");

        switch (this.state) {
            case State_PLAYING:
                this.pause(SOURCE_UI_TOGGLE);
                break;
            case State_DOWNLOADING:
            case State_DECRYPTING:
                logger.debug("do nothing (state = {})", this.state);
                break;
            default:
                this.open();
        }
        return true;
    }

    public void pause() {
        pause(SOURCE_UNDEFINED);
    }

    public void pause(int source) {
        logger.debug("pause. source = " + source + " state = " + this.state);
        if (this.state == State_PLAYING) {
            this.state = State_PAUSE;
            this.makePause(source);
            synchronized (this.playbackListeners) {
                for (PlaybackListener playbackListener : this.playbackListeners.values()) {
                    playbackListener.onPause(this.messageModel);
                }
            }

        }
    }

    public boolean open() {
        return this.open(false);
    }

    public boolean open(final boolean autoPlay) {
        final MediaMessageDataInterface data = this.getData();
        if (data != null) {
            if (data.isDownloaded()) {
                this.play(autoPlay);
            } else {
                this.download(new InternalListener() {
                    @Override
                    public void onComplete(boolean ok) {
                        if (ok) {
                            data.isDownloaded(true);
                            messageService.save(setData(data));

                            if (autoPlay ||
                                getMessageModel().getFileData().getRenderingType() != RENDERING_MEDIA ||
                                FileUtil.isAudioFile(getMessageModel().getFileData())) {
                                open(autoPlay);
                            }
                        }
                    }
                }, autoPlay);

            }
            return true;
        }
        return false;
    }

    public float togglePlaybackSpeed(float currentSpeed) {
        return 1f;
    }

    public MessagePlayer addListener(String key, PlayerListener listener) {
        synchronized (this.playerListeners) {
            this.playerListeners.put(key, listener);
        }
        return this;
    }

    public MessagePlayer addListener(String key, PlaybackListener listener) {
        synchronized (this.playbackListeners) {
            this.playbackListeners.put(key, listener);
        }
        return this;
    }

    public MessagePlayer addListener(String key, DownloadListener listener) {
        synchronized (this.downloadListeners) {
            this.downloadListeners.put(key, listener);
        }
        return this;
    }

    public MessagePlayer addListener(String key, DecryptionListener listener) {
        synchronized (this.decryptingListeners) {
            this.decryptingListeners.put(key, listener);
        }
        return this;
    }

    public MessagePlayer addListener(String key, TranscodeListener listener) {
        synchronized (this.transcodeListeners) {
            this.transcodeListeners.put(key, listener);
        }
        return this;
    }

    public void removeListener(PlayerListener listener) {
        synchronized (this.playerListeners) {
            this.playerListeners.remove(listener);
        }
    }

    public void removeListener(PlaybackListener listener) {
        synchronized (this.playbackListeners) {
            this.playbackListeners.remove(listener);
        }
    }

    public void removeListener(DownloadListener listener) {
        synchronized (this.downloadListeners) {
            this.downloadListeners.remove(listener);
        }
    }

    public void removeListener(DecryptionListener listener) {
        synchronized (this.decryptingListeners) {
            this.decryptingListeners.remove(listener);
        }
    }

    public void removeListeners() {
        synchronized (this.playbackListeners) {
            Iterator<Map.Entry<String, PlaybackListener>> iterator = this.playbackListeners.entrySet().iterator();
            while (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }

        synchronized (this.playerListeners) {
            Iterator<Map.Entry<String, PlayerListener>> iterator = this.playerListeners.entrySet().iterator();
            while (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }

        synchronized (this.downloadListeners) {
            Iterator<Map.Entry<String, DownloadListener>> iterator = this.downloadListeners.entrySet().iterator();
            while (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }

        synchronized (this.decryptingListeners) {
            Iterator<Map.Entry<String, DecryptionListener>> iterator = this.decryptingListeners.entrySet().iterator();
            while (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }

        synchronized (this.transcodeListeners) {
            Iterator<Map.Entry<String, TranscodeListener>> iterator = this.transcodeListeners.entrySet().iterator();
            while (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    abstract protected MediaMessageDataInterface getData();

    abstract protected AbstractMessageModel setData(MediaMessageDataInterface data);

    abstract protected void open(File decryptedFile);

    abstract protected void makePause(int source);

    abstract protected void makeResume(int source);

    abstract public void seekTo(int pos);

    /**
     * Get duration of media that is currently loaded/playing
     *
     * @return duration in milliseconds
     */
    abstract public int getDuration();

    /**
     * Get current position of media that is currently playing
     *
     * @return position in milliseconds
     */
    abstract public int getPosition();

    public int getState() {
        return this.state;
    }

    final public int getDownloadProgress() {
        return this.downloadProgress;
    }

    final public int getTranscodeProgress() {
        return this.transcodeProgress;
    }

    public void resume(int source) {
        logger.debug("resume");
    }

    protected void play(final boolean autoPlay) {
        logger.debug("play");
        if (this.state == State_PAUSE) {
            this.state = State_PLAYING;
            this.makeResume(SOURCE_UI_TOGGLE);

            synchronized (this.playbackListeners) {
                for (PlaybackListener playbackListener : this.playbackListeners.values()) {
                    playbackListener.onPlay(messageModel, autoPlay);
                }
            }
            return;
        }

        // decrypt in separate thread!
        RuntimeUtil.runOnUiThread(() -> {
            logger.debug("execute decrypt");
            decryptTask = new DecryptTask();
            try {
                decryptTask.execute(autoPlay);
            } catch (RejectedExecutionException e) {
                logger.debug("decryptTask rejected");
            }
        });
    }

    protected void download(@NonNull final InternalListener internalListener, final boolean autoplay) {
        //download media first
        if (this.state == State_DOWNLOADING) {
            //do nothing, downloading in progress
            return;
        }
        state = State_DOWNLOADING;
        synchronized (this.downloadListeners) {
            for (DownloadListener downloadListener : this.downloadListeners.values()) {
                downloadListener.onStart(this.messageModel);
            }
        }
        logger.debug("download");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean success = messageService.downloadMediaMessage(messageModel, new ProgressListener() {
                        @Override
                        public void updateProgress(final int progress) {
                            downloadProgress = progress;

                            synchronized (downloadListeners) {
                                for (DownloadListener downloadListener : downloadListeners.values()) {
                                    downloadListener.onStatusUpdate(messageModel, progress);
                                }
                            }
                        }

                        @Override
                        public void noProgressAvailable() {
                            synchronized (downloadListeners) {
                                for (DownloadListener downloadListener : downloadListeners.values()) {
                                    downloadListener.onUnknownProgress(messageModel);
                                }
                            }
                        }

                        @Override
                        public void onFinished(boolean success) {
                            downloadProgress = 100;

                            synchronized (downloadListeners) {
                                for (DownloadListener downloadListener : downloadListeners.values()) {
                                    downloadListener.onStatusUpdate(messageModel, 100);
                                }
                            }
                        }
                    });

                    state = State_DOWNLOADED;
                    synchronized (downloadListeners) {
                        for (DownloadListener downloadListener : downloadListeners.values()) {
                            downloadListener.onEnd(messageModel, success, null);
                        }
                    }

                    internalListener.onComplete(true);
                } catch (Exception e) {
                    String errorMessage;
                    if (state == State_NONE) {
                        // cancelled by user
                        errorMessage = null;
                    } else {
                        // some other error
                        state = State_NONE;
                        errorMessage = autoplay ? null : getContext().getString(R.string.could_not_download_message);
                    }
                    synchronized (downloadListeners) {
                        for (DownloadListener downloadListener : downloadListeners.values()) {
                            downloadListener.onEnd(messageModel, false, errorMessage);
                        }
                    }
                    internalListener.onComplete(false);
                }
            }
        }, "MessagePlayerDownload").start();
    }

    protected final void updatePlayState() {
        synchronized (this.playbackListeners) {
            for (PlaybackListener playbackListener : this.playbackListeners.values()) {
                playbackListener.onStatusUpdate(this.messageModel, getPosition());
            }
        }
    }

    public void setTranscodeProgress(int transcodeProgress) {
        synchronized (this.transcodeListeners) {
            this.transcodeProgress = transcodeProgress;
            for (TranscodeListener transcodeListener : this.transcodeListeners.values()) {
                transcodeListener.onStatusUpdate(transcodeProgress);
            }
        }
    }

    public void setTranscodeStart() {
        synchronized (this.transcodeListeners) {
            for (TranscodeListener transcodeListener : this.transcodeListeners.values()) {
                transcodeListener.onStart();
            }
        }
    }

    public void setTranscodeFinished(boolean success, @Nullable String message) {
        synchronized (this.transcodeListeners) {
            for (TranscodeListener transcodeListener : this.transcodeListeners.values()) {
                transcodeListener.onEnd(success, message);
            }
        }
    }

    protected void showError(final String error) {
        synchronized (this.playbackListeners) {
            for (PlayerListener playerListener : this.playerListeners.values()) {
                playerListener.onError(error);
            }
        }
    }

    protected void exception(String error, Exception x) {
        this.showError(error);
        logger.error("Exception", x);
    }

    protected void exception(int error, Exception x) {
        if (this.getContext() != null) {
            this.exception(this.getContext().getString(error), x);
        }
    }

    @WorkerThread
    protected void markAsConsumed() {
        try {
            messageService.markAsConsumed(getMessageModel());
        } catch (ThreemaException e) {
            logger.error("Unable to mark message as consumed", e);
        }
    }
}
