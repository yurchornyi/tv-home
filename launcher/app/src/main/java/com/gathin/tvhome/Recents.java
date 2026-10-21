package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What was opened from the launcher last, newest first, for the top shelf's Continue row (MainActivity): a live
 * channel (LiveChannels), a film or video from the shelf (ShelfFeed/ChannelFeed item), or an app. One entry per
 * thing, the last few kept; opening something again moves it to the front. Kept in preferences on this TV only.
 */
final class Recents {

    static final class Entry {
        static final String APP = "app", CHANNEL = "channel", ITEM = "item";

        final String kind;
        final String pkg;
        /** The channel id, the item's open URI or title; with the package it tells one entry from another. */
        final String key;
        final String title;
        /** A logo or backdrop URL, or null (an app shows its banner). */
        final String image;
        /** What to open: a channel's or item's URI; null for an app (its launch intent) or a feed item without one. */
        final String open;
        final long time;

        Entry(String kind, String pkg, String key, String title, String image, String open, long time) {
            this.kind = kind;
            this.pkg = pkg;
            this.key = key;
            this.title = title;
            this.image = image;
            this.open = open;
            this.time = time;
        }
    }

    private static final int MAX = 10;

    private final SharedPreferences prefs;
    private List<Entry> entries;

    Recents(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences("recents", Context.MODE_PRIVATE);
    }

    /** Newest first; entries whose app is gone are left out by the caller. */
    List<Entry> all() {
        if (entries == null) {
            entries = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(prefs.getString("list", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    entries.add(new Entry(o.getString("kind"), o.getString("pkg"), o.getString("key"),
                            o.getString("title"), o.isNull("image") ? null : o.optString("image", null),
                            o.isNull("open") ? null : o.optString("open", null), o.optLong("time", 0)));
                }
            } catch (Exception ignored) {
                entries.clear();
            }
        }
        return new ArrayList<>(entries);
    }

    void app(String pkg, String title) {
        add(new Entry(Entry.APP, pkg, pkg, title, null, null, System.currentTimeMillis()));
    }

    void channel(String pkg, LiveChannels.Channel c) {
        add(new Entry(Entry.CHANNEL, pkg, c.id, c.name, c.logo, c.open, System.currentTimeMillis()));
    }

    void item(String pkg, ShelfFeed.Item item) {
        String key = item.open != null ? item.open : item.type + ":" + item.id + ":" + item.title;
        add(new Entry(Entry.ITEM, pkg, key, item.title, item.backdrop, item.open, System.currentTimeMillis()));
    }

    /** Drops every entry of an uninstalled app. */
    void forget(String pkg) {
        List<Entry> list = all();
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).pkg.equals(pkg)) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) save(list);
    }

    private void add(Entry e) {
        List<Entry> list = all();
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry o = list.get(i);
            if (o.pkg.equals(e.pkg) && o.key.equals(e.key)) list.remove(i);
            // An app's own entry gives way to what was opened inside it: the channel says more than the app.
            else if (o.kind.equals(Entry.APP) && o.pkg.equals(e.pkg) && !e.kind.equals(Entry.APP)) list.remove(i);
        }
        list.add(0, e);
        while (list.size() > MAX) list.remove(list.size() - 1);
        save(list);
    }

    private void save(List<Entry> list) {
        entries = list;
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("kind", e.kind).put("pkg", e.pkg).put("key", e.key).put("title", e.title)
                        .put("image", e.image == null ? JSONObject.NULL : e.image)
                        .put("open", e.open == null ? JSONObject.NULL : e.open).put("time", e.time);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString("list", a.toString()).apply();
    }
}
