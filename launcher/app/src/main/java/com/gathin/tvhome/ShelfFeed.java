package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posters for the top shelf (config.xml shelf_feed_apps): film and series backdrops from TMDB, shown while one of
 * those dock apps is focused. Netflix gets what is popular on Netflix in the TV's region (its network for series,
 * its watch provider for films); Kyivstar TV Ukrainian series and films, then what is popular in the region; every
 * other listed app what is trending this week. Kyivstar TV, through the Worker, shows its own posters instead: what is
 * popular on Kyivstar TV, and with a channel focused the programmes popular on that channel; Spotify the songs most
 * played on Spotify in Ukraine, each album cover set on a 16:9 picture of its own (poster()), and YouTube Music the
 * same songs, opened by a search there. Titles come in the TV's
 * language. Lists are cached for six hours in preferences, images on disk, the last few decoded in memory as RGB_565
 * (about 1.8 MB each at 1280 px), so a ~1 GB TV is not squeezed. No key (BuildConfig.TMDB_KEY) means no requests.
 */
final class ShelfFeed {

    static final class Item {
        final String title;
        final String backdrop;
        /** A URI that opens it in its app (a program's intent_uri), or null. */
        final String open;
        /** TMDB kind ("movie" or "tv") and id, for a lookup of where it plays; "" and 0 for a channel program. */
        final String type;
        final long id;
        /** A live stream (a program's live flag): the shelf marks it. */
        final boolean live;
        /** TMDB's original title and first year, "" when unknown: they pick the right film in KIK's catalogue. */
        String original = "", year = "";

        Item(String title, String backdrop, String open, String type, long id) {
            this(title, backdrop, open, type, id, false);
        }

        Item(String title, String backdrop, String open, String type, long id, boolean live) {
            this.title = title;
            this.backdrop = backdrop;
            this.open = open;
            this.type = type == null ? "" : type;
            this.id = id;
            this.live = live;
        }
    }

    interface IdListener { void onId(String id); }

    interface ItemsListener { void onItems(List<Item> items); }
    /** bitmap is null when the backdrop could not be loaded. */
    interface ImageListener { void onImage(String backdrop, Bitmap bitmap); }

    private static final String TAG = "TvHome.ShelfFeed";
    /** TMDB's API, or the Worker that asks it on the launcher's behalf (config.xml tmdb_proxy). */
    private final String API;
    private static final String IMAGES = "https://image.tmdb.org/t/p/w1280";
    private static final long MAX_AGE_MS = 6 * 60 * 60 * 1000L;
    private static final String NETFLIX = "com.netflix.ninja";
    private static final String KYIVSTAR = "com.kyivstar.tv.tvapp";
    private static final String SPOTIFY = "com.spotify.tv.android";
    static final String YOUTUBE_MUSIC = "com.google.android.youtube.tvmusic";

    /** A music app: its shelf shows the popular songs, as a player shows a song (MainActivity). */
    static boolean music(String pkg) {
        return SPOTIFY.equals(pkg) || YOUTUBE_MUSIC.equals(pkg);
    }
    /** Titles the owner never wants on the shelf, whichever app or list offers them (any spelling, any season). */
    private static final java.util.regex.Pattern NEVER = java.util.regex.Pattern.compile(
            "(^|[^\\p{L}])(свати|сваты|svaty|слуга народу|слуга народа)([^\\p{L}]|$)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    static boolean never(String title) {
        return title != null && NEVER.matcher(title).find();
    }

