package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The flowing backdrop of the full-screen Now Playing (MainActivity ambient screen): a few large soft blobs in the
 * cover's own colours drifting slowly over a deep base, like a music app's player on a TV. Colours come from the
 * cover (a handful of its brightest saturated pixels); without a cover it is a quiet blue-violet.
 */
public class AmbientView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] colors = {0xFF3A2FB8, 0xFF6A3DE8, 0xFF1F1C6B, 0xFF8E4BFF};
    private int base = 0xFF14113A;
    private long start = System.nanoTime();
    private boolean running;

    public AmbientView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Picks the palette from a cover; null keeps the default. */
    void setCover(Bitmap cover) {
        if (cover == null) return;
        Bitmap small = Bitmap.createScaledBitmap(cover, 24, 24, true);
        int[] px = new int[24 * 24];
        small.getPixels(px, 0, 24, 0, 0, 24, 24);
        small.recycle();
        // Four well-separated hues among the saturated pixels; fall back to the pixel's own colour when dull.
        float[] hsv = new float[3];
        int[] picked = new int[4];
        float[] pickedHue = new float[4];
        int n = 0;
        int[] order = px.clone();
        java.util.Arrays.sort(order);   // deterministic walk; saturation decides below
        for (int pass = 0; pass < 2 && n < 4; pass++) {
            float minSat = pass == 0 ? 0.45f : 0.15f;
            for (int c : px) {
                Color.colorToHSV(c, hsv);
                if (hsv[1] < minSat || hsv[2] < 0.25f) continue;
                boolean far = true;
                for (int i = 0; i < n; i++) {
                    float d = Math.abs(hsv[0] - pickedHue[i]);
                    if (Math.min(d, 360 - d) < 28) { far = false; break; }
                }
                if (!far) continue;
                hsv[1] = Math.min(1f, hsv[1] * 1.15f);
                hsv[2] = Math.max(0.55f, Math.min(0.9f, hsv[2]));
                pickedHue[n] = hsv[0];
                picked[n++] = Color.HSVToColor(hsv);
                if (n == 4) break;
            }
        }
        if (n == 0) return;
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) out[i] = picked[i % n];
        colors = out;
        Color.colorToHSV(colors[0], hsv);
        hsv[1] = Math.min(1f, hsv[1] + 0.1f);
        hsv[2] = 0.16f;
        base = Color.HSVToColor(hsv);
        invalidate();
    }

    void start() {
        running = true;
        start = System.nanoTime();
        invalidate();
    }

    void stop() {
        running = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(base);
        float t = (System.nanoTime() - start) / 1e9f;
        float r = Math.max(w, h) * 0.62f;
        for (int i = 0; i < colors.length; i++) {
            // Each blob wanders on its own slow Lissajous path.
            float ph = i * 1.9f;
            float cx = w * (0.5f + 0.42f * (float) Math.sin(t * 0.19f + ph) * (float) Math.cos(t * 0.08f + ph * 0.7f));
            float cy = h * (0.5f + 0.45f * (float) Math.cos(t * 0.15f + ph * 1.3f));
            int c = colors[i];
            paint.setShader(new RadialGradient(cx, cy, r, new int[]{c & 0x00FFFFFF | 0xB8000000, c & 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
        paint.setShader(null);
        if (running) postInvalidateOnAnimation();
    }
}
