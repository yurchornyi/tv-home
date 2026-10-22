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
 * A "calm" marker records a banner that needs no darkening. Written in the background; the folder is emptied when
 * it grows past a few hundred files (apps come and go, each update gets a new key).
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

    static boolean isCalm(Context ctx, String key) {
        return new File(dir(ctx), name(key) + ".calm").isFile();
    }

    static void markCalm(Context ctx, String key) {
        File f = new File(dir(ctx), name(key) + ".calm");
        writer.execute(() -> {
            try {
                f.createNewFile();
            } catch (Exception ignored) {
            }
        });
    }

    static void write(Context ctx, String key, Bitmap bmp) {
        File d = dir(ctx);
        File f = new File(d, name(key) + ".png");
        writer.execute(() -> {
            String[] all = d.list();
            if (all != null && all.length > 300) {
                for (String n : all) new File(d, n).delete();
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
