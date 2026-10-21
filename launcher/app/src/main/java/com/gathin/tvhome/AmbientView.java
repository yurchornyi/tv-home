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
    /** The palette being left (a new cover's colours glide in over MIX_MS), or null when settled. */
    private int[] fromColors;
    private int fromBase;
    private long mixStart;
    private static final float MIX_MS = 1600f;

    public AmbientView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /**
     * Picks the palette from the cover, the way a music app's player does: its dominant hues by how much of the
     * picture they cover, lifted a little so they glow; a grey cover gives greys. Null keeps the default.
     */
    void setCover(Bitmap cover) {
        if (cover == null) return;
        Bitmap small = Bitmap.createScaledBitmap(cover, 32, 32, true);
        int[] px = new int[32 * 32];
        small.getPixels(px, 0, 32, 0, 0, 32, 32);
        small.recycle();
        // 12 hue buckets plus one for the greys; each remembers how many pixels, and their mean saturation/value.
        int buckets = 13;
        int[] count = new int[buckets];
        float[] sumH = new float[buckets], sumS = new float[buckets], sumV = new float[buckets];
        float[] hsv = new float[3];
        for (int c : px) {
            Color.colorToHSV(c, hsv);
            int b = hsv[1] < 0.18f ? 12 : (int) (hsv[0] / 30f) % 12;
            count[b]++;
            sumH[b] += hsv[0];
            sumS[b] += hsv[1];
            sumV[b] += hsv[2];
        }
        Integer[] order = new Integer[buckets];
        for (int i = 0; i < buckets; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(count[b], count[a]));
        int[] out = new int[4];
        int n = 0;
        for (int i = 0; i < buckets && n < 4; i++) {
            int b = order[i];
            if (count[b] < px.length / 40) continue;   // under 2.5 % of the picture: not its colour
            float h = sumH[b] / count[b], sat = sumS[b] / count[b], v = sumV[b] / count[b];
            if (b == 12) {
                // Greys: two tones of the cover's own grey, so a black-and-white cover gets a quiet dark backdrop.
                out[n++] = Color.HSVToColor(new float[]{0f, 0f, Math.max(0.3f, Math.min(0.55f, v))});
                if (n < 4) out[n++] = Color.HSVToColor(new float[]{0f, 0f, Math.max(0.18f, Math.min(0.4f, v * 0.6f))});
            } else {
                out[n++] = Color.HSVToColor(new float[]{h, Math.max(0.55f, Math.min(1f, sat * 1.3f)),
                        Math.max(0.5f, Math.min(0.9f, v * 1.15f))});
            }
        }
        if (n == 0) return;
        // Fewer colours than blobs: the rest are the first ones again, shifted in brightness so they still differ.
        for (int i = n; i < 4; i++) {
            Color.colorToHSV(out[i % n], hsv);
            hsv[2] = Math.max(0.25f, hsv[2] * (i % 2 == 0 ? 0.7f : 0.85f));
            out[i] = Color.HSVToColor(hsv);
        }
        // Start the glide from whatever is on screen now, even mid-glide, so a quick skip never jumps.
        float m = mix();
        int[] nowColors = new int[4];
        for (int i = 0; i < 4; i++) nowColors[i] = fromColors == null ? colors[i] : blend(fromColors[i], colors[i], m);
        int nowBase = fromColors == null ? base : blend(fromBase, base, m);
        fromColors = nowColors;
        fromBase = nowBase;
        mixStart = System.nanoTime();
        colors = out;
        Color.colorToHSV(out[0], hsv);
        hsv[1] = Math.min(1f, hsv[1] * 0.9f);
        hsv[2] = 0.1f;
        base = Color.HSVToColor(hsv);
        invalidate();
    }

    /** 0..1 through the glide, eased; 1 when settled. */
    private float mix() {
        if (fromColors == null) return 1f;
        float f = (System.nanoTime() - mixStart) / 1e6f / MIX_MS;
        if (f >= 1f) {
            fromColors = null;
            return 1f;
        }
        return f * f * (3f - 2f * f);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
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
        float m = mix();
        canvas.drawColor(fromColors == null ? base : blend(fromBase, base, m));
        float t = (System.nanoTime() - start) / 1e9f;
        for (int i = 0; i < colors.length; i++) {
            // Each blob wanders on its own Lissajous path and breathes, so the colours visibly flow into each other.
            float ph = i * 1.9f;
            float cx = w * (0.5f + 0.48f * (float) Math.sin(t * 0.37f + ph) * (float) Math.cos(t * 0.17f + ph * 0.7f));
            float cy = h * (0.5f + 0.5f * (float) Math.cos(t * 0.29f + ph * 1.3f));
            float r = Math.max(w, h) * (0.55f + 0.18f * (float) Math.sin(t * 0.43f + ph * 2.1f));
            int alpha = 0x90 + (int) (0x48 * (0.5f + 0.5f * Math.sin(t * 0.23f + ph)));
            int c = fromColors == null ? colors[i] : blend(fromColors[i], colors[i], m);
            paint.setShader(new RadialGradient(cx, cy, r, new int[]{c & 0x00FFFFFF | (alpha << 24), c & 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
        paint.setShader(null);
        if (running || fromColors != null) postInvalidateOnAnimation();
    }
}
