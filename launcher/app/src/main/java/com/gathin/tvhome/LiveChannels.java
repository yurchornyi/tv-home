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

    /**
     * The channels Ukraine watches most, most watched first (TV ratings of the big channel groups, then the news
     * channels people turn on). The joint news marathon is news, not one of these.
     */
    private static final List<String> FIRST = java.util.Arrays.asList(
            "1+1 hd", "ictv hd", "стб hd", "новий канал", "ictv 2 hd", "тет hd", "2+2 hd", "інтер hd", "нтн",
            "к1 hd", "мега hd", "перший", "еспресо", "прямий", "5 канал", "24 канал");
    private static final Set<String> ADULT = new java.util.HashSet<>(java.util.Arrays.asList(
            "barely legal tv", "blue hustler", "erox", "playboy", "tiny teen", "secret 18 · київстар",
            "erotica · київстар", "lesbian love · київстар"));

    /**
     * The picker's categories, in the order shown (R.array.channel_categories names them). The list has no genres,
     * so a channel's category comes from words in its name; one that says nothing goes to Other.
     */
    static final int POPULAR = 0, NEWS = 1, SPORT = 2, MOVIES = 3, KIDS = 4, KNOWLEDGE = 5, MUSIC = 6, RADIO = 7,
            REGIONAL = 8, OTHER = 9, ADULT_ONLY = 10;

    private static final java.util.regex.Pattern RADIO_WORDS = words("fm|фм|radio|радіо|радио|авторадіо|djfm|radiotochka|nrj");
    private static final java.util.regex.Pattern REGIONAL_WORDS = words("суспільне (?!kultura|sport)\\S+|київ|галичина"
            + "|дніпро tv|перший західний|правдатут|konkurent volyn|конкурент tv|misto nad buhom|pershyi miskyi"
            + "|рудана|нта|аверс|атр|holos donbasu");
    private static final java.util.regex.Pattern KIDS_WORDS = words("kids?|дит\\S*|junior|multo\\S*|мульт\\S*"
            + "|cartoon|disney|nick\\S*|niki|duck tv|peppa|paw patrol|sponge bob|lego|ninja turtles|oggy|bubble guppies"
            + "|penguins|kinokazka|kiddisvit|lingotoons|піксель|плюсплюс|кус-кус|ляле|малятко");
    private static final java.util.regex.Pattern SPORT_WORDS = words("sports?|спорт\\S*|setanta|football|futsal"
            + "|basketball|hockey|volleyball|boxing|motorsport|eurosport|maincast|upl\\S*|mediafootball|динамо"
            + "|sporting|socca|divisport|kibersport|equalympic|warrior");
    private static final java.util.regex.Pattern NEWS_WORDS = words("news|новин\\S*|euronews|bloomberg|dw|france 24"
            + "|obozrevatel|апостроф tv|nastoyashcheye vremya|zakon tv|itv media group|united news|еспресо|прямий"
            + "|рада|24 канал|freeдom|ми — україна|1\\+1 marafon|tv5monde|київ 24|front|армія тб");
    private static final java.util.regex.Pattern MUSIC_WORDS = words("music|музик\\S*|музвар|hits?|rock|trace"
            + "|m1|m2|k pop 24|jazz|chillout|electro|hiphop|наше music|наше ретро|4ever music|eu music|world charts"
            + "|retro|relax|chill|lounge|classical harmony|bikini|musicbox|viva latino");
    private static final java.util.regex.Pattern KNOWLEDGE_WORDS = words("суспільне kultura|discovery|documentary|national geographic|animal"
            + "\\S*|history\\S*|viasat (explore|history|nature|true crime)|travel|food|nature|wildlife|explore|наука"
            + "|фауна|zoosvit|top gear|36\\.6|tlc|u travel|one planet|da vinci|trophy|трофей|рибалка|дача|6 соток"
            + "|eco tv|lost world|world inside out|lives of famous people|english club|lang lab|be helsi|телевсесвіт"
            + "|cars stars|перший автомобільний|бігуді|fashion|woman|reality\\S*|wife swap|надія|світло");
    private static final java.util.regex.Pattern MOVIE_WORDS = words("kino\\S*|кіно\\S*|film\\S*|фільм\\S*|cine"
            + "|movies?|serials?|серіал\\S*|series|drama\\S*|dorama|дорама|comedy\\S*|hollywood|star cinema|cinema"
            + "|fx\\S*|amc|megahit|horror|thriller|crime|detective|melodram\\S*|action|prokino|bolt|milady|наталі"
            + "|enter-фільм|мега|epic|mystic\\S*|fantasy|романс|romance|love|turkish|medical|doctors|hot films|humor"
            + "|perets|black|now series|sol tv|super hd|flix snip|kinosweet|kinogrande|kinowood|kinoliving|vitatv"
            + "|trailer|good cinema|free movies|thrillers?|dexter|charmed|csi|father brown|death in paradise|marple"
            + "|murdoch|elementary|kara sevda|leyla|forbidden|golden boy|aldatmak|wild heart|sweet revenge"
            + "|her yerde sen|jennets tears|feriha|blind wedding|blood flowers|brilliant|bride from istanbul"
            + "|a part of me|yellowstone|cop on call|the family|lady and the peasant|recipe of love|red buds"
            + "|shopping goddess|stosuetsa|veshdok|heads tails|real story|whole country|kremlin|costume drama"
            + "|our series|made in ukraine|audio stories|team|my z ukrainy|українська|terra|терра|тет|2\\+2|нтн|к1|к2|ictv 2"
            + "|сонцe|світ|дім|ets|zoom|d1|ляле|cappuccino|inrating|lux tv|pv|raz \\d|lux|dramatic");

    private static java.util.regex.Pattern words(String any) {
        return java.util.regex.Pattern.compile("(^|[^\\p{L}\\d])(" + any + ")([^\\p{L}\\d]|$)");
    }

    /** The category a channel is listed under in the picker (POPULAR to ADULT_ONLY). */
    static int category(Channel c) {
        String k = key(c);
        if (ADULT.contains(k)) return ADULT_ONLY;
        if (FIRST.contains(k)) return POPULAR;
        if (RADIO_WORDS.matcher(k).find()) return RADIO;
        if (REGIONAL_WORDS.matcher(k).find()) return REGIONAL;
        if (KIDS_WORDS.matcher(k).find()) return KIDS;
        if (SPORT_WORDS.matcher(k).find()) return SPORT;
        if (NEWS_WORDS.matcher(k).find()) return NEWS;
        if (MOVIE_WORDS.matcher(k).find()) return MOVIES;
        if (MUSIC_WORDS.matcher(k).find()) return MUSIC;
        if (KNOWLEDGE_WORDS.matcher(k).find()) return KNOWLEDGE;
        return OTHER;
    }

    /**
     * The picker's "All channels": by category; the popular ones most watched first, inside every other
     * category Ukrainian names first, then A to Z.
     */
    static List<Channel> byCategory(List<Channel> channels) {
        java.text.Collator abc = java.text.Collator.getInstance(new java.util.Locale("uk", "UA"));
        abc.setStrength(java.text.Collator.SECONDARY);
        List<Channel> out = new ArrayList<>(channels);
        Collections.sort(out, (a, b) -> {
            int ca = category(a), cb = category(b);
            if (ca != cb) return Integer.compare(ca, cb);
            if (ca == POPULAR) return Integer.compare(FIRST.indexOf(key(a)), FIRST.indexOf(key(b)));
            boolean ua = ukrainian(a), ub = ukrainian(b);
            if (ua != ub) return ua ? -1 : 1;
            return abc.compare(a.name.trim(), b.name.trim());
        });
        return out;
    }

    private static boolean ukrainian(Channel c) {
        String k = key(c);
        char first = k.isEmpty() ? 'a' : k.charAt(0);
        return Character.UnicodeBlock.of(first) == Character.UnicodeBlock.CYRILLIC || Character.isDigit(first);
    }

    private static String key(Channel c) {
        return c.name.trim().toLowerCase(java.util.Locale.ROOT);
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
