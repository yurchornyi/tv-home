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
        // The list is kept under the app's first package name (Aliases); its deep links open the one installed here.
        String listed = Aliases.canonical(pkg);
        try (InputStream in = ctx.getAssets().open("channels/" + listed + ".json")) {
            JSONArray a = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String logo = o.isNull("logo") ? null : o.optString("logo", null);
                String open = o.getString("open");
                if (!listed.equals(pkg) && open.startsWith(listed + ":")) open = pkg + open.substring(listed.length());
                out.add(new Channel(o.getString("id"), o.getString("name"), logo, open));
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
        // A channel chosen by hand is no longer one the owner took off the shelf.
        Set<String> hidden = hiddenIds(pkg);
        hidden.removeAll(ids);
        prefs().edit().putString("chosen_" + pkg, new JSONArray(ids).toString())
                .putString("hidden_" + pkg, new JSONArray(hidden).toString()).apply();
    }

    /** The picker's answer: what is on the shelf, and what is never shown (the picker's bottom part). */
    void setChoice(String pkg, Set<String> chosen, Set<String> hidden) {
        Set<String> on = new LinkedHashSet<>(chosen);
        on.removeAll(hidden);
        prefs().edit().putString("chosen_" + pkg, new JSONArray(on).toString())
                .putString("hidden_" + pkg, new JSONArray(hidden).toString()).apply();
    }

    /** Channels the owner took off the shelf: the automatic order does not bring them back. */
    Set<String> hiddenIds(String pkg) {
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("hidden_" + pkg, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Hold OK on a channel tile, "Remove from the shelf": out of the chosen ones, and kept out of the automatic. */
    void remove(String pkg, String id) {
        Set<String> chosen = chosenIds(pkg);
        chosen.remove(id);
        Set<String> hidden = hiddenIds(pkg);
        hidden.add(id);
        prefs().edit().putString("chosen_" + pkg, new JSONArray(chosen).toString())
                .putString("hidden_" + pkg, new JSONArray(hidden).toString()).apply();
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

    /** The row sorts itself by use and adds channels opened often (the tile menu's "automatic" switch). */
    boolean automatic(String pkg) {
        return prefs().getBoolean("auto_" + pkg, true);
    }

    void setAutomatic(String pkg, boolean on) {
        prefs().edit().putBoolean("auto_" + pkg, on).apply();
    }

    /** The most channels the shelf's row holds. */
    private static final int ROW_MAX = 12;

    /**
     * What the shelf shows. Automatic: it both arranges and adds. The chosen channels are always there, the most
     * opened first (equal counts keep the order chosen); after them, in the places left up to twelve, every other
     * channel opened from Home (the shelf, Search, a voice command), the most opened first; the ones taken off the
     * shelf stay off. Only openings from Home count: what is switched inside the app itself is not seen from here.
     * Not automatic: the chosen ones in the order they were chosen, nothing else. Empty until a choice or an opening.
     */
    List<Channel> row(String pkg) {
        List<Channel> chosen = chosen(pkg);
        if (!automatic(pkg)) return chosen;
        Map<String, Integer> use = uses(pkg);
        java.util.Comparator<Channel> byUse = (a, b) -> Integer.compare(use.getOrDefault(b.id, 0), use.getOrDefault(a.id, 0));
        List<Channel> out = new ArrayList<>(chosen);
        Collections.sort(out, byUse);   // stable: equal counts keep the chosen order
        Set<String> hidden = hiddenIds(pkg);
        List<Channel> opened = new ArrayList<>();
        for (Channel c : all(pkg)) {
            if (use.getOrDefault(c.id, 0) >= 1 && !hidden.contains(c.id) && !out.contains(c)) opened.add(c);
        }
        Collections.sort(opened, byUse);   // stable: equal counts keep the list's order
        for (Channel c : opened) {
            if (out.size() >= ROW_MAX) break;
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
