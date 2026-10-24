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
        /** The picker's category (NEWS to ADULT_ONLY), and the place among the popular ones (1 first), 0 if not one. */
        final int category, popular;

        Channel(String id, String name, String logo, String open) {
            this(id, name, logo, open, OTHER, 0);
        }

        Channel(String id, String name, String logo, String open, int category, int popular) {
            this.id = id;
            this.name = name;
            this.logo = logo;
            this.open = open;
            this.category = category;
            this.popular = popular;
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
                out.add(new Channel(o.getString("id"), o.getString("name"), logo, open,
                        category(o.optString("category", "")), o.optInt("popular", 0)));
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
    static final int ROW_MAX = 20;

    /**
     * What the shelf shows. Automatic: it both arranges and adds. The chosen channels are always there, the most
     * opened first (equal counts keep the order chosen); after them, in the places left up to twenty, every other
     * channel opened from Home (the shelf, Search, a voice command), the most opened first; the ones taken off the
     * shelf stay off. Only openings from Home count: what is switched inside the app itself is not seen from here.
     * Not automatic: the chosen ones in the order they were chosen, nothing else. Empty until a choice or an opening.
     */
    List<Channel> row(String pkg) {
        List<Channel> chosen = chosen(pkg);
        if (!automatic(pkg)) return chosen.size() > ROW_MAX ? chosen.subList(0, ROW_MAX) : chosen;
        Map<String, Integer> use = uses(pkg);
        java.util.Comparator<Channel> byUse = (a, b) -> Integer.compare(use.getOrDefault(b.id, 0), use.getOrDefault(a.id, 0));
        List<Channel> out = new ArrayList<>(chosen);
        Collections.sort(out, byUse);   // stable: equal counts keep the chosen order
        if (out.size() > ROW_MAX) out = new ArrayList<>(out.subList(0, ROW_MAX));
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

    /**
     * The picker's categories, in the order its tabs show them (R.array.channel_categories names them). A channel's
     * category comes from the list (tools/kyivstar-channels.py takes it from Kyivstar's own genre groups); POPULAR is
     * not one of them but the tab of the channels the list ranks as most watched.
     */
    static final int POPULAR = 0, NEWS = 1, SPORT = 2, MOVIES = 3, ENTERTAINMENT = 4, KIDS = 5, KNOWLEDGE = 6, MUSIC = 7,
            RADIO = 8, REGIONAL = 9, OTHER = 10, ADULT_ONLY = 11;

    private static final List<String> CATEGORY_KEYS = java.util.Arrays.asList("popular", "news", "sport", "movies",
            "entertainment", "kids", "knowledge", "music", "radio", "regional", "other", "adult");

    private static int category(String key) {
        int i = CATEGORY_KEYS.indexOf(key);
        return i > POPULAR ? i : OTHER;
    }

    /** The channels of one category: the popular ones most watched first, in every other the list's order. */
    static List<Channel> inCategory(List<Channel> channels, int category) {
        List<Channel> out = new ArrayList<>();
        for (Channel c : channels) {
            if (category == POPULAR ? c.popular > 0 : c.category == category) out.add(c);
        }
        if (category == POPULAR) Collections.sort(out, (a, b) -> Integer.compare(a.popular, b.popular));
        return out;
    }

    private static final Map<android.graphics.Bitmap, android.graphics.Bitmap> TRIMMED = new java.util.WeakHashMap<>();

    /**
     * A logo without the clear margins round it: many come with more empty room on one side, and fitted as they are
     * they sit off the tile's centre. A logo with nothing to trim (or no alpha) comes back as it is.
     */
    static android.graphics.Bitmap trim(android.graphics.Bitmap b) {
        if (b == null || !b.hasAlpha()) return b;
        android.graphics.Bitmap known = TRIMMED.get(b);
        if (known != null) return known;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) < 16) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        android.graphics.Bitmap out = right < 0 || (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) ? b
                : android.graphics.Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
        TRIMMED.put(b, out);
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
