/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2016-2025 Threema GmbH
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

package ch.threema.app.activities;

import static ch.threema.app.utils.BitmapUtil.FLIP_HORIZONTAL;
import static ch.threema.app.utils.BitmapUtil.FLIP_NONE;
import static ch.threema.app.utils.BitmapUtil.FLIP_VERTICAL;
import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.FaceDetector;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorInt;
import androidx.annotation.DimenRes;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.android.colorpicker.ColorPickerDialog;
import com.android.colorpicker.ColorPickerSwatch;
import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ch.threema.app.AppConstants;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.dialogs.GenericAlertDialog;
import ch.threema.app.dialogs.GenericProgressDialog;
import ch.threema.app.emojis.EmojiButton;
import ch.threema.app.emojis.EmojiPicker;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.motionviews.FaceItem;
import ch.threema.app.motionviews.viewmodel.Font;
import ch.threema.app.motionviews.viewmodel.Layer;
import ch.threema.app.motionviews.viewmodel.TextLayer;
import ch.threema.app.motionviews.widget.ActionEntity;
import ch.threema.app.motionviews.widget.CropEntity;
import ch.threema.app.motionviews.widget.FaceBlurEntity;
import ch.threema.app.motionviews.widget.FaceEmojiEntity;
import ch.threema.app.motionviews.widget.FaceEntity;
import ch.threema.app.motionviews.widget.FlipEntity;
import ch.threema.app.motionviews.widget.ImageEntity;
import ch.threema.app.motionviews.widget.MotionEntity;
import ch.threema.app.motionviews.widget.MotionView;
import ch.threema.app.motionviews.widget.PathEntity;
import ch.threema.app.motionviews.widget.RotationEntity;
import ch.threema.app.motionviews.widget.TextEntity;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.FileService;
import ch.threema.app.services.GroupService;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.services.UserService;
import ch.threema.app.ui.ComposeEditText;
import ch.threema.app.ui.LockableScrollView;
import ch.threema.app.ui.MediaItem;
import ch.threema.app.ui.PaintSelectionPopup;
import ch.threema.app.ui.PaintView;
import ch.threema.app.ui.SendButton;
import ch.threema.app.utils.BitmapUtil;
import ch.threema.app.utils.BitmapWorkerTask;
import ch.threema.app.utils.BitmapWorkerTaskParams;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.DialogUtil;
import ch.threema.app.utils.EditTextUtil;
import ch.threema.app.utils.IntentDataUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.data.repositories.GroupModelRepository;
import ch.threema.localcrypto.MasterKeyLockedException;
import ch.threema.data.models.GroupModel;

public class ImagePaintActivity extends ThreemaToolbarActivity implements GenericAlertDialog.DialogClickListener {
    private static final Logger logger = LoggingUtil.getThreemaLogger("ImagePaintActivity");

    private enum ActivityMode {
        /**
         * This is the mode where an image is taken as background and the user can draw on it.
         */
        EDIT_IMAGE,
        /**
         * In this mode, an image and a receiver is given and the user can directly send the image
         * after drawing on it.
         */
        IMAGE_REPLY,
        /**
         * In this mode, only a receiver is given and the user can directly send the drawing without
         * a background image.
         */
        DRAWING
    }

    private static final String EXTRA_IMAGE_REPLY = "imageReply";
    private static final String EXTRA_GROUP_ID = "groupId";
    private static final String EXTRA_ACTIVITY_MODE = "activityMode";

    private static final String DIALOG_TAG_COLOR_PICKER = "colp";
    private static final String KEY_PEN_COLOR = "pc";
    private static final String KEY_BACKGROUND_COLOR = "bc";
    private static final int REQUEST_CODE_STICKER_SELECTOR = 44;
    private static final int REQUEST_CODE_ENTER_TEXT = 45;
    private static final String DIALOG_TAG_QUIT_CONFIRM = "qq";
    private static final String DIALOG_TAG_SAVING_IMAGE = "se";
    private static final String DIALOG_TAG_BLUR_FACES = "bf";

    private static final String SMILEY_PATH = "emojione/3_Emoji_classic/1f600.png";

    private static final int STROKE_MODE_BRUSH = 0;
    private static final int STROKE_MODE_PENCIL = 1;