    private final Context ctx;
    private final String key;
    private final String language;
    private final String region;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Pictures load on their own thread: a list fetch is a dozen requests, and a backdrop must not wait behind it. */
    private final ExecutorService pics = Executors.newSingleThreadExecutor();
    /** Everyone who asked for a list while it was loading; all hear the answer, not only the first. */
    private final Map<String, List<ItemsListener>> listWaiters = new HashMap<>();
    private final Map<String, List<Item>> lists = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    /** When a list last failed to load (offline, TMDB down): not retried on every focus, only after RETRY_MS. */
    private final Map<String, Long> failedAt = new HashMap<>();
    private static final long RETRY_MS = 5 * 60 * 1000L;
    /** Backdrops being fetched, with everyone waiting for each: one download however often it is asked for. */
    private final Map<String, List<ImageListener>> waiting = new HashMap<>();
    /** Pictures by size, about 10 MB: a few backdrops (1.8 MB each at 1280 px) and a channel row's logos together. */
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(10 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getAllocationByteCount(); }
    };

    ShelfFeed(Context ctx, String key, String proxy) {
        this.ctx = ctx.getApplicationContext();
        String p = proxy == null ? "" : proxy.trim();
        // With the Worker, no key goes along: it adds its own.
        this.key = p.isEmpty() && key != null ? key.trim() : "";
        this.API = p.isEmpty() ? "https://api.themoviedb.org/3/" : (p.endsWith("/") ? p : p + "/") + "3/";
        this.proxied = !p.isEmpty();
        this.proxyBase = p.isEmpty() ? "" : (p.endsWith("/") ? p : p + "/");
        java.util.Locale l = java.util.Locale.getDefault();
        this.language = l.getLanguage() + (l.getCountry().isEmpty() ? "" : "-" + l.getCountry());
        this.region = l.getCountry().isEmpty() ? "US" : l.getCountry();
    }

    private final boolean proxied;
    /** The Worker's own address ("" without one): Kyivstar TV's posters come from its /kyivstar routes. */
    private final String proxyBase;

    boolean enabled() {
        return proxied || !key.isEmpty();
    }

    /** What is cached for this app right now, without any fetch; empty when nothing is. */
    List<Item> cached(String pkg) {
        if (!enabled()) return java.util.Collections.emptyList();
        String group = group(pkg);
        if (group == null) return java.util.Collections.emptyList();
        List<Item> known = lists.get(group);
        if (known == null) {
            known = parse(prefs().getString(group, ""), !ordered(group));
            if (!known.isEmpty()) lists.put(group, known);
        }
        return forApp(pkg, known);
    }

    /** The cached list for this app at once if there is one, then a fresh one when the cache is old. */
    void items(String pkg, ItemsListener asked) {
        if (!enabled()) return;
        String group = group(pkg);
        if (group == null) return;
        ItemsListener listener = YOUTUBE_MUSIC.equals(pkg) ? items -> asked.onItems(forApp(pkg, items)) : asked;
        List<Item> known = lists.get(group);
        if (known == null) {
            known = parse(prefs().getString(group, ""), !ordered(group));
            if (!known.isEmpty()) lists.put(group, known);
        }
        if (!known.isEmpty()) listener.onItems(known);
        boolean fresh = System.currentTimeMillis() - prefs().getLong(group + "_at", 0) < MAX_AGE_MS;
        if (fresh && !known.isEmpty()) return;
        Long failed = failedAt.get(group);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return;
        List<ItemsListener> waiters = listWaiters.get(group);
        if (waiters == null) listWaiters.put(group, waiters = new ArrayList<>());
        if (!waiters.contains(listener)) waiters.add(listener);
        if (!loading.add(group)) return;
        if (io.isShutdown()) return;
        io.execute(() -> {
            List<Item> got = new ArrayList<>();
            JSONArray raw = new JSONArray();
            try {
                if ("kyivstar".equals(group)) fromWorker(proxyBase + "kyivstar/popular", got, raw);
                else if ("spotify".equals(group)) fromWorker(proxyBase + "spotify/popular", got, raw);
                else {
                String[] paths = "netflix".equals(group)
                        ? new String[]{"discover/tv?with_networks=213&sort_by=popularity.desc",
                                       "discover/movie?with_watch_providers=8&watch_region=" + region + "&sort_by=popularity.desc"}
                        : "ukraine".equals(group)
                        // Kyivstar TV: Ukrainian series and films first, then what is popular in the region.
                        ? new String[]{"discover/tv?with_origin_country=UA&sort_by=popularity.desc",
                                       "discover/movie?with_origin_country=UA&sort_by=popularity.desc",
                                       "movie/popular?region=" + region}
                        : new String[]{"trending/all/week", "movie/popular?region=" + region};
                for (String path : paths) collect(path, got, raw);
                }
            } catch (Exception e) {
                Log.w(TAG, "feed failed: " + e);
            }
            main.post(() -> {
                loading.remove(group);
                List<ItemsListener> all = listWaiters.remove(group);
                if (got.isEmpty()) {
                    failedAt.put(group, System.currentTimeMillis());
                    return;
                }
                failedAt.remove(group);
                if (!ordered(group)) Collections.shuffle(got);   // Kyivstar's and Spotify's come most popular first
                lists.put(group, got);
                prefs().edit().putString(group, raw.toString()).putLong(group + "_at", System.currentTimeMillis()).apply();
                if (all != null) for (ItemsListener l : all) l.onItems(got);
            });
        });
    }

    /** Which cached list an app's shelf shows; null for none (Spotify without the Worker). */
    private String group(String pkg) {
        if (NETFLIX.equals(pkg)) return "netflix";
        if (music(pkg)) return proxied ? "spotify" : null;
        if (KYIVSTAR.equals(Aliases.canonical(pkg))) return proxied ? "kyivstar" : "ukraine";
        return "trending";
    }

    /** YouTube Music gets Spotify's chart, each song opening YouTube Music's search for it. */
    private static List<Item> forApp(String pkg, List<Item> items) {
        if (!YOUTUBE_MUSIC.equals(pkg)) return items;
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            String q = i.title.replace(" · ", " ");
            try {
                q = java.net.URLEncoder.encode(q, "UTF-8");
            } catch (java.io.UnsupportedEncodingException ignored) {
            }
            out.add(new Item(i.title, i.backdrop, "https://music.youtube.com/search?q=" + q, "", 0));
        }
        return out;
    }

    private static boolean ordered(String group) {
        return "kyivstar".equals(group) || "spotify".equals(group);
    }

    /**
     * The programmes popular on one Kyivstar TV channel (the Worker's /kyivstar/channel), cached like the lists; the
     * listener hears them on the main thread, empty when the channel has none (its shelf keeps the app's posters).
     */
    void channelItems(String channelId, String channelName, ItemsListener listener) {
        if (!proxied) {
            listener.onItems(Collections.emptyList());
            return;
        }
        String group = "ch_" + channelId;
        List<Item> known = lists.get(group);
        if (known == null) {
            known = parse(prefs().getString(group, ""), false);
            lists.put(group, known);
        }
        boolean fresh = System.currentTimeMillis() - prefs().getLong(group + "_at", 0) < MAX_AGE_MS;
        if (fresh || io.isShutdown()) {
            listener.onItems(known);
            return;
        }
        Long failed = failedAt.get(group);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) {
            listener.onItems(known);
            return;
        }
        List<Item> stale = known;
        io.execute(() -> {
            List<Item> got = new ArrayList<>();
            JSONArray raw = new JSONArray();
            boolean ok = false;
            try {
                fromWorker(proxyBase + "kyivstar/channel?id=" + java.net.URLEncoder.encode(channelId, "UTF-8")
                        + "&name=" + java.net.URLEncoder.encode(channelName == null ? "" : channelName, "UTF-8"), got, raw);
                ok = true;
            } catch (Exception e) {
                Log.w(TAG, "channel feed failed: " + e);
            }
            boolean loaded = ok;
            main.post(() -> {
                if (!loaded) {
                    failedAt.put(group, System.currentTimeMillis());
                    listener.onItems(stale);
                    return;
                }
                failedAt.remove(group);
                lists.put(group, got);
                prefs().edit().putString(group, raw.toString()).putLong(group + "_at", System.currentTimeMillis()).apply();
                listener.onItems(got);
            });
        });
    }

    /** A list from the Worker's Kyivstar or Spotify routes: title, backdrop and the app's deep link. */
    private void fromWorker(String url, List<Item> into, JSONArray raw) throws Exception {
        JSONArray items = new JSONObject(new String(get(url, false), StandardCharsets.UTF_8)).getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.getJSONObject(i);
            String title = r.optString("title", "");
            String backdrop = r.optString("backdrop", "");
            String open = r.optString("open", "");
            if (title.isEmpty() || backdrop.isEmpty() || never(title)) continue;
            into.add(new Item(title, backdrop, open.isEmpty() ? null : open, "", 0));
            raw.put(new JSONObject().put("t", title).put("b", backdrop).put("u", open));
        }
    }

    /** The backdrop from memory, else disk, else TMDB; the listener hears it on the main thread. */
    void image(String backdrop, ImageListener listener) {
        Bitmap hit = images.get(backdrop);
        if (hit != null) {
            listener.onImage(backdrop, hit);
            return;
        }
        List<ImageListener> queue = waiting.get(backdrop);
        if (queue != null) {
            queue.add(listener);
            return;
        }
        queue = new ArrayList<>();
        queue.add(listener);
        if (pics.isShutdown()) return;
        waiting.put(backdrop, queue);
        pics.execute(() -> {
            Bitmap b = null;
            try {
                String name = backdrop.replaceAll("[^A-Za-z0-9._-]", "_");
                if (name.length() > 120) name = name.substring(name.length() - 120);   // a URL is long, the end is the file
                File f = new File(cacheDir(), name);
                if (!f.isFile()) {
                    byte[] data = backdrop.startsWith("http") ? getPicture(backdrop) : get(IMAGES + backdrop, false);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(data);
                    }
                    trimCache(f);
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                // A logo (PNG) keeps its transparency; a backdrop is a photo and can go without the alpha bytes.
                o.inPreferredConfig = backdrop.toLowerCase(java.util.Locale.ROOT).endsWith(".png")
                        ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                b = BitmapFactory.decodeFile(f.getPath(), o);
                if (b == null) f.delete();   // a broken download: fetch it again next time, not never show it
                else if (cover(backdrop)) {
                    Bitmap cover = b;
                    b = poster(cover);
                    cover.recycle();
                }
            } catch (Exception e) {
                Log.w(TAG, "image failed: " + e);
            }
            Bitmap done = b;
            main.post(() -> {
                List<ImageListener> all = waiting.remove(backdrop);
                if (all == null) return;
                if (done != null) images.put(backdrop, done);
                for (ImageListener l : all) l.onImage(backdrop, done);
            });
        });
    }

    /** A Spotify album cover: square, so it is set on a 16:9 picture rather than cropped to one. */
    private static boolean cover(String backdrop) {
        return backdrop.contains("spotifycdn.com/image/") || backdrop.contains("i.scdn.co/image/");
    }

    /** The cover on the right of its own colour field (glow), clear of the top bar and above the dock. */
    static Bitmap poster(Bitmap cover) {
        cover = software(cover);
        Bitmap out = glow(cover);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        int size = 330, x = out.getWidth() - size - 150, y = 135;
        android.graphics.RectF box = new android.graphics.RectF(x, y, x + size, y + size);
        android.graphics.Paint shadow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x59000000);
        c.drawRoundRect(new android.graphics.RectF(x + 4, y + 16, x + size + 4, y + size + 16), 24, 24, shadow);
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(box, 18, 18, android.graphics.Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawBitmap(cover, null, box, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        c.restore();
        return out;
    }

    /**
     * A 1280x720 field of the cover's own colours, as Apple Music lights its player: the cover's few most vivid
     * colours (distinct hues, made a little brighter and richer) as large soft glows over a deep shade of its average
     * colour, darker on the left, where the shelf's text sits. Behind the player it stands alone, since the player card
     * shows the cover itself.
     */
    static Bitmap glow(Bitmap cover) {
        cover = software(cover);
        int w = 1280, h = 720;
        // Full colour: in RGB_565 the soft glows fall into visible bands.
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        Bitmap small = Bitmap.createScaledBitmap(cover, 8, 8, true);
        int[] px = new int[64];
        small.getPixels(px, 0, 8, 0, 0, 8, 8);
        small.recycle();
        float[] avg = new float[3];
        Integer[] order = new Integer[64];
        float[][] hsv = new float[64][3];
        for (int i = 0; i < 64; i++) {
            android.graphics.Color.colorToHSV(px[i], hsv[i]);
            avg[0] += android.graphics.Color.red(px[i]) / 64f;
            avg[1] += android.graphics.Color.green(px[i]) / 64f;
            avg[2] += android.graphics.Color.blue(px[i]) / 64f;
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(score(hsv[b]), score(hsv[a])));
        List<Integer> picked = new ArrayList<>();
        for (int i : order) {
            if (picked.size() == 3) break;
            boolean distinct = true;
            for (int j : picked) {
                float d = Math.abs(hsv[i][0] - hsv[j][0]);
                if (Math.min(d, 360 - d) < 28 && Math.abs(hsv[i][2] - hsv[j][2]) < 0.3f) distinct = false;
            }
            if (distinct) picked.add(i);
        }
        float[] base = new float[3];
        android.graphics.Color.RGBToHSV((int) avg[0], (int) avg[1], (int) avg[2], base);
        base[2] = Math.min(base[2], 0.22f);
        c.drawColor(android.graphics.Color.HSVToColor(base));
        float[][] spots = {{0.82f, 0.32f, 0.72f}, {0.38f, 0.05f, 0.62f}, {0.58f, 1.0f, 0.6f}};
        for (int k = 0; k < picked.size(); k++) {
            float[] col = hsv[picked.get(k)].clone();
            col[1] = Math.min(1f, col[1] * 1.25f + 0.08f);
            col[2] = Math.max(col[2], 0.72f);
            int rgb = android.graphics.Color.HSVToColor(col);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.DITHER_FLAG);
            float r = spots[k][2] * w;
            p.setShader(new android.graphics.RadialGradient(spots[k][0] * w, spots[k][1] * h, r,
                    new int[]{rgb | 0xFF000000, (rgb & 0xFFFFFF) | 0x80000000, rgb & 0xFFFFFF},
                    new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }
        android.graphics.Paint shade = new android.graphics.Paint(android.graphics.Paint.DITHER_FLAG);
        shade.setShader(new android.graphics.LinearGradient(0, 0, w, 0, new int[]{0x80000000, 0x26000000, 0x00000000},
                null, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, shade);
        return out;
    }

    /** How much a colour lights the field: vivid and bright counts, grey and dark does not. */
    private static float score(float[] hsv) {
        return hsv[1] * 0.65f + hsv[2] * 0.35f;
    }

    /** A media session's cover can be a hardware bitmap, which a software canvas cannot draw. */
    private static Bitmap software(Bitmap b) {
        return b.getConfig() == Bitmap.Config.HARDWARE ? b.copy(Bitmap.Config.ARGB_8888, false) : b;
    }

    /** Stops the download thread; the activity is going away and nothing will hear the answers. */
    void close() {
        io.shutdownNow();
        pics.shutdownNow();
        main.removeCallbacksAndMessages(null);
        waiting.clear();
        images.evictAll();
    }

    /** Frees the decoded backdrops (Home is behind another app); they come back from disk when needed. */
    void trimMemory() {
        images.evictAll();
    }

    private void collect(String path, List<Item> into, JSONArray raw) throws Exception {
        String url = API + path + (path.contains("?") ? "&" : "?") + "language=" + language;
        JSONArray results = new JSONObject(new String(get(url, true), StandardCharsets.UTF_8)).getJSONArray("results");
        int start = into.size();
        for (int i = 0; i < results.length() && into.size() - start < 12; i++) {
            JSONObject r = results.getJSONObject(i);
            String title = r.optString("title", r.optString("name", ""));
            if (title.isEmpty() || never(title) || never(r.optString("original_title", r.optString("original_name", "")))) continue;
            // The list's backdrop often has the title painted on in English; ask for one without any text, so the
            // shelf shows the localized title only once.
            String type = r.optString("media_type", path.startsWith("discover/tv") ? "tv" : "movie");
            if (!type.equals("movie") && !type.equals("tv")) continue;
            String backdrop = textless(type, r.optLong("id"));
            if (backdrop.isEmpty()) continue;
            long id = r.optLong("id");
            Item item = new Item(title, backdrop, null, type, id);
            item.original = r.optString("original_title", r.optString("original_name", ""));
            String date = r.optString("release_date", r.optString("first_air_date", ""));
            item.year = date.length() >= 4 ? date.substring(0, 4) : "";
            into.add(item);
            raw.put(new JSONObject().put("t", title).put("b", backdrop).put("ty", type).put("id", id)
                    .put("o", item.original).put("y", item.year));
        }
    }

    /** The most popular backdrop with no text on it, or "" when TMDB has none. */
    private String textless(String type, long id) {
        try {
            String url = API + type + "/" + id + "/images?include_image_language=null";
            JSONArray b = new JSONObject(new String(get(url, true), StandardCharsets.UTF_8)).getJSONArray("backdrops");
            return b.length() > 0 ? b.getJSONObject(0).optString("file_path", "") : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static List<Item> parse(String json, boolean shuffle) {
        List<Item> out = new ArrayList<>();
        if (json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String open = o.optString("u", "");
                Item item = new Item(o.getString("t"), o.getString("b"), open.isEmpty() ? null : open,
                        o.optString("ty", ""), o.optLong("id"));
                item.original = o.optString("o", "");
                item.year = o.optString("y", "");
                if (never(item.title) || never(item.original)) continue;
                out.add(item);
            }
            if (shuffle) Collections.shuffle(out);
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * The Netflix title id of a TMDB film or series, through its Wikidata entry (Netflix ID, P1874): "" when it
     * has none there. Cached for good once known; the listener hears it on the main thread.
     */
    /** The Netflix id already looked up for a TMDB title: "" when it has none, null when not looked up yet. */
    String knownNetflixId(String type, long id) {
        return prefs().getString("nf_" + type + "_" + id, null);
    }

    void netflixId(String type, long id, IdListener listener) {
        String key = "nf_" + type + "_" + id;
        String known = prefs().getString(key, null);
        if (known != null) {
            listener.onId(known);
            return;
        }
        if (io.isShutdown()) return;
        io.execute(() -> {
            String found = "";
            try {
                JSONObject ext = new JSONObject(new String(get(API + type + "/" + id + "/external_ids?x=1", true),
                        StandardCharsets.UTF_8));
                String q = ext.optString("wikidata_id", "");
                if (!q.isEmpty()) {
                    String url = "https://www.wikidata.org/w/api.php?action=wbgetclaims&property=P1874&format=json&entity=" + q;
                    JSONObject claims = new JSONObject(new String(get(url, false), StandardCharsets.UTF_8)).getJSONObject("claims");
                    JSONArray p = claims.optJSONArray("P1874");
                    if (p != null && p.length() > 0) {
                        found = p.getJSONObject(0).getJSONObject("mainsnak").getJSONObject("datavalue").optString("value", "");
                    }
                }
                prefs().edit().putString(key, found).apply();
            } catch (Exception e) {
                Log.w(TAG, "netflix id failed: " + e);
            }
            String got = found;
            main.post(() -> listener.onId(got));
        });
    }

    /**
     * A picture by its own URL (ChannelFeed posters). A YouTube thumbnail comes as a small letterboxed frame; its
     * full-size one is at maxresdefault, which some videos lack, then the given one will do.
     */
    private byte[] getPicture(String url) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(https?://i\\.ytimg\\.com/vi(?:_webp)?/[^/]+/)[a-z]+\\.(jpg|webp)").matcher(url);
        if (m.matches()) {
            try {
                return get(m.group(1) + "maxresdefault." + m.group(2), false);
            } catch (Exception ignored) {
            }
        }
        return get(url, false);
    }

    /** A v4 read token (long JWT) goes in the Authorization header, a v3 key in the query. */
    private byte[] get(String url, boolean api) throws Exception {
        boolean bearer = key.length() > 40;
        if (api && !bearer && !key.isEmpty()) url += "&api_key=" + key;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        if (api && bearer && !key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new IllegalStateException("too large");
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private File cacheDir() {
        File d = new File(ctx.getCacheDir(), "shelf");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /**
     * Keeps the newest 40 backdrops on disk (about 8 MB), and always the one just downloaded. A file dated in the
     * future (the TV's clock was ahead when it was saved, then corrected) counts as the oldest: ranked as newest, it
     * made every fresh download the one to go, deleted before it could be shown.
     */
    private void trimCache(File keep) {
        File[] files = cacheDir().listFiles();
        if (files == null || files.length <= 40) return;
        long now = System.currentTimeMillis() + 60_000;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(age(b, keep, now), age(a, keep, now)));
        for (int i = 40; i < files.length; i++) files[i].delete();
    }

    private static long age(File f, File keep, long now) {
        if (f.equals(keep)) return Long.MAX_VALUE;
        long t = f.lastModified();
        return t > now ? 0 : t;
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("shelf_feed_v6", Context.MODE_PRIVATE);
    }
}
