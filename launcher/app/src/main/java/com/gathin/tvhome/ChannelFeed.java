package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What an app itself suggests to watch, for the top shelf (config.xml shelf_channel_apps): the preview programs it
 * publishes to Android TV's home-screen channels (SmartTube's Recommendations and Subscriptions, say), read from the
 * TV provider with their poster and title. Android lets a launcher that is not the system's read only the rows
 * marked searchable, and only with READ_TV_LISTINGS (build-and-install grants it); without it nothing is shown.
 * The history channel (watched already) is left out. Rows are local, so a list is read anew every minute at most.
 */
final class ChannelFeed {

    private static final String TAG = "TvHome.ChannelFeed";
    private static final long MAX_AGE_MS = 60 * 1000L;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, List<ShelfFeed.Item>> lists = new HashMap<>();
    private final Map<String, Long> readAt = new HashMap<>();

    ChannelFeed(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    boolean enabled() {
        return ctx.checkSelfPermission("android.permission.READ_TV_LISTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** The programs read last for this app, without a new read; empty when none were. */
    List<ShelfFeed.Item> cached(String pkg) {
        List<ShelfFeed.Item> known = lists.get(pkg);
        return known == null ? java.util.Collections.emptyList() : known;
    }

    /** The app's programs, shuffled; the cached list at once when it is fresh, else read on a worker thread. */
    void items(String pkg, ShelfFeed.ItemsListener listener) {
        if (!enabled()) return;
        Long at = readAt.get(pkg);
        List<ShelfFeed.Item> known = lists.get(pkg);
        if (known != null && at != null && System.currentTimeMillis() - at < MAX_AGE_MS) {
            if (!known.isEmpty()) listener.onItems(known);
            return;
        }
        new Thread(() -> {
            List<ShelfFeed.Item> got = read(pkg);
            main.post(() -> {
                readAt.put(pkg, System.currentTimeMillis());
                lists.put(pkg, got);
                if (!got.isEmpty()) listener.onItems(got);
            });
        }, "channel-feed").start();
    }

    private List<ShelfFeed.Item> read(String pkg) {
        List<ShelfFeed.Item> out = new ArrayList<>();
        try {
            // The app's channels, in the order it made them. SmartTube publishes Subscriptions, Recommendations and
            // History; the one made last is history, which is not something to watch next.
            List<Long> channels = new ArrayList<>();
            Uri channelsUri = TvContract.Channels.CONTENT_URI.buildUpon()
                    .appendQueryParameter("package", pkg).build();
            try (Cursor c = ctx.getContentResolver().query(channelsUri,
                    new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME,
                            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID}, null, null,
                    TvContract.Channels._ID)) {
                while (c != null && c.moveToNext()) {
                    String id = c.getString(2);
                    if (id != null && id.toLowerCase(java.util.Locale.ROOT).contains("history")) continue;
                    channels.add(c.getLong(0));
                }
            }
            if (channels.size() >= 3) channels.remove(channels.size() - 1);
            if (channels.isEmpty()) return out;

            Uri programsUri = TvContract.PreviewPrograms.CONTENT_URI.buildUpon()
                    .appendQueryParameter("package", pkg).build();
            try (Cursor c = ctx.getContentResolver().query(programsUri,
                    new String[]{TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, TvContract.PreviewPrograms.COLUMN_TITLE,
                            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                            TvContract.PreviewPrograms.COLUMN_LIVE},
                    null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (!channels.contains(c.getLong(0))) continue;
                    String title = c.getString(1);
                    String poster = c.getString(2);
                    if (title == null || title.isEmpty() || poster == null || !poster.startsWith("http")) continue;
                    out.add(new ShelfFeed.Item(title, poster, c.getString(3), "", 0, c.getInt(4) != 0));
                }
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "channels unreadable: " + e);
        }
        Collections.shuffle(out);
        if (out.size() > 30) out = new ArrayList<>(out.subList(0, 30));
        return out;
    }
}
