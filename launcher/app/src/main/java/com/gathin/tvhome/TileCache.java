package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Darkened tile banners (DarkBanner) kept on disk between starts, so Home builds its tiles from finished pictures
 * instead of decoding and repainting every app's artwork, which right after the TV is switched on took seconds.
 * A banner that needs no darkening is kept as a picture all the same, so no app's resources are opened for a tile that
 * was built before. The tile labels are kept alongside (labels.json). Written in the background; the banners are
 * emptied when they grow past a few hundred files (apps come and go, each update gets a new key).
 */
final class TileCache {

    private static final ExecutorService writer = Executors.newSingleThreadExecutor();

    private TileCache() { }

    private static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "tiles");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static String name(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    static Bitmap read(Context ctx, String key) {
        File f = new File(dir(ctx), name(key) + ".png");
        if (!f.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(f.getPath());
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The tile labels by the adapter's memo key, as last written; empty when there is no file or it is unreadable. */
    static java.util.Map<String, String> readLabels(Context ctx) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        File f = new File(dir(ctx), "labels.json");
        if (!f.isFile() || f.length() > 256 * 1024) return out;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            org.json.JSONObject o = new org.json.JSONObject(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, o.getString(k));
            }
        } catch (Exception e) {
            out.clear();
        }
        return out;
    }

    /** Keeps these labels for the next start (readLabels); written in the background, whole or not at all. */
    static void writeLabels(Context ctx, java.util.Map<String, String> labels) {
        File f = new File(dir(ctx), "labels.json");
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            for (java.util.Map.Entry<String, String> e : labels.entrySet()) {
                if (e.getValue() != null) o.put(e.getKey(), e.getValue());
            }
        } catch (org.json.JSONException impossible) {
            return;
        }
        byte[] bytes = o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writer.execute(() -> {
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            } catch (Exception e) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(f)) tmp.delete();
        });
    }

    static void write(Context ctx, String key, Bitmap bmp) {
        File d = dir(ctx);
        File f = new File(d, name(key) + ".png");
        writer.execute(() -> {
            String[] all = d.list();
            if (all != null && all.length > 300) {
                for (String n : all) if (n.endsWith(".png")) new File(d, n).delete();
            }
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(f)) tmp.delete();
        });
    }
}
