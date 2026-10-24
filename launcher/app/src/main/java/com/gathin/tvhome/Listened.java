package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The songs the owner played in a music app (ShelfFeed.music), as the Now Playing card saw them: title and artist,
 * the most recent first, thirty at most. The music shelves show them among the popular ones. Russian-language songs are
 * never kept. The list is in the "listened" preferences, which Backup carries, so an account brings it to every TV.
 */
final class Listened {

    static final String PREFS = "listened";
    private static final int MAX = 30;

    static final class Song {
        final String title, artist;

        Song(String title, String artist) {
            this.title = title;
            this.artist = artist;
        }

        String key() {
            return (title + "|" + artist).toLowerCase(java.util.Locale.ROOT);
        }
    }

    private Listened() { }

    /** Cyrillic text stays only with a letter Russian lacks (і, ї, є, ґ); Latin and other scripts always do. */
    static boolean notRussian(String text) {
        boolean cyrillic = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 'Ѐ' && ch <= 'ӿ') cyrillic = true;
            if ("ыэъёЫЭЪЁ".indexOf(ch) >= 0) return false;
        }
        if (!cyrillic) return true;
        for (int i = 0; i < text.length(); i++) if ("іїєґІЇЄҐ".indexOf(text.charAt(i)) >= 0) return true;
        return false;
    }

    /** A song is playing in a music app: it goes to the top of the list (moved there if it was in it already). */
    static void record(Context ctx, NowPlaying.Track t) {
        if (t == null || !t.playing || !ShelfFeed.music(t.pkg)) return;
        String title = t.title == null ? "" : t.title.trim();
        String artist = t.artist == null ? "" : t.artist.trim();
        if (title.isEmpty() || artist.isEmpty() || !notRussian(title) || !notRussian(artist)) return;
        List<Song> songs = all(ctx);
        Song song = new Song(title, artist);
        if (!songs.isEmpty() && songs.get(0).key().equals(song.key())) return;
        for (int i = songs.size() - 1; i >= 0; i--) if (songs.get(i).key().equals(song.key())) songs.remove(i);
        songs.add(0, song);
        while (songs.size() > MAX) songs.remove(songs.size() - 1);
        JSONArray a = new JSONArray();
        try {
            for (Song s : songs) a.put(new JSONObject().put("t", s.title).put("a", s.artist));
        } catch (Exception ignored) {
        }
        prefs(ctx).edit().putString("songs", a.toString()).apply();
    }

    static List<Song> all(Context ctx) {
        List<Song> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString("songs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Song s = new Song(o.optString("t"), o.optString("a"));
                // Kept from before the rule, or from another TV: the shelf shows none in Russian either way.
                if (!s.title.isEmpty() && notRussian(s.title) && notRussian(s.artist)) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
