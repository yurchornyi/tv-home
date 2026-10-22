package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kick's live streams for the top shelf, through the launcher's Worker (tools/tmdb-proxy/kick.js): the streams
 * Kick features now, each a poster with the streamer and the stream's title, marked live. Kick's TV app opens no
 * particular channel, so an item opens as "kick:slug" and MainActivity asks for a fresh playback link (play) to
 * hand to a video player. The list is read again after two minutes at most.
 */
final class KickFeed {

    static final String PACKAGE = "com.kick.mobile";
    interface Link { void onLink(String url); }

    private static final String TAG = "TvHome.Kick";
    private static final long MAX_AGE_MS = 2 * 60 * 1000L;

    private final String base;
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<ShelfFeed.Item> known = Collections.emptyList();
    private long readAt;
    private boolean loading;
    /** Everyone who asked while the list loaded; all hear it. */
    private final List<ShelfFeed.ItemsListener> waiters = new ArrayList<>();

    KickFeed(String proxy) {
        String p = proxy == null ? "" : proxy.trim();
        base = p.isEmpty() ? "" : (p.endsWith("/") ? p.substring(0, p.length() - 1) : p);
    }

    boolean enabled() {
        return !base.isEmpty();
    }

    List<ShelfFeed.Item> cached() {
        return known;
    }

    void items(ShelfFeed.ItemsListener listener) {
        if (!enabled()) return;
        if (!known.isEmpty()) listener.onItems(known);
        if (System.currentTimeMillis() - readAt < MAX_AGE_MS) return;
        if (!waiters.contains(listener)) waiters.add(listener);
        if (loading) return;
        loading = true;
        new Thread(() -> {
            List<ShelfFeed.Item> got = new ArrayList<>();
            try {
                JSONArray a = new JSONObject(get(base + "/kick/featured?lang=en")).getJSONArray("items");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject s = a.getJSONObject(i);
                    String title = s.optString("streamer");
                    String session = s.optString("title").trim();
                    if (!session.isEmpty()) title = title.isEmpty() ? session : title + ": " + session;
                    got.add(new ShelfFeed.Item(title, s.getString("thumbnail"), "kick:" + s.getString("slug"), "", 0, true));
                }
            } catch (Exception e) {
                Log.w(TAG, "streams unreadable: " + e);
            }
            main.post(() -> {
                loading = false;
                readAt = System.currentTimeMillis();
                List<ShelfFeed.ItemsListener> all = new ArrayList<>(waiters);
                waiters.clear();
                if (got.isEmpty()) return;
                boolean first = known.isEmpty();
                known = got;
                if (first) for (ShelfFeed.ItemsListener l : all) l.onItems(got);
            });
        }, "kick-feed").start();
    }

    /** A fresh playback link for a channel that is live; null when it went offline or Kick did not answer. */
    void play(String slug, Link link) {
        new Thread(() -> {
            String url = null;
            try {
                url = new JSONObject(get(base + "/kick/play?slug=" + android.net.Uri.encode(slug))).optString("url", null);
            } catch (Exception e) {
                Log.w(TAG, "no link for " + slug + ": " + e);
            }
            String result = url;
            main.post(() -> link.onLink(result));
        }, "kick-play").start();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