    private static final int STROKE_MODE_HIGHLIGHTER = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STROKE_MODE_BRUSH, STROKE_MODE_PENCIL, STROKE_MODE_HIGHLIGHTER})
    private @interface StrokeMode {
    }

    private static final int MAX_FACES = 16;

    private static final int ANIMATION_DURATION_MS = 200;

    private static final Set<Class<? extends ActionEntity>> allowedActionsEntitiesToCrop = new HashSet<>();

    static {
        allowedActionsEntitiesToCrop.add(RotationEntity.class);
        allowedActionsEntitiesToCrop.add(FlipEntity.class);
        allowedActionsEntitiesToCrop.add(CropEntity.class);
    }

    private ImageView imageView;
    private PaintView paintView;
    private MotionView motionView;
    private FrameLayout imageFrame;
    private LockableScrollView scrollView;
    private ComposeEditText captionEditText;
    private CircularProgressIndicator progressBar;
    private EmojiPicker emojiPicker;

    private int clipWidth, clipHeight;

    private File inputFile;
    private Uri imageUri, outputUri;
    private MediaItem mediaItem;

    @ColorInt
    private int penColor, backgroundColor;

    private MenuItem undoItem, drawParentItem, paintItem, pencilItem, highlighterItem, blurFacesItem, cropItem;
    private Drawable brushIcon, pencilIcon, highlighterIcon;
    private PaintSelectionPopup paintSelectionPopup;
    private final Deque<ActionEntity> undoHistory = new LinkedList<>();
    private long lastAnimationStart = 0;
    private final MediaItem.Orientation currentOrientation = new MediaItem.Orientation();
    private boolean saveSemaphore = false;
    private @StrokeMode int strokeMode = STROKE_MODE_BRUSH;
    private ActivityMode activityMode = ActivityMode.EDIT_IMAGE;
    private long groupId = -1;
    private final ExecutorService threadPoolExecutor = Executors.newSingleThreadExecutor();
    private File cropFile;

    private final ActivityResultLauncher<Intent> cropResultLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == Activity.RESULT_OK
                && activityMode == ActivityMode.IMAGE_REPLY
                && cropFile != null
                && mediaItem != null
            ) {
                // Add crop entity to undo history
                undoHistory.push(new CropEntity(
                    mediaItem.getUri(),
                    new MediaItem.Orientation(mediaItem.getRotation(), mediaItem.getFlip()))
                );

                imageUri = Uri.fromFile(cropFile);
                mediaItem.setUri(imageUri);
                // As the image is saved with the current orientation applied, we need to apply the
                // inverse orientation to get it in the original orientation.
                MediaItem.Orientation inverseOrientation = currentOrientation.getInverse();
                // As the flip is applied before the rotation, we may need to swap the flips,
                // because a horizontal flip on a 90 or 270 rotated image is a vertical flip.
                if (inverseOrientation.getRotation() == 90 || inverseOrientation.getRotation() == 270) {
                    inverseOrientation = getSwappedFlips(inverseOrientation);
                }
                mediaItem.setRotation(inverseOrientation.getRotation());
                mediaItem.setFlip(inverseOrientation.getFlip());

                resetViewOrientation(imageView);
                resetViewOrientation(motionView);
                resetViewOrientation(paintView);

                loadImage(this::applyCurrentOrientation);
                invalidateOptionsMenu();
            }
        });

    /**
     * Returns an intent to start the activity for editing a picture. The edited picture is stored
     * in the output file. On success, the activity finishes with {@code RESULT_OK}. If the activity
     * finishes with {@code RESULT_CANCELED}, no changes were made or an error occurred.
     *
     * @param context    the context
     * @param mediaItem  the media item containing the image uri and the orientation/flip information
     * @param outputFile the file where the edited image is stored in
     * @return the intent to start the {@code ImagePaintActivity}
     */
    public static Intent getImageEditIntent(
        @NonNull Context context,
        @NonNull MediaItem mediaItem,
        @NonNull File outputFile
    ) {
        Intent intent = new Intent(context, ImagePaintActivity.class);
        intent.putExtra(EXTRA_ACTIVITY_MODE, ActivityMode.EDIT_IMAGE.name());
        intent.putExtra(Intent.EXTRA_STREAM, mediaItem);
        intent.putExtra(AppConstants.EXTRA_OUTPUT_FILE, Uri.fromFile(outputFile));
        return intent;
    }

    /**
     * Returns an intent to start the activity for creating a fast reply. The edited picture is
     * stored in the output file. The message receiver and the updated media item will be part of
     * the activity result data.
     *
     * @param context         the context
     * @param mediaItem       the media item containing the image uri
     * @param outputFile      the output file where the edited image is stored in
     * @param messageReceiver the message receiver
     * @param groupModel      the group model (if sent to a group) for mentions
     * @return the intent to start the {@code ImagePaintActivity}
     */
    public static Intent getImageReplyIntent(
        @NonNull Context context,
        @NonNull MediaItem mediaItem,
        @NonNull File outputFile,
        @SuppressWarnings("rawtypes") @NonNull MessageReceiver messageReceiver,
        @Nullable GroupModel groupModel
    ) {
        Intent intent = new Intent(context, ImagePaintActivity.class);
        intent.putExtra(EXTRA_ACTIVITY_MODE, ActivityMode.IMAGE_REPLY.name());
        intent.putExtra(Intent.EXTRA_STREAM, mediaItem);
        intent.putExtra(AppConstants.EXTRA_OUTPUT_FILE, Uri.fromFile(outputFile));
        intent.putExtra(ImagePaintActivity.EXTRA_IMAGE_REPLY, true);
        if (groupModel != null) {
            intent.putExtra(EXTRA_GROUP_ID, groupModel.getDatabaseId());
        }
        IntentDataUtil.addMessageReceiverToIntent(intent, messageReceiver);
        return intent;
    }

    /**
     * Returns an intent to start the activity for creating a drawing. The edited picture is stored
     * in a file. The message receiver and the media item will be part of the activity result data.
     *
     * @param context         the context
     * @param messageReceiver the message receiver
     * @return the intent to start the {@code ImagePaintActivity}
     */
    public static Intent getDrawingIntent(
        @NonNull Context context,
        @SuppressWarnings("rawtypes") @NonNull MessageReceiver messageReceiver
    ) {
        Intent intent = new Intent(context, ImagePaintActivity.class);
        intent.putExtra(EXTRA_ACTIVITY_MODE, ActivityMode.DRAWING.name());
        IntentDataUtil.addMessageReceiverToIntent(intent, messageReceiver);
        return intent;
    }

    @Override
    public int getLayoutResource() {
        return R.layout.activity_image_paint;
    }

    @Override
    protected boolean enableOnBackPressedCallback() {
        return true;
    }

    @Override
    protected void handleOnBackPressed() {
        if (hasChanges()) {
            GenericAlertDialog dialogFragment = GenericAlertDialog.newInstance(
                R.string.discard_changes_title,
                R.string.discard_changes,
                R.string.discard,
                R.string.cancel);
            dialogFragment.show(getSupportFragmentManager(), DIALOG_TAG_QUIT_CONFIRM);
        } else {
            finishWithoutChanges();
        }
    }

    private boolean hasChanges() {
        return !undoHistory.isEmpty();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK && data != null) {
            switch (requestCode) {
                case REQUEST_CODE_STICKER_SELECTOR:
                    final String stickerPath = data.getStringExtra(StickerSelectorActivity.EXTRA_STICKER_PATH);
                    if (!TestUtil.isEmptyOrNull(stickerPath)) {
                        addSticker(stickerPath);
                    }
                    break;
                case REQUEST_CODE_ENTER_TEXT:
                    final String text = data.getStringExtra(ImagePaintKeyboardActivity.INTENT_EXTRA_TEXT);
                    if (!TestUtil.isEmptyOrNull(text)) {
                        addText(text);
                    }
            }
        }
    }

    private void addSticker(final String stickerPath) {
        paintView.setActive(false);

        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... params) {
                try {
                    Bitmap bitmap = BitmapFactory.decodeStream(getAssets().open(stickerPath));
                    boolean isFlippedHorizontally = isFlippedHorizontally();
                    boolean isFlippedVertically = isFlippedVertically();
                    float rotation = imageView.getRotation();
                    if (isFlippedHorizontally || isFlippedVertically || rotation != 0) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate(-rotation);
                        matrix.postScale(isFlippedHorizontally ? -1 : 1, isFlippedVertically ? -1 : 1);
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    }
                    return bitmap;
                } catch (IOException e) {
                    logger.error("Exception", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(final Bitmap bitmap) {
                if (bitmap != null) {
                    motionView.post(() -> {
                        Layer layer = new Layer();
                        ImageEntity entity = new ImageEntity(layer, bitmap, motionView.getWidth(), motionView.getHeight());
                        motionView.addEntityAndPosition(entity);
                    });
                }
            }
        }.execute();
    }

    private void addText(final String text) {
        paintView.setActive(false);

        TextLayer textLayer = new TextLayer();
        Font font = new Font();

        font.setColor(penColor);
        font.setSize(getResources().getDimensionPixelSize(R.dimen.imagepaint_default_font_size));

        textLayer.setFont(font);
        textLayer.setText(text);
        textLayer.setRotationInDegrees(-imageView.getRotation());
        int rotation = (int) imageView.getRotation() % 360;
        if (rotation < 0) {
            rotation += 360;
        }
        if (rotation == 90 || rotation == 270) {
            textLayer.setFlipped(imageView.getScaleY() < 0);
        } else {
            textLayer.setFlipped(imageView.getScaleX() < 0);
        }

        TextEntity textEntity = new TextEntity(textLayer, motionView.getWidth(),
            motionView.getHeight());
        textEntity.setColor(penColor);
        motionView.addEntityAndPosition(textEntity);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        Intent intent = getIntent();

        groupId = intent.getIntExtra(EXTRA_GROUP_ID, -1);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mediaItem = intent.getParcelableExtra(Intent.EXTRA_STREAM, MediaItem.class);
        } else {
            mediaItem = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }

        try {
            String activityModeOrdinal = intent.getStringExtra(EXTRA_ACTIVITY_MODE);
            activityMode = ActivityMode.valueOf(activityModeOrdinal);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid activity mode", e);
            finishWithoutChanges();
            return;
        }

        this.outputUri = intent.getParcelableExtra(AppConstants.EXTRA_OUTPUT_FILE);

        setSupportActionBar(getToolbar());
        ActionBar actionBar = getSupportActionBar();

        if (actionBar == null) {
            finishWithoutChanges();
            return;
        }

        actionBar.setDisplayHomeAsUpEnabled(activityMode == ActivityMode.EDIT_IMAGE);
        actionBar.setHomeAsUpIndicator(R.drawable.ic_check);
        actionBar.setTitle("");

        this.paintView = findViewById(R.id.paint_view);
        this.progressBar = findViewById(R.id.progress);
        this.imageView = findViewById(R.id.preview_image);
        this.motionView = findViewById(R.id.motion_view);

        this.brushIcon = AppCompatResources.getDrawable(this, R.drawable.ic_brush);
        this.pencilIcon = AppCompatResources.getDrawable(this, R.drawable.ic_pencil_outline);
        this.highlighterIcon = AppCompatResources.getDrawable(this, R.drawable.ic_ink_highlighter_outline);

        this.penColor = getResources().getColor(R.color.material_red);
        this.backgroundColor = Color.WHITE;
        if (savedInstanceState != null) {
            this.penColor = savedInstanceState.getInt(KEY_PEN_COLOR, penColor);
            this.backgroundColor = savedInstanceState.getInt(KEY_BACKGROUND_COLOR, backgroundColor);
        }

        initializeCaptionEditText();

        // Lock the scroll view (the scroll view is needed so that the keyboard does not resize the drawing)
        scrollView = findViewById(R.id.content_scroll_view);
        scrollView.setScrollingEnabled(false);

        // Set the height of the image to the size of the scrollview
        this.imageFrame = findViewById(R.id.content_frame);

        this.paintView.setColor(penColor);
        this.paintView.setStrokeWidth(getResources().getDimensionPixelSize(R.dimen.imagepaint_brush_stroke_width));
        this.paintView.setTouchListener(new PaintView.TouchListener() {
            @Override
            public void onTouchUp() {
                invalidateOptionsMenu();
            }

            @Override
            public void onTouchDown() {
            }

            @Override
            public void onAdded() {
                undoHistory.push(new PathEntity());
            }

            @Override
            public void onDeleted() {
            }
        });

        this.motionView.setTouchListener(new MotionView.TouchListener() {
            @Override
            public void onSelected(boolean isSelected) {
                invalidateOptionsMenu();
            }

            @Override
            public void onLongClick(@NonNull MotionEntity entity, int x, int y) {
                paintSelectionPopup.show((int) motionView.getX() + x, (int) motionView.getY() + y, entity);
            }

            @Override
            public void onAdded(MotionEntity entity) {
                undoHistory.push(entity);
            }

            @SuppressLint("UseValueOf")
            @Override
            public void onDeleted(MotionEntity entity) {
                undoHistory.remove(entity);
            }

            @Override
            public void onTouchUp() {
                if (!paintView.getActive()) {
                    invalidateOptionsMenu();
                }
            }

            @Override
            public void onTouchDown() {
            }
        });

        this.paintSelectionPopup = new PaintSelectionPopup(this, this.motionView);
        this.paintSelectionPopup.setListener(new PaintSelectionPopup.PaintSelectPopupListener() {
            @Override
            public void onRemoveClicked() {
                deleteEntity();
            }

            @Override
            public void onFlipClicked() {
                flipEntity();
            }

            @Override
            public void onBringToFrontClicked() {
                bringToFrontEntity();
            }

            @Override
            public void onColorClicked() {
                colorEntity();
            }

            @Override
            public void onOpen() {
                motionView.setClickable(false);
                paintView.setClickable(false);
            }

            @Override
            public void onClose() {
                motionView.setClickable(true);
                paintView.setClickable(true);
            }
        });

        if (activityMode == ActivityMode.DRAWING) {
            inputFile = createDrawingInputFile();
            File outputFile = createDrawingOutputFile();

            if (inputFile == null || outputFile == null) {
                logger.error("Input file '{}' or output file '{}' is null", inputFile, outputFile);
                finishWithoutChanges();
                return;
            }

            imageUri = Uri.fromFile(inputFile);
            outputUri = Uri.fromFile(outputFile);

            createBackground(inputFile, Color.WHITE);
        } else {
            if (mediaItem == null || mediaItem.getUri() == null) {
                logger.error("No media uri given");
                finishWithoutChanges();
                return;
            }
            this.imageUri = mediaItem.getUri();
            loadImageOnLayout();
        }

        // Don't show tooltip when creating a drawing or for image replies
        if (activityMode == ActivityMode.EDIT_IMAGE) {
            showTooltip();
        }
    }

    /**
     * Create a file that is used for the drawing input (the background)
     */
    private File createDrawingInputFile() {
        try {
            return serviceManager.getFileService().createTempFile(".blank", ".png");
        } catch (IOException e) {
            logger.error("Error while creating temporary drawing input file");
            return null;
        }
    }

    /**
     * Create a file that is used for the resulting output image (background + drawings)
     */
    private File createDrawingOutputFile() {
        try {
            return serviceManager.getFileService().createTempFile(".drawing", ".png");
        } catch (IOException e) {
            logger.error("Error while creating temporary drawing output file", e);
            return null;
        }
    }

    /**
     * Create a background with the given color and store it into the given file. Afterwards display
     * the background.
     *
     * @param inputFile the file where the background is stored
     * @param color     the color of the background
     */
    private void createBackground(File inputFile, int color) {
        Futures.addCallback(
            getDrawingImageFuture(inputFile, color),
            new FutureCallback<>() {
                @Override
                public void onSuccess(@Nullable Void result) {
                    loadImageOnLayout();
                }

                @Override
                public void onFailure(@NonNull Throwable t) {
                    logger.error("Error while getting the image uri", t);
                    finishWithoutChanges();
                }
            },
            ContextCompat.getMainExecutor(this)
        );
    }

    /**
     * Get a listenable future that creates a background image of the given color and stores it in
     * the given file.
     *
     * @param file  the file where the image of the given color is stored in
     * @param color the color of the background
     * @return the listenable future
     */
    private ListenableFuture<Void> getDrawingImageFuture(@NonNull File file, int color) {
        ListeningExecutorService executorService = MoreExecutors.listeningDecorator(threadPoolExecutor);
        return executorService.submit(() -> {
            try {
                int dimension = ConfigUtils.getPreferredImageDimensions(PreferenceService.ImageScale_MEDIUM);
                Bitmap bitmap = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.RGB_565);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(color);
                bitmap.compress(Bitmap.CompressFormat.PNG, 0, new FileOutputStream(file));
            } catch (IOException e) {
                logger.error("Exception while creating blanc drawing", e);
            }
            return null;
        });
    }

    private void loadImage(@Nullable Runnable onLoaded) {
        BitmapWorkerTaskParams bitmapParams = new BitmapWorkerTaskParams();
        bitmapParams.imageUri = this.imageUri;
        bitmapParams.width = this.imageFrame.getWidth();
        bitmapParams.height = this.scrollView.getHeight();
        bitmapParams.contentResolver = getContentResolver();
        if (mediaItem != null) {
            bitmapParams.orientation = mediaItem.getRotation();
            bitmapParams.flip = mediaItem.getFlip();
            bitmapParams.exifOrientation = mediaItem.getExifRotation();
            bitmapParams.exifFlip = mediaItem.getExifFlip();
        }

        logger.debug("screen height: {}", bitmapParams.height);

        // load main image
        new BitmapWorkerTask(this.imageView) {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                super.onPostExecute(bitmap);
                progressBar.setVisibility(View.GONE);

                // clip other views to image size
                if (bitmap != null) {
                    clipWidth = bitmap.getWidth();
                    clipHeight = bitmap.getHeight();

                    paintView.recalculate(clipWidth, clipHeight);
                    resizeView(paintView, clipWidth, clipHeight);
                    resizeView(motionView, clipWidth, clipHeight);
                }

                if (onLoaded != null) {
                    onLoaded.run();
                }
            }
        }.execute(bitmapParams);
    }

    private void resizeView(View view, int width, int height) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.width = width;
        params.height = height;

        view.requestLayout();
    }

    private void selectSticker() {
        startActivityForResult(new Intent(this, StickerSelectorActivity.class), REQUEST_CODE_STICKER_SELECTOR);
        overridePendingTransition(R.anim.abc_fade_in, R.anim.abc_fade_out);
    }

    private void enterText() {
        Intent intent = new Intent(this, ImagePaintKeyboardActivity.class);
        intent.putExtra(ImagePaintKeyboardActivity.INTENT_EXTRA_COLOR, penColor);
        startActivityForResult(intent, REQUEST_CODE_ENTER_TEXT);
        overridePendingTransition(R.anim.abc_fade_in, R.anim.abc_fade_out);
    }

    @SuppressLint("StaticFieldLeak")
    private void blurFaces(final boolean useEmoji) {
        this.paintView.setActive(false);

        new AsyncTask<Void, Void, List<FaceItem>>() {
            int numFaces = -1;
            int originalImageWidth, originalImageHeight;

            @Override
            protected void onPreExecute() {
                GenericProgressDialog.newInstance(0, R.string.please_wait).show(getSupportFragmentManager(), DIALOG_TAG_BLUR_FACES);
            }

            @Override
            protected List<FaceItem> doInBackground(Void... voids) {
                BitmapFactory.Options options;
                Bitmap bitmap, orgBitmap;
                List<FaceItem> faceItemList = new ArrayList<>();

                try (InputStream measure = getContentResolver().openInputStream(imageUri)) {
                    options = BitmapUtil.getImageDimensions(measure);
                } catch (IOException | SecurityException | IllegalStateException |
                         OutOfMemoryError e) {
                    logger.error("Exception", e);
                    return null;
                }

                if (options.outWidth < 16 || options.outHeight < 16) {
                    return null;
                }

                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                options.inJustDecodeBounds = false;

                int orientation = 0, flip = FLIP_NONE, exifOrientation = 0, exifFlip = FLIP_NONE;
                if (mediaItem != null) {
                    orientation = mediaItem.getRotation();
                    flip = mediaItem.getFlip();
                    exifOrientation = mediaItem.getExifRotation();
                    exifFlip = mediaItem.getExifFlip();
                }

                try (InputStream data = getContentResolver().openInputStream(imageUri)) {
                    if (data != null) {
                        orgBitmap = BitmapFactory.decodeStream(new BufferedInputStream(data), null, options);
                        if (orgBitmap != null) {
                            if (exifOrientation != 0 || exifFlip != FLIP_NONE) {
                                orgBitmap = BitmapUtil.rotateBitmap(orgBitmap, exifOrientation, exifFlip);
                            }
                            if (orientation != 0 || flip != FLIP_NONE) {
                                orgBitmap = BitmapUtil.rotateBitmap(orgBitmap, orientation, flip);
                            }
                            bitmap = Bitmap.createBitmap(orgBitmap.getWidth() & ~0x1, orgBitmap.getHeight(), Bitmap.Config.RGB_565);
                            new Canvas(bitmap).drawBitmap(orgBitmap, 0, 0, null);

                            originalImageWidth = orgBitmap.getWidth();
                            originalImageHeight = orgBitmap.getHeight();
                        } else {
                            logger.info("could not open image");
                            return null;
                        }
                    } else {
                        logger.info("could not open input stream");
                        return null;
                    }
                } catch (Exception e) {
                    logger.error("Exception", e);
                    return null;
                }

                try {
                    FaceDetector faceDetector = new FaceDetector(bitmap.getWidth(), bitmap.getHeight(), MAX_FACES);
                    FaceDetector.Face[] faces = new FaceDetector.Face[MAX_FACES];

                    numFaces = faceDetector.findFaces(bitmap, faces);
                    if (numFaces < 1) {
                        return null;
                    }

                    logger.debug("{} faces found.", numFaces);

                    Bitmap emoji = null;
                    if (useEmoji) {
                        emoji = BitmapFactory.decodeStream(getAssets().open(SMILEY_PATH));
                    }

                    for (FaceDetector.Face face : faces) {
                        if (face != null) {
                            if (useEmoji) {
                                faceItemList.add(new FaceItem(face, emoji, 1));
                            } else {
                                float offsetY = face.eyesDistance() * FaceEntity.BLUR_RADIUS;
                                PointF midPoint = new PointF();
                                face.getMidPoint(midPoint);

                                int croppedBitmapSize = (int) (offsetY * 2);
                                float scale = 1f;
                                // pixelize large bitmaps
                                if (croppedBitmapSize > 64) {
                                    scale = (float) croppedBitmapSize / 64f;
                                }

                                float scaleFactor = 1f / scale;
                                Matrix matrix = new Matrix();
                                matrix.setScale(scaleFactor, scaleFactor);

                                Bitmap croppedBitmap = Bitmap.createBitmap(
                                    orgBitmap,
                                    offsetY > midPoint.x ? 0 : (int) (midPoint.x - offsetY),
                                    offsetY > midPoint.y ? 0 : (int) (midPoint.y - offsetY),
                                    croppedBitmapSize,
                                    croppedBitmapSize,
                                    matrix,
                                    false);

                                faceItemList.add(new FaceItem(face, croppedBitmap, scale));
                            }
                        }
                    }

                    return faceItemList;
                } catch (Exception e) {
                    logger.error("Face detection failed", e);
                    return null;
                } finally {
                    bitmap.recycle();
                }
            }

            @Override
            protected void onPostExecute(List<FaceItem> faceItemList) {
                if (faceItemList != null && !faceItemList.isEmpty()) {
                    motionView.post(() -> {
                        for (FaceItem faceItem : faceItemList) {
                            Layer layer = new Layer();
                            if (useEmoji) {
                                FaceEmojiEntity entity = new FaceEmojiEntity(layer, faceItem, originalImageWidth, originalImageHeight, motionView.getWidth(), motionView.getHeight());
                                motionView.addEntity(entity);
                            } else {
                                FaceBlurEntity entity = new FaceBlurEntity(layer, faceItem, originalImageWidth, originalImageHeight, motionView.getWidth(), motionView.getHeight());
                                motionView.addEntity(entity);
                            }
                        }
                    });
                } else {
                    Toast.makeText(ImagePaintActivity.this, R.string.no_faces_detected, Toast.LENGTH_LONG).show();
                }

                DialogUtil.dismissDialog(getSupportFragmentManager(), DIALOG_TAG_BLUR_FACES, true);
            }
        }.execute();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        switch (this.strokeMode) {
            case STROKE_MODE_PENCIL:
                drawParentItem.setIcon(pencilIcon);
                break;
            case STROKE_MODE_HIGHLIGHTER:
                drawParentItem.setIcon(highlighterIcon);
                break;
            default:
                drawParentItem.setIcon(brushIcon);
                break;
        }

        ConfigUtils.tintMenuIcon(this, drawParentItem, R.attr.colorOnSurface);
        ConfigUtils.tintMenuIcon(this, paintItem, R.attr.colorOnSurface);
        ConfigUtils.tintMenuIcon(this, pencilItem, R.attr.colorOnSurface);
        ConfigUtils.tintMenuIcon(this, highlighterItem, R.attr.colorOnSurface);

        if (motionView.getSelectedEntity() == null) {
            // no selected entities => draw mode or neutral mode
            if (paintView.getActive()) {
                switch (this.strokeMode) {
                    case STROKE_MODE_PENCIL:
                        ConfigUtils.tintMenuIcon(pencilItem, this.penColor);
                        break;
                    case STROKE_MODE_HIGHLIGHTER:
                        ConfigUtils.tintMenuIcon(highlighterItem, this.penColor);
                        break;
                    default:
                        ConfigUtils.tintMenuIcon(paintItem, this.penColor);
                        break;
                }
                ConfigUtils.tintMenuIcon(drawParentItem, this.penColor);
            }
        }
        undoItem.setVisible(hasChanges());
        blurFacesItem.setVisible(activityMode != ActivityMode.DRAWING && motionView.getEntitiesCount() == 0);

        if (activityMode == ActivityMode.IMAGE_REPLY) {
            // Cropping is currently not possible when the image already has been edited. However,
            // if the image has only been rotated or flipped, it is still possible to crop it.
            cropItem.setVisible(true);
            for (ActionEntity action : undoHistory) {
                if (!allowedActionsEntitiesToCrop.contains(action.getClass())) {
                    cropItem.setVisible(false);
                    break;
                }
            }
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.activity_image_paint, menu);

        undoItem = menu.findItem(R.id.item_undo);
        drawParentItem = menu.findItem(R.id.item_draw_parent);
        paintItem = menu.findItem(R.id.item_draw);
        pencilItem = menu.findItem(R.id.item_pencil);
        highlighterItem = menu.findItem(R.id.item_highlighter);
        blurFacesItem = menu.findItem(R.id.item_face);

        if (activityMode == ActivityMode.DRAWING) {
            menu.findItem(R.id.item_background).setVisible(true);
        } else if (activityMode == ActivityMode.IMAGE_REPLY) {
            menu.findItem(R.id.item_flip).setVisible(true);
            menu.findItem(R.id.item_rotate).setVisible(true);
            cropItem = menu.findItem(R.id.item_crop);
            cropItem.setVisible(true);
        }

        ConfigUtils.addIconsToOverflowMenu(menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        super.onOptionsItemSelected(item);

        int id = item.getItemId();
        if (id == android.R.id.home) {
            if (hasChanges()) {
                item.setEnabled(false);
                renderImage();
            } else {
                finishWithoutChanges();
            }
            return true;
        } else if (id == R.id.item_undo) {
            undo();
        } else if (id == R.id.item_stickers) {
            selectSticker();
        } else if (id == R.id.item_palette) {
            choosePenColor();
        } else if (id == R.id.item_text) {
            enterText();
        } else if (id == R.id.item_draw) {
            if (strokeMode == STROKE_MODE_BRUSH && this.paintView.getActive()) {
                // switch to selection mode
                setDrawMode(false);
            } else {
                setStrokeMode(STROKE_MODE_BRUSH);
                setDrawMode(true);
            }
        } else if (id == R.id.item_pencil) {
            if (strokeMode == STROKE_MODE_PENCIL && this.paintView.getActive()) {
                // switch to selection mode
                setDrawMode(false);
            } else {
                setStrokeMode(STROKE_MODE_PENCIL);
                setDrawMode(true);
            }
        } else if (id == R.id.item_highlighter) {
            if (strokeMode == STROKE_MODE_HIGHLIGHTER && this.paintView.getActive()) {
                // switch to selection mode
                setDrawMode(false);
            } else {
                setStrokeMode(STROKE_MODE_HIGHLIGHTER);
                setDrawMode(true);
            }
        } else if (id == R.id.item_face_blur) {
            blurFaces(false);
        } else if (id == R.id.item_face_emoji) {
            blurFaces(true);
        } else if (id == R.id.item_background) {
            chooseBackgroundColor();
        } else if (id == R.id.item_flip) {
            if (lastAnimationStart + ANIMATION_DURATION_MS < System.currentTimeMillis()) {
                flip();
                lastAnimationStart = System.currentTimeMillis();
            }
        } else if (id == R.id.item_rotate) {
            if (lastAnimationStart + ANIMATION_DURATION_MS < System.currentTimeMillis()) {
                rotate();
                lastAnimationStart = System.currentTimeMillis();
            }
        } else if (id == R.id.item_crop) {
            crop();
        }
        return false;
    }

    @UiThread
    public void showTooltip() {
        if (!preferenceService.getIsFaceBlurTooltipShown()) {
            if (getToolbar() != null) {
                getToolbar().postDelayed(() -> {
                    final View v = findViewById(R.id.item_face);
                    final @ColorInt int textColor = ConfigUtils.getColorFromAttribute(this, R.attr.colorOnPrimary);
                    try {
                        TapTargetView.showFor(this,
                            TapTarget.forView(v, getString(R.string.face_blur_tooltip_title), getString(R.string.face_blur_tooltip_text))
                                .outerCircleColorInt(ConfigUtils.getColorFromAttribute(this, R.attr.colorPrimary)) // Specify a color for the outer circle
                                .outerCircleAlpha(0.96f)            // Specify the alpha amount for the outer circle
                                .targetCircleColor(android.R.color.white)   // Specify a color for the target circle
                                .titleTextSize(24)                  // Specify the size (in sp) of the title text
                                .titleTextColorInt(textColor)      // Specify the color of the title text
                                .descriptionTextSize(18)            // Specify the size (in sp) of the description text
                                .descriptionTextColorInt(textColor)  // Specify the color of the description text
                                .textColorInt(textColor)            // Specify a color for both the title and description text
                                .textTypeface(Typeface.SANS_SERIF)  // Specify a typeface for the text
                                .dimColor(android.R.color.black)            // If set, will dim behind the view with 30% opacity of the given color
                                .drawShadow(true)                   // Whether to draw a drop shadow or not
                                .cancelable(true)                  // Whether tapping outside the outer circle dismisses the view
                                .tintTarget(true)                   // Whether to tint the target view's color
                                .transparentTarget(false)           // Specify whether the target is transparent (displays the content underneath)
                                .targetRadius(50)                  // Specify the target radius (in dp)
                        );
                        preferenceService.setFaceBlurTooltipShown(true);
                    } catch (Exception ignore) {
                        // catch null typeface exception on CROSSCALL Action-X3
                    }
                }, 2000);
            }
        }
    }

    private void setStrokeMode(int strokeMode) {
        this.strokeMode = strokeMode;
        @DimenRes int strokeWidthDimension;
        switch (strokeMode) {
            case STROKE_MODE_HIGHLIGHTER:
                paintView.setTransparent(true);
                strokeWidthDimension = R.dimen.imagepaint_highlighter_stroke_width;
                break;
            case STROKE_MODE_PENCIL:
                paintView.setTransparent(false);
                strokeWidthDimension = R.dimen.imagepaint_pencil_stroke_width;
                break;
            default:
                paintView.setTransparent(false);
                strokeWidthDimension = R.dimen.imagepaint_brush_stroke_width;
                break;

        }
        this.paintView.setStrokeWidth(getResources().getDimensionPixelSize(strokeWidthDimension));
    }

    private void deleteEntity() {
        motionView.deletedSelectedEntity();
        invalidateOptionsMenu();
    }

    private void flipEntity() {
        motionView.flipSelectedEntity();
        invalidateOptionsMenu();
    }

    private void bringToFrontEntity() {
        motionView.moveSelectedEntityToFront();
        invalidateOptionsMenu();
    }

    private void colorEntity() {
        final MotionEntity selectedEntity = motionView.getSelectedEntity();
        if (selectedEntity == null) {
            logger.warn("Cannot change entity color when no entity is selected");
            return;
        }
        chooseColor(selectedEntity::setColor, selectedEntity.getColor());
    }

    private void undo() {
        if (hasChanges() && lastAnimationStart + ANIMATION_DURATION_MS + 100 < System.currentTimeMillis()) {
            ActionEntity entity = undoHistory.pop();

            motionView.unselectEntity();
            if (entity instanceof PathEntity) {
                paintView.undo();
            } else if (entity instanceof RotationEntity) {
                undoRotate();
            } else if (entity instanceof FlipEntity) {
                undoFlip();
            } else if (entity instanceof CropEntity) {
                undoCrop((CropEntity) entity);
            } else if (entity instanceof MotionEntity) {
                motionView.deleteEntity((MotionEntity) entity);
            }
            invalidateOptionsMenu();
            lastAnimationStart = System.currentTimeMillis();
        }
    }

    private void setDrawMode(boolean enable) {
        if (enable) {
            motionView.unselectEntity();
            paintView.setActive(true);
        } else {
            paintView.setActive(false);
        }
        invalidateOptionsMenu();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // hack to adjust toolbar height after rotate
        ConfigUtils.adjustToolbar(this, getToolbar());

        loadImageOnLayout();
    }

    /**
     * Updates the image frame height on next layout of the scroll view
     */
    private void loadImageOnLayout() {
        if (scrollView == null || imageFrame == null) {
            logger.warn("scrollView or imageFrame is null");
            return;
        }
        scrollView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int softKeyboardHeight = 0;
                if (isSoftKeyboardOpen()) {
                    softKeyboardHeight = loadStoredSoftKeyboardHeight();
                }

                // If soft keyboard is open then add its height to the image frame
                imageFrame.setMinimumHeight(bottom - top + softKeyboardHeight);

                // If the image frame is larger than it's parent (scroll view), we need to wait for another relayout.
                // Otherwise we can remove this listener and load the image
                if (imageFrame.getMinimumHeight() <= scrollView.getHeight()) {
                    scrollView.removeOnLayoutChangeListener(this);
                    loadImage(null);
                }
            }
        });
        scrollView.requestLayout();
    }

    /**
     * Show a color picker and set the selected color as pen color
     */
    private void choosePenColor() {
        chooseColor(color -> {
            paintView.setColor(color);
            penColor = color;
            setDrawMode(true);
        }, penColor);
    }

    /**
     * Show a color picker and writes the selected color to the input file.
     */
    private void chooseBackgroundColor() {
        chooseColor(color -> {
            backgroundColor = color;
            createBackground(inputFile, color);
        }, backgroundColor);
    }

    private void chooseColor(@NonNull ColorPickerSwatch.OnColorSelectedListener colorSelectedListener, int selectedColor) {
        int[] colors = {
            getResources().getColor(R.color.material_cyan),
            getResources().getColor(R.color.material_blue),
            getResources().getColor(R.color.material_indigo),
            getResources().getColor(R.color.material_deep_purple),
            getResources().getColor(R.color.material_purple),
            getResources().getColor(R.color.material_pink),
            getResources().getColor(R.color.material_red),
            getResources().getColor(R.color.material_orange),
            getResources().getColor(R.color.material_amber),
            getResources().getColor(R.color.material_yellow),
            getResources().getColor(R.color.material_lime),
            getResources().getColor(R.color.material_green),
            getResources().getColor(R.color.material_green_700),
            getResources().getColor(R.color.material_teal),
            getResources().getColor(R.color.material_brown),
            getResources().getColor(R.color.material_grey_600),
            getResources().getColor(R.color.material_grey_500),
            getResources().getColor(R.color.material_grey_300),
            Color.WHITE,
            Color.BLACK,
        };

        ColorPickerDialog colorPickerDialog = new ColorPickerDialog();
        colorPickerDialog.initialize(R.string.color_picker_default_title, colors, selectedColor, 4, colors.length);
        colorPickerDialog.setOnColorSelectedListener(colorSelectedListener);
        colorPickerDialog.show(getSupportFragmentManager(), DIALOG_TAG_COLOR_PICKER);
    }

    private void renderImage() {
        logger.debug("renderImage");
        if (saveSemaphore) {
            return;
        }

        saveSemaphore = true;

        BitmapWorkerTaskParams bitmapParams = new BitmapWorkerTaskParams();
        bitmapParams.imageUri = this.imageUri;
        bitmapParams.contentResolver = getContentResolver();
        if (mediaItem != null) {
            bitmapParams.orientation = mediaItem.getRotation();
            bitmapParams.flip = mediaItem.getFlip();
            bitmapParams.exifOrientation = mediaItem.getExifRotation();
            bitmapParams.exifFlip = mediaItem.getExifFlip();
        }
        bitmapParams.mutable = true;

        new BitmapWorkerTask(null) {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();

                String message = String.format(ConfigUtils.getSafeQuantityString(ImagePaintActivity.this, R.plurals.saving_media, 1, 1));
                String title = getString(R.string.draw);
                GenericProgressDialog.newInstance(title, message).show(getSupportFragmentManager(), DIALOG_TAG_SAVING_IMAGE);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                Canvas canvas = new Canvas(bitmap);
                motionView.renderOverlay(canvas);
                paintView.renderOverlay(canvas, clipWidth, clipHeight);

                new AsyncTask<Bitmap, Void, Boolean>() {

                    @Override
                    protected Boolean doInBackground(Bitmap... params) {
                        try {
                            File output = new File(outputUri.getPath());

                            FileOutputStream outputStream = new FileOutputStream(output);
                            Matrix matrix = currentOrientation.getTransformationMatrix();
                            Bitmap transformed = Bitmap.createBitmap(params[0], 0, 0, params[0].getWidth(), params[0].getHeight(), matrix, true);
                            transformed.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                            outputStream.flush();
                            outputStream.close();
                        } catch (Exception e) {
                            return false;
                        }
                        return true;
                    }

                    @Override
                    protected void onPostExecute(Boolean success) {
                        DialogUtil.dismissDialog(getSupportFragmentManager(), DIALOG_TAG_SAVING_IMAGE, true);

                        if (success) {
                            finishWithChanges();
                        } else {
                            Toast.makeText(ImagePaintActivity.this, R.string.error_saving_file, Toast.LENGTH_SHORT).show();
                        }
                    }
                }.execute(bitmap);
            }
        }.execute(bitmapParams);
    }

    private void initializeCaptionEditText() {
        if (activityMode == ActivityMode.EDIT_IMAGE) {
            // Don't show caption edit text when just editing the image
            return;
        }

        captionEditText = findViewById(R.id.caption_edittext);
        captionEditText.setOnClickListener(v -> {
            if (emojiPicker != null) {
                if (emojiPicker.isShown()) {
                    if (ConfigUtils.isLandscape(this) &&
                        !ConfigUtils.isTabletLayout()) {
                        emojiPicker.hide();
                    } else {
                        openSoftKeyboard(captionEditText);
                        runOnSoftKeyboardOpen(() -> emojiPicker.hide());
                    }
                }
            }
        });

        SendButton sendButton = findViewById(R.id.send_button);
        sendButton.setEnabled(true);
        sendButton.setOnClickListener(v -> renderImage());

        View bottomPanel = findViewById(R.id.bottom_panel);
        bottomPanel.setVisibility(View.VISIBLE);

        if (ConfigUtils.isDefaultEmojiStyle()) {
            initializeEmojiView();
        } else {
            findViewById(R.id.emoji_button).setVisibility(View.GONE);
            captionEditText.setPadding(getResources().getDimensionPixelSize(R.dimen.no_emoji_button_padding_left), this.captionEditText.getPaddingTop(), this.captionEditText.getPaddingRight(), this.captionEditText.getPaddingBottom());
        }

        if (groupId != -1) {
            initializeMentions();
        }

    }

    private void initializeMentions() {
        ServiceManager serviceManager = ThreemaApplication.getServiceManager();
        if (serviceManager == null) {
            logger.error("Cannot enable mention popup: serviceManager is null");
            return;
        }
        try {
            GroupService groupService = serviceManager.getGroupService();
            ContactService contactService = serviceManager.getContactService();
            UserService userService = serviceManager.getUserService();
            GroupModelRepository groupModelRepository = serviceManager.getModelRepositories().getGroups();
            ch.threema.data.models.GroupModel groupModel = groupModelRepository.getByLocalGroupDbId(groupId);

            if (groupModel == null) {
                logger.error("Cannot enable mention popup: no group model with id {} found", groupId);
                return;
            }

            captionEditText.enableMentionPopup(
                this,
                groupService,
                contactService,
                userService,
                preferenceService,
                groupModel,
                null
            );
        } catch (MasterKeyLockedException e) {
            logger.error("Cannot enable mention popup", e);
        }
    }

    @SuppressWarnings("deprecation")
    private void initializeEmojiView() {
        final EmojiPicker.EmojiKeyListener emojiKeyListener = new EmojiPicker.EmojiKeyListener() {
            @Override
            public void onBackspaceClick() {
                captionEditText.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
            }

            @Override
            public void onEmojiClick(String emojiCodeString) {
                RuntimeUtil.runOnUiThread(() -> captionEditText.addEmoji(emojiCodeString));
            }

            @Override
            public void onShowPicker() {
                logger.info("onShowPicker");
                showEmojiPicker();
            }
        };

        EmojiButton emojiButton = findViewById(R.id.emoji_button);
        emojiButton.setOnClickListener(v -> showEmojiPicker());
        emojiButton.setColorFilter(getResources().getColor(android.R.color.white));

        emojiPicker = (EmojiPicker) ((ViewStub) findViewById(R.id.emoji_stub)).inflate();
        emojiPicker.init(this, ThreemaApplication.requireServiceManager().getEmojiService(), false);
        emojiButton.attach(this.emojiPicker);
        emojiPicker.setEmojiKeyListener(emojiKeyListener);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.image_paint_root).getRootView(), (v, insets) -> {
            if (insets.getSystemWindowInsetBottom() <= insets.getStableInsetBottom()) {
                onSoftKeyboardClosed();
            } else {
                onSoftKeyboardOpened(insets.getSystemWindowInsetBottom() - insets.getStableInsetBottom());
            }
            return insets;
        });

        addOnSoftKeyboardChangedListener(new OnSoftKeyboardChangedListener() {
            @Override
            public void onKeyboardHidden() {
                // Nothing to do
            }

            @Override
            public void onKeyboardShown() {
                if (emojiPicker != null && emojiPicker.isShown()) {
                    emojiPicker.onKeyboardShown();
                }
            }
        });
    }

    private void showEmojiPicker() {
        if (isSoftKeyboardOpen() && !isEmojiPickerShown()) {
            logger.info("Show emoji picker after keyboard close");
            runOnSoftKeyboardClose(() -> {
                if (emojiPicker != null) {
                    emojiPicker.show(loadStoredSoftKeyboardHeight());
                }
            });

            captionEditText.post(() -> EditTextUtil.hideSoftKeyboard(captionEditText));
        } else {
            if (emojiPicker != null) {
                if (emojiPicker.isShown()) {
                    logger.info("EmojiPicker currently shown. Closing.");
                    if (ConfigUtils.isLandscape(this) &&
                        !ConfigUtils.isTabletLayout()) {
                        emojiPicker.hide();
                    } else {
                        openSoftKeyboard(captionEditText);
                        runOnSoftKeyboardOpen(() -> emojiPicker.hide());
                    }
                } else {
                    emojiPicker.show(loadStoredSoftKeyboardHeight());
                }
            }
        }
    }

    private boolean isEmojiPickerShown() {
        return emojiPicker != null && emojiPicker.isShown();
    }

    private void flip() {
        flipViewsAnimated();
        if (hasChanges() && undoHistory.peek() instanceof FlipEntity) {
            // Remove the previous flip action instead of creating two consecutive flip actions
            undoHistory.pop();
        } else {
            undoHistory.push(new FlipEntity());
        }
        invalidateOptionsMenu();
    }

    private void undoFlip() {
        flipViewsAnimated();
    }

    private void flipViewsAnimated() {
        int previousFlip = currentOrientation.getFlip();
        currentOrientation.flip();
        int currentFlip = currentOrientation.getFlip();

        flipViewAnimated(imageView, previousFlip, currentFlip);
        flipViewAnimated(motionView, previousFlip, currentFlip);
        flipViewAnimated(paintView, previousFlip, currentFlip);
    }

    private void flipViewAnimated(@NonNull View view, int previousFlip, int newFlip) {
        if ((previousFlip & FLIP_HORIZONTAL) != (newFlip & FLIP_HORIZONTAL)) {
            flipViewHorizontalAnimated(view);
        }
        if ((previousFlip & FLIP_VERTICAL) != (newFlip & FLIP_VERTICAL)) {
            flipViewVerticalAnimated(view);
        }
    }

    private void flipViewHorizontalAnimated(@NonNull View view) {
        view.animate().scaleX(view.getScaleX() * -1f).setDuration(ANIMATION_DURATION_MS).start();
    }

    private void flipViewVerticalAnimated(@NonNull View view) {
        view.animate().scaleY(view.getScaleY() * -1f).setDuration(ANIMATION_DURATION_MS).start();
    }

    private void resetViewOrientation(@NonNull View view) {
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setRotation(0f);
    }

    private boolean isFlippedHorizontally() {
        return imageView.getScaleX() < 0f;
    }

    private boolean isFlippedVertically() {
        return imageView.getScaleY() < 0f;
    }

    private void rotate() {
        rotateBy(-90);
        undoHistory.push(new RotationEntity());
        invalidateOptionsMenu();
    }

    private void undoRotate() {
        rotateBy(90);
    }

    private void rotateBy(int degrees) {
        // Rotate views
        currentOrientation.rotateBy(degrees);

        rotateViewAnimated(imageView, degrees);
        rotateViewAnimated(motionView, degrees);
        rotateViewAnimated(paintView, degrees);
    }

    private void rotateViewAnimated(@NonNull View view, int degrees) {
        int rotation = ((int) view.getRotation()) % 360;
        if (rotation < 0) {
            rotation += 360;
        }
        boolean invertedDimensions = rotation == 90 || rotation == 270;
        float newWidth = invertedDimensions ? view.getWidth() : view.getHeight();
        float newHeight = invertedDimensions ? view.getHeight() : view.getWidth();
        float scale = getTargetScale(newWidth, newHeight);
        float xScaleNormalized = view.getScaleX() < 0 ? -1 : 1;
        float yScaleNormalized = view.getScaleY() < 0 ? -1 : 1;

        view.animate()
            .rotationBy(degrees)
            .scaleX(xScaleNormalized * scale)
            .scaleY(yScaleNormalized * scale)
            .setDuration(ANIMATION_DURATION_MS)
            .start();
    }

    private float getTargetScale(float width, float height) {
        float parentWidth = scrollView.getWidth();
        float parentHeight = scrollView.getHeight();
        return Math.min(parentWidth / width, parentHeight / height);
    }

    private void crop() {
        try {
            ServiceManager serviceManager = ThreemaApplication.getServiceManager();
            if (serviceManager == null) {
                logger.error("Service manager is null");
                return;
            }
            FileService fileService = serviceManager.getFileService();
            cropFile = fileService.createTempFile(".crop", ".png");

            Intent intent = new Intent(this, CropImageActivity.class);
            intent.setData(imageUri);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(cropFile));
            // The rotation and flip to load the image 'correctly'
            intent.putExtra(AppConstants.EXTRA_ORIENTATION, mediaItem.getRotation());
            intent.putExtra(AppConstants.EXTRA_FLIP, mediaItem.getFlip());
            // The rotation and flip that has been applied in the image paint activity
            intent.putExtra(CropImageActivity.EXTRA_ADDITIONAL_ORIENTATION, currentOrientation.getRotation());
            intent.putExtra(CropImageActivity.EXTRA_ADDITIONAL_FLIP, currentOrientation.getFlip());
            intent.putExtra(CropImageActivity.FORCE_DARK_THEME, true);

            cropResultLauncher.launch(intent);
        } catch (IOException e) {
            logger.debug("Unable to create temp file for crop");
        }
    }

    private void undoCrop(@NonNull CropEntity cropEntity) {
        imageView.setAlpha(0f);
        motionView.setAlpha(0f);
        paintView.setAlpha(0f);

        resetViewOrientation(imageView);
        resetViewOrientation(motionView);
        resetViewOrientation(paintView);

        imageUri = cropEntity.getLastUri();
        mediaItem.setUri(imageUri);
        mediaItem.setRotation(cropEntity.getOrientation().getRotation());
        mediaItem.setFlip(cropEntity.getOrientation().getFlip());
        loadImage(() -> {
            applyCurrentOrientation();
            animateFadeIn(imageView);
            animateFadeIn(motionView);
            animateFadeIn(paintView);
        });
    }

    private void animateFadeIn(@NonNull View view) {
        view.animate()
            .alpha(1f)
            .setDuration(ANIMATION_DURATION_MS)
            .start();
    }

    private MediaItem.Orientation getSwappedFlips(@NonNull MediaItem.Orientation orientation) {
        MediaItem.Orientation swappedOrientation = new MediaItem.Orientation(orientation.getRotation(), FLIP_NONE);
        boolean isHorizontalFlip = orientation.isHorizontalFlip();
        boolean isVerticalFlip = orientation.isVerticalFlip();
        swappedOrientation.setFlip(
            (isHorizontalFlip ? FLIP_VERTICAL : FLIP_NONE)
                | (isVerticalFlip ? FLIP_HORIZONTAL : FLIP_NONE)
        );
        return swappedOrientation;
    }

    private void applyCurrentOrientation() {
        imageView.setRotation(currentOrientation.getRotation());
        motionView.setRotation(currentOrientation.getRotation());
        paintView.setRotation(currentOrientation.getRotation());

        float scaleX = currentOrientation.isHorizontalFlip() ? -1 : 1;
        float scaleY = currentOrientation.isVerticalFlip() ? -1 : 1;

        boolean inverted = currentOrientation.getRotation() == 90 || currentOrientation.getRotation() == 270;
        float width = inverted ? imageView.getHeight() : imageView.getWidth();
        float height = inverted ? imageView.getWidth() : imageView.getHeight();
        float scale = getTargetScale(width, height);

        imageView.setScaleX(scaleX * scale);
        imageView.setScaleY(scaleY * scale);
        motionView.setScaleX(scaleX * scale);
        motionView.setScaleY(scaleY * scale);
        paintView.setScaleX(scaleX * scale);
        paintView.setScaleY(scaleY * scale);
    }


    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_PEN_COLOR, penColor);
        outState.putInt(KEY_BACKGROUND_COLOR, backgroundColor);
    }

    @Override
    public void onYes(String tag, Object data) {
        finishWithoutChanges();
    }

    /**
     * Finish activity with changes (result ok)
     */
    private void finishWithChanges() {
        if (activityMode == ActivityMode.IMAGE_REPLY || activityMode == ActivityMode.DRAWING) {
            MediaItem mediaItem = new MediaItem(outputUri, MediaItem.TYPE_IMAGE);
            if (captionEditText != null && captionEditText.getText() != null) {
                mediaItem.setCaption(captionEditText.getText().toString());
            }

            Intent result = new Intent();
            boolean messageReceiverCopied = IntentDataUtil.copyMessageReceiverFromIntentToIntent(this, getIntent(), result);
            if (!messageReceiverCopied) {
                logger.warn("Could not copy message receiver to intent");
                finishWithoutChanges();
                return;
            }
            result.putExtra(Intent.EXTRA_STREAM, mediaItem);

            setResult(RESULT_OK, result);
        } else {
            setResult(RESULT_OK);
        }
        finish();
    }

    /**
     * Finish activity without changes (result canceled)
     */
    private void finishWithoutChanges() {
        setResult(RESULT_CANCELED);
        finish();
    }

}
