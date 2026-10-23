package com.gathin.tvhome;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * A dark version of a light app banner (config.xml dark_tiles): on a white or grey card the neutral pixels are flipped
 * to dark and coloured ones kept, so Netflix becomes black with the same red logo. A glaring coloured background (light
 * and saturated, like a neon green field) becomes a deep shade of itself with the logo kept; strong but darker colours
 * (Twitch purple) stay. tile_deep_colors names background colours by hand where the automatic pick misses (stripes). Done once per banner when the tiles load, on a copy (at most 640 px wide), never on the app's drawable.
 */
final class DarkBanner {

    /** Bumped when the darkening changes, so banners saved by an older build are made again (TileCache). */
    static final int VERSION = 1;

    /** The background a pure white pixel becomes: the same near-black as the tiles. */
    private static final int DARK = 28;

    private DarkBanner() { }

    static Drawable of(Resources res, Drawable banner, int[] deepColors) {
        int w = banner.getIntrinsicWidth(), h = banner.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return banner;
        float scale = Math.min(1f, 640f / w);
        int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
        Drawable copy = banner.getConstantState() != null ? banner.getConstantState().newDrawable(res).mutate() : banner;
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, bw, bh);
        copy.draw(new Canvas(bmp));
        int[] px = new int[bw * bh];
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh);
        if (deepColors.length > 0) {
            deepenAll(px, bw, bh, deepColors);
        } else if (lightBorder(px, bw, bh)) {
            for (int i = 0; i < px.length; i++) px[i] = flip(px[i]);
        } else {
            int[] keys = glaringBackground(px, bw, bh);
            if (keys.length == 0) {
                bmp.recycle();
                return banner;
            }
            deepenAll(px, bw, bh, keys);
        }
        bmp.setPixels(px, 0, bw, 0, 0, bw, bh);
        return new BitmapDrawable(res, bmp);
    }

    /**
     * The banner as a picture of its own, at most 640 px wide, drawn the way of() draws one before darkening: what a
     * calm banner is kept as in the tile cache, so the app's resources need not be opened for it next time. The
     * banner itself when it has no size to draw at.
     */
    static Drawable raster(Resources res, Drawable banner) {
        int w = banner.getIntrinsicWidth(), h = banner.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return banner;
        float scale = Math.min(1f, 640f / w);
        int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
        Drawable copy = banner.getConstantState() != null ? banner.getConstantState().newDrawable(res).mutate() : banner;
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, bw, bh);
        copy.draw(new Canvas(bmp));
        return new BitmapDrawable(res, bmp);
    }

    /** The banner's edge is mostly light (its background is white or pale grey). */
    static boolean lightBorder(int[] px, int w, int h) {
        int edge = Math.max(1, Math.min(w, h) / 16);
        long sum = 0;
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= edge && x < w - edge && y >= edge && y < h - edge) continue;
                int c = px[y * w + x];
                if ((c >>> 24) < 128) continue;   // transparent corners of rounded banners
                sum += ((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114;
                n++;
            }
        }
        return n > 0 && sum / n > 185_000;   // average luma above ~185 of 255
    }

    /**
     * The glaring colours of the banner's edge, its background: up to two clusters, each a fifth of the edge or more,
     * saturated and light (luma above 150 of 255). Twitch's purple or a red field are darker and come back empty.
     */
    static int[] glaringBackground(int[] px, int w, int h) {
        int edge = Math.max(1, Math.min(w, h) / 16);
        java.util.List<Integer> border = new java.util.ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= edge && x < w - edge && y >= edge && y < h - edge) continue;
                int c = px[y * w + x];
                if ((c >>> 24) >= 128) border.add(c & 0xFFFFFF);
            }
        }
        int n = border.size();
        java.util.List<Integer> keys = new java.util.ArrayList<>();
        for (int round = 0; round < 2 && !border.isEmpty(); round++) {
            java.util.HashMap<Integer, Integer> counts = new java.util.HashMap<>();
            int best = 0, bestCount = 0;
            for (int c : border) {
                int key = (((c >> 16) & 0xFF) >> 4 << 8) | (((c >> 8) & 0xFF) >> 4 << 4) | ((c & 0xFF) >> 4);
                int k = counts.merge(key, 1, Integer::sum);
                if (k > bestCount) { bestCount = k; best = c; }
            }
            int br = (best >> 16) & 0xFF, bg = (best >> 8) & 0xFF, bb = best & 0xFF;
            long sr = 0, sg = 0, sb = 0;
            int in = 0;
            java.util.List<Integer> rest = new java.util.ArrayList<>();
            for (int c : border) {
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (Math.abs(r - br) + Math.abs(g - bg) + Math.abs(b - bb) < 80) {
                    sr += r; sg += g; sb += b; in++;
                } else {
                    rest.add(c);
                }
            }
            border = rest;
            if (in * 5 < n) break;
            int r = (int) (sr / in), g = (int) (sg / in), b = (int) (sb / in);
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            float sat = max == 0 ? 0f : (max - min) / (float) max;
            int luma = (r * 299 + g * 587 + b * 114) / 1000;
            if (sat > 0.35f && luma > 150) keys.add((r << 16) | (g << 8) | b);
        }
        int[] out = new int[keys.size()];
        for (int i = 0; i < out.length; i++) out[i] = keys.get(i);
        return out;
    }

    /**
     * Deepens the background colours. A two-colour banner striped top and bottom often writes each half's text in the
     * other half's colour (TV Bro: yellow "TV" on blue, blue "BRO" on yellow), so each colour is deepened only in the
     * half where it is the background, and the text stays bright.
     */
    static void deepenAll(int[] px, int w, int h, int[] keys) {
        int[] top = keys, bottom = keys;
        int split = h / 2;
        if (keys.length >= 2) {
            int a = keys[0], b = keys[1];
            int topA = nearCount(px, w, 0, Math.max(1, h / 8), a, b);
            int bottomA = nearCount(px, w, h - Math.max(1, h / 8), h, a, b);
            int band = Math.max(1, h / 8) * w;
            // a owns one edge and b the other: split the banner between them.
            if (topA > band * 0.6f && bottomA < band * 0.4f) {
                top = new int[]{a};
                bottom = new int[]{b};
            } else if (bottomA > band * 0.6f && topA < band * 0.4f) {
                top = new int[]{b};
                bottom = new int[]{a};
            }
            if (top.length == 1) split = boundary(px, w, h, top[0], bottom[0]);
        }
        for (int y = 0; y < h; y++) {
            int[] use = y < split ? top : bottom;
            for (int x = 0; x < w; x++) px[y * w + x] = deepen(px[y * w + x], use);
        }
    }

    /** The first row, from the top, whose left and right edges belong to the bottom colour: where the stripes meet. */
    private static int boundary(int[] px, int w, int h, int topKey, int bottomKey) {
        int edge = Math.max(1, w / 20);
        for (int y = 0; y < h; y++) {
            int toBottom = 0, total = 0;
            for (int x = 0; x < w; x++) {
                if (x >= edge && x < w - edge) continue;
                int c = px[y * w + x];
                if (dist(c, bottomKey) < dist(c, topKey)) toBottom++;
                total++;
            }
            if (toBottom * 2 > total) return y;
        }
        return h / 2;
    }

    /** Pixels in rows [y0, y1) closer to a than to b. */
    private static int nearCount(int[] px, int w, int y0, int y1, int a, int b) {
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                if (dist(c, a) < dist(c, b)) n++;
            }
        }
        return n;
    }

    private static int dist(int c, int k) {
        return Math.abs(((c >> 16) & 0xFF) - ((k >> 16) & 0xFF)) + Math.abs(((c >> 8) & 0xFF) - ((k >> 8) & 0xFF))
                + Math.abs((c & 0xFF) - (k & 0xFF));
    }

    /** Pixels close to a background colour become a deep shade of it (about a quarter of its brightness). */
    static int deepen(int c, int[] keys) {
        int a = c >>> 24, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        for (int k : keys) {
            int kr = (k >> 16) & 0xFF, kg = (k >> 8) & 0xFF, kb = k & 0xFF;
            int dist = Math.abs(r - kr) + Math.abs(g - kg) + Math.abs(b - kb);
            float wgt = Math.max(0f, Math.min(1f, (150f - dist) / 50f));
            if (wgt <= 0f) continue;
            // Keep the pixel's own variation (gradients, shading), only scaled down.
            int dr = Math.round(r * 0.26f), dg = Math.round(g * 0.26f), db = Math.round(b * 0.26f);
            r = Math.round(r + (dr - r) * wgt);
            g = Math.round(g + (dg - g) * wgt);
            b = Math.round(b + (db - b) * wgt);
            break;
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Neutral pixels are mirrored in lightness onto the dark range; saturated ones keep their colour. */
    static int flip(int c) {
        int a = c >>> 24, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float sat = max == 0 ? 0f : (max - min) / (float) max;
        float neutral = Math.max(0f, Math.min(1f, (0.35f - sat) / 0.2f));
        if (neutral <= 0f) return c;
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        int inv = DARK + (255 - lum) * (255 - DARK - 8) / 255;
        r = Math.round(r + (inv - r) * neutral);
        g = Math.round(g + (inv - g) * neutral);
        b = Math.round(b + (inv - b) * neutral);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
