package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/**
 * Covers a player names by URL rather than sending as a bitmap (NowPlaying): fetched once, kept in memory for the
 * last few tracks. A URL that failed is not tried again until the launcher restarts.
 */
final class CoverArt {

    private static final String TAG = "TvHome.CoverArt";
    private static final LruCache<String, Bitmap> cache = new LruCache<>(6);
    private static final Set<String> loading = new HashSet<>();
    private static final Set<String> failed = new HashSet<>();

    private CoverArt() { }

    static synchronized Bitmap cached(String url) {
        return cache.get(url);
    }

    /** Downloads the picture on a worker thread; `done` is called (on that thread) once it is cached. */
    static synchronized void fetch(String url, Runnable done) {
        if (cache.get(url) != null || failed.contains(url) || !loading.add(url)) return;
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    bmp = BitmapFactory.decodeStream(in, null, o);
                }
                if (bmp != null && bmp.getWidth() > 800) {
                    bmp = Bitmap.createScaledBitmap(bmp, 800, 800 * bmp.getHeight() / bmp.getWidth(), true);
                }
            } catch (Exception e) {
                Log.w(TAG, "cover failed: " + e);
            }
            synchronized (CoverArt.class) {
                loading.remove(url);
                if (bmp != null) cache.put(url, bmp);
                else failed.add(url);
            }
            if (bmp != null) done.run();
        }, "cover-art").start();
    }
}
