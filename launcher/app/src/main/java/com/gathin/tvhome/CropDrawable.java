package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * A picture that fills whatever bounds it gets, centre-cropped, keeping its proportions. It has no size of its own,
 * so it sits in a FIT_XY ImageView beside the app banners: a cross-fade from a banner to a poster then draws the
 * poster at its true proportions from the first frame, instead of squeezed to the banner's and snapping after.
 */
final class CropDrawable extends Drawable {

    private final Drawable picture;

    CropDrawable(Drawable picture) {
        this.picture = picture;
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = picture.getIntrinsicWidth(), h = picture.getIntrinsicHeight();
        if (b.isEmpty() || w <= 0 || h <= 0) return;
        float scale = Math.max(b.width() / (float) w, b.height() / (float) h);
        int sw = Math.round(w * scale), sh = Math.round(h * scale);
        int left = b.left + (b.width() - sw) / 2, top = b.top + (b.height() - sh) / 2;
        canvas.save();
        canvas.clipRect(b);
        picture.setBounds(left, top, left + sw, top + sh);
        picture.draw(canvas);
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) {
        picture.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public int getAlpha() {
        return picture.getAlpha();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        picture.setColorFilter(filter);
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
