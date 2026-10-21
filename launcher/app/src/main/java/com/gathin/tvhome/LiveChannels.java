package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live TV channels an app can open by deep link, for the top shelf's channel row (MainActivity): the launcher
 * ships a list per app in assets/channels/<package>.json (tools/kyivstar-channels.py builds Kyivstar TV's from
 * the service's public sitemap, names and logos from iptv-org). The owner picks which to show (ChannelPicker)
 * and whether an app's shelf shows posters or channels (the tile menu); both are kept in preferences.
 */
final class LiveChannels {

    static final class Channel {
        final String id, name, logo, open;

        Channel(String id, String name, String logo, String open) {
            this.id = id;
            this.name = name;
            this.logo = logo;
            this.open = open;
        }
    }

    private static final String PREFS = "channels";

    private final Context ctx;
    private final Map<String, List<Channel>> lists = new HashMap<>();

    LiveChannels(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** True when the launcher ships a channel list for this app. */
    boolean available(String pkg) {
        if (lists.containsKey(pkg)) return !lists.get(pkg).isEmpty();
        return !all(pkg).isEmpty();
    }

    /** Every channel the app can open, in the list's order; empty for an app without one. */
    List<Channel> all(String pkg) {
        List<Channel> known = lists.get(pkg);
        if (known != null) return known;
        List<Channel> out = new ArrayList<>();
        try (InputStream in = ctx.getAssets().open("channels/" + pkg + ".json")) {
            JSONArray a = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String logo = o.isNull("logo") ? null : o.optString("logo", null);
                out.add(new Channel(o.getString("id"), o.getString("name"), logo, o.getString("open")));
            }
        } catch (Exception ignored) {
            // no list for this app
        }
        out = Collections.unmodifiableList(out);
        lists.put(pkg, out);
        return out;
    }

    /** The app's shelf shows its channels rather than posters (the tile menu's choice). */
    boolean channelMode(String pkg) {
        return prefs().getBoolean("mode_" + pkg, false);
    }

    void setChannelMode(String pkg, boolean on) {
        prefs().edit().putBoolean("mode_" + pkg, on).apply();
    }

    /** The ids the owner chose for the shelf, in the order chosen. */
    Set<String> chosenIds(String pkg) {
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("chosen_" + pkg, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    void setChosen(String pkg, Set<String> ids) {
        prefs().edit().putString("chosen_" + pkg, new JSONArray(ids).toString()).apply();
    }

    /** The chosen channels, in the order chosen, those no longer in the list left out. */
    List<Channel> chosen(String pkg) {
        Map<String, Channel> byId = new HashMap<>();
        for (Channel c : all(pkg)) byId.put(c.id, c);
        List<Channel> out = new ArrayList<>();
        for (String id : chosenIds(pkg)) {
            Channel c = byId.get(id);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** A channel was opened from the shelf: counts towards the row's order (row). */
    void recordOpen(String pkg, String id) {
        Map<String, Integer> use = uses(pkg);
        Integer n = use.get(id);
        use.put(id, n == null ? 1 : n + 1);
        prefs().edit().putString("use_" + pkg, new JSONObject(use).toString()).apply();
    }

    /**
     * What the shelf shows: the chosen channels, the most opened first, then up to three others opened often
     * enough to earn a place; with nothing chosen, the eight most opened. Empty until a choice or an opening.
     */
    List<Channel> row(String pkg) {
        Map<String, Integer> use = uses(pkg);
        List<Channel> chosen = chosen(pkg);
        List<Channel> out = new ArrayList<>(chosen);
        java.util.Comparator<Channel> byUse = (a, b) -> Integer.compare(use.getOrDefault(b.id, 0), use.getOrDefault(a.id, 0));
        Collections.sort(out, byUse);   // stable: equal counts keep the chosen order
        List<Channel> often = new ArrayList<>();
        for (Channel c : all(pkg)) {
            if (use.getOrDefault(c.id, 0) >= 2 && !out.contains(c)) often.add(c);
        }
        Collections.sort(often, byUse);
        int extra = chosen.isEmpty() ? 8 : 3;
        for (Channel c : often) {
            if (extra-- <= 0) break;
            out.add(c);
        }
        return out;
    }

    private Map<String, Integer> uses(String pkg) {
        Map<String, Integer> out = new HashMap<>();
        try {
            JSONObject o = new JSONObject(prefs().getString("use_" + pkg, "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, o.getInt(k));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
