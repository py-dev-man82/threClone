/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2020-2025 Threema GmbH
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

package ch.threema.app.video.transcoder;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.text.format.DateUtils;

import org.slf4j.Logger;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Objects;

import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.ui.MediaItem;
import ch.threema.app.utils.FileUtil;
import ch.threema.base.ThreemaException;
import ch.threema.base.utils.LoggingUtil;

import static ch.threema.app.AppConstants.MAX_BLOB_SIZE;
import static ch.threema.app.utils.MimeUtil.MIME_AUDIO;
import static ch.threema.app.utils.MimeUtil.MIME_VIDEO;

public class VideoConfig {
    private static final Logger logger = LoggingUtil.getThreemaLogger("VideoConfig");

    public static final int BITRATE_LOW = 384000;
    public static final int BITRATE_MEDIUM = 1500000;
    public static final int BITRATE_DEFAULT = 2000000;

    // longest edge of video
    public static final int VIDEO_SIZE_MEDIUM = 848;
    public static final int VIDEO_SIZE_SMALL = 480;

    private static final int FILE_OVERHEAD = 48 * 1024;

    public static int getPreferredVideoBitrate(int videoSizeId) {
        switch (videoSizeId) {
            case PreferenceService.VideoSize_MEDIUM:
                return BITRATE_MEDIUM;
            case PreferenceService.VideoSize_SMALL:
                return BITRATE_LOW;
        }
        return BITRATE_DEFAULT;
    }

    public static int getMaxSizeFromBitrate(int bitrate) {
        switch (bitrate) {
            case BITRATE_MEDIUM:
                return VIDEO_SIZE_MEDIUM;
            case BITRATE_LOW:
                return VIDEO_SIZE_SMALL;
        }
        return 0;
    }

    /**
     * Returns the ID of the first track we find for the given mime type
     *
     * @param extractor
     * @param mimeType
     * @return ID of first matching track or -1 if none was found
     */
    private static int findTrack(MediaExtractor extractor, String mimeType) {
        // Select the first video track we find, ignore the rest.
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            logger.info("Found track " + i + " of format " + mime);
            if (mime != null && mime.startsWith(mimeType)) {
                logger.debug("Extractor selected track " + i + " (" + mime + "): " + format);
                return i;
            }
        }
        return -1;
    }

    /**
     * Return ideal video bitrate from a set of predefined values keeping in account user setting and MAX_BLOB_SIZE restriction
     *
     * @param mediaItem Media Item representing this video
     * @return target bitrate, -1 if the resulting file would not fit regardless of bitrate, or 0 if no change of bitrate is necessary
     * @throws ThreemaException
     */
    public static int getTargetVideoBitrate(Context context, MediaItem mediaItem, int videoSize) throws ThreemaException {
        Integer originalBitrate = null;
        int targetBitrate;
        int preferredBitrate = getPreferredVideoBitrate(videoSize);

        // do not use automatic resource management on MediaMetadataRetriever
        //noinspection resource
        MediaMetadataRetriever metaRetriever = new MediaMetadataRetriever();
        try {
            metaRetriever.setDataSource(context, mediaItem.getUri());
            originalBitrate = Integer.parseInt(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        } catch (Exception e) {
            logger.error("Exception querying MediaMetaDataRetriever", e);
        } finally {
            try {
                metaRetriever.release();
            } catch (IOException e) {
                logger.debug("Failed to release MediaMetadataRetriever");
            }
        }

        if (originalBitrate == null) {
            logger.info("Original bit rate could not be extracted. Falling back to bit rate {}", preferredBitrate);
            return preferredBitrate;
        }

        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, mediaItem.getUri(), null);
        } catch (IOException e) {
            logger.error("Exception setting MediaExtractor data source with uri... try with FileDescriptor next");

            String realMediaPatch = FileUtil.getRealPathFromURI(context, mediaItem.getUri());
            if (realMediaPatch == null) {
                logger.error("Exception setting MediaExtractor data source with FileDescriptor, real media patch is null", e);
                throw new ThreemaException(e.getMessage());
            }

            File file = new File(Objects.requireNonNull(FileUtil.getRealPathFromURI(context, mediaItem.getUri())));
            try (FileInputStream fis = new FileInputStream(file)) {
                FileDescriptor fd = fis.getFD();
                extractor.setDataSource(fd);
            } catch (Exception e2) {
                logger.error("Exception setting MediaExtractor data source with FileDescriptor", e);
                extractor.release();
                throw new ThreemaException(e.getMessage());
            }
        }

        targetBitrate = calculateTargetBitrate(extractor, mediaItem, originalBitrate);
        extractor.release();

        if (targetBitrate < preferredBitrate) {
            logger.info("Preferred bit rate is {}. Falling back to bit rate {} due to size", preferredBitrate, targetBitrate);
        }

        if (mediaItem.getType() != MediaItem.TYPE_VIDEO_CAM && targetBitrate > preferredBitrate && preferredBitrate != BITRATE_DEFAULT) {
            logger.info("Target bitrate ({}) is higher than preferred bitrate ({})", targetBitrate, preferredBitrate);
            return preferredBitrate;
        }

        if (targetBitrate != originalBitrate) {
            logger.info("Target bitrate ({}) is not original bitrate ({})", targetBitrate, originalBitrate);
            return targetBitrate;
        }

        return 0; // no change necessary
    }

    private static int calculateTargetBitrate(MediaExtractor extractor, MediaItem mediaItem, int originalBitrate) throws ThreemaException {
        int calculatedAudioSize = 0;
        int srcAudioTrack = findTrack(extractor, MIME_AUDIO);
        if (srcAudioTrack >= 0) {
            MediaFormat srcAudioFormat = extractor.getTrackFormat(srcAudioTrack);

            float durationS = 0, bitrate = 0;
            if (srcAudioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                durationS = (float) srcAudioFormat.getLong(MediaFormat.KEY_DURATION) / 1000000;
            }

            if (srcAudioFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                bitrate = srcAudioFormat.getInteger(MediaFormat.KEY_BIT_RATE);
            }

            calculatedAudioSize = (int) (durationS * bitrate / 8);
            logger.info("Estimated audio size (bytes): " + calculatedAudioSize);
        }

        int srcVideoTrack = findTrack(extractor, MIME_VIDEO);
        if (srcVideoTrack >= 0) {
            float durationS = (float) mediaItem.getTrimmedDurationMs() / (float) DateUtils.SECOND_IN_MILLIS;
            int calculatedFileSize = ((int) (durationS * originalBitrate / 8)) + calculatedAudioSize + FILE_OVERHEAD;
            if (calculatedFileSize > MAX_BLOB_SIZE) {
                calculatedFileSize = ((int) (durationS * BITRATE_MEDIUM / 8)) + calculatedAudioSize + FILE_OVERHEAD;
                if (calculatedFileSize > MAX_BLOB_SIZE) {
                    calculatedFileSize = ((int) (durationS * BITRATE_LOW / 8)) + calculatedAudioSize + FILE_OVERHEAD;
                    if (calculatedFileSize > MAX_BLOB_SIZE) {
                        return -1;
                    } else {
                        return BITRATE_LOW;
                    }
                } else {
                    return BITRATE_MEDIUM;
                }
            } else {
                return originalBitrate;
            }
        } else {
            throw new ThreemaException("No video track found in this file");
        }
    }
}
