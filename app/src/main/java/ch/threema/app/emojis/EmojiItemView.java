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

package ch.threema.app.emojis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import android.util.AttributeSet;
import android.view.View;

public class EmojiItemView extends View implements Drawable.Callback {
    private String emoji;
    private boolean hasDiverse;
    @ColorInt
    private int diverseColor;
    private Drawable drawable;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public EmojiItemView(Context context) {
        this(context, null);
    }

    public EmojiItemView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EmojiItemView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public Drawable setEmoji(String emoji, boolean hasDiverse, @ColorInt int diverseColor) {
        this.emoji = emoji;
        this.drawable = EmojiManager.getInstance(getContext()).getEmojiDrawableAsync(emoji);
        this.hasDiverse = hasDiverse;
        this.diverseColor = diverseColor;

        postInvalidate();

        return this.drawable;
    }

    public String getEmoji() {
        return emoji;
    }

    private void drawCenteredCharacter(Canvas canvas, String character) {
        Paint centeredCharacterPaint = new Paint();
        centeredCharacterPaint.setTextAlign(Paint.Align.CENTER);

        // Measure text bounds to get width and height
        Rect bounds = new Rect();
        centeredCharacterPaint.getTextBounds(character, 0, 1, bounds);
        int textWidth = bounds.width();
        int textHeight = bounds.height();

        // Get canvas dimensions
        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();

        // Calculate scaling factor to fit text within canvas
        float scaleX = (float) canvasWidth / textWidth;
        float scaleY = (float) canvasHeight / textHeight;
        float scaleFactor = Math.min(scaleX, scaleY);

        // Apply scaling to text size
        centeredCharacterPaint.setTextSize(centeredCharacterPaint.getTextSize() * scaleFactor);

        // Calculate centered position
        float x = canvasWidth / 2f; // Horizontal center
        float y = canvasHeight / 2f - (centeredCharacterPaint.descent() + centeredCharacterPaint.ascent()) / 2f; // Vertical center

        // Draw the character
        canvas.drawText(character, x, y, centeredCharacterPaint);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (this.drawable != null) {
            this.drawable.setBounds(getPaddingLeft(),
                getPaddingTop(),
                getWidth() - getPaddingRight(),
                getHeight() - getPaddingBottom());
            this.drawable.setCallback(this);
            this.drawable.draw(canvas);
            if (this.hasDiverse) {
                float targetFontSize = getPaddingBottom() * 2;
                this.paint.setTextSize(targetFontSize);
                this.paint.setTextAlign(Paint.Align.RIGHT);
                int xPos = canvas.getWidth();
                int yPos = canvas.getHeight();
                this.paint.setColor(this.diverseColor);
                canvas.drawText("◢", xPos, yPos, this.paint);
            }
        } else {
            drawCenteredCharacter(canvas, EmojiUtil.REPLACEMENT_CHARACTER);
        }
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable drawable) {
        super.invalidateDrawable(drawable);
        postInvalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
