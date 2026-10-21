package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * What is playing, for the top shelf's Now Playing card: title, artist, cover, the player app and its position. Read
 * from the media sessions apps publish (Spotify, SmartTube, Apple Music...), which Android only shows to an enabled
 * notification listener (NowPlayingListener; build-and-install allows it with `cmd notification allow_listener`).
 * Without that access nothing is reported and the shelf behaves as before.
 */
final class NowPlaying {

    interface Listener { void onNowPlayingChanged(); }

    static final class Track {
        final String title, artist, app, pkg;
        final Bitmap cover;
        final long duration;
        final boolean playing;

        Track(String title, String artist, String app, String pkg, Bitmap cover, long duration, boolean playing) {
            this.title = title;
            this.artist = artist;
            this.app = app;
            this.pkg = pkg;
            this.cover = cover;
            this.duration = duration;
            this.playing = playing;
        }
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();
    private MediaSessionManager msm;
    private MediaController current;
    private Track track;
    /** The card was closed: stays hidden until something plays again. */
    private boolean closed;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { pick(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { pick(); }
        @Override public void onSessionDestroyed() { pick(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::watch;

    NowPlaying(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    void start() {
        msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName me = new ComponentName(ctx, NowPlayingListener.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessions, me, main);
            watch(msm.getActiveSessions(me));
        } catch (SecurityException e) {
            msm = null;   // no notification access: stay quiet
        }
    }

    void stop() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        msm = null;
    }

    /** The current track, playing or paused, or null when nothing is loaded or the card was closed. */
    Track track() {
        return track;
    }

    /** Playback position now, extrapolated from the last state the app reported. */
    long position() {
        PlaybackState s;
        try {
            s = current != null ? current.getPlaybackState() : null;
        } catch (RuntimeException e) {
            return 0;
        }
        if (s == null) return 0;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, pos);
    }

    private void watch(List<MediaController> controllers) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                try {
                    c.registerCallback(callback, main);
                    watched.add(c);
                } catch (RuntimeException ignored) {
                }
            }
        }
        pick();
    }

    /**
     * The first session that is playing; failing that, a paused one (the one shown before first), so the card stays
     * up with its play button. After the card was closed nothing shows until a session plays again.
     */
    private void pick() {
        MediaController found = null;
        for (MediaController c : watched) {
            if (state(c) == PlaybackState.STATE_PLAYING && hasTitle(c)) {
                found = c;
                break;
            }
        }
        if (found != null) {
            closed = false;
        } else if (!closed) {
            // Paused: keep the one shown before, else any active session that is paused (Android only reports
            // sessions the app still marks active, so a long-stopped player does not come back).
            if (current != null && watched.contains(current) && state(current) == PlaybackState.STATE_PAUSED
                    && hasTitle(current)) {
                found = current;
            } else {
                for (MediaController c : watched) {
                    if (state(c) == PlaybackState.STATE_PAUSED && hasTitle(c)) {
                        found = c;
                        break;
                    }
                }
            }
        }
        if (closed) found = null;
        // describe() reads the metadata again; a player that just let its session go returns none, then nothing shows.
        Track next = found == null ? null : describe(found);
        if (next == null) found = null;
        boolean same = next != null && track != null && next.title.equals(track.title) && next.artist.equals(track.artist);
        // Players resend the same cover as a new bitmap with every metadata update; keep the first one for the
        // track, so the card does not rebuild its backdrop (a visible flicker) on each update.
        if (same && track.cover != null) {
            next = new Track(next.title, next.artist, next.app, next.pkg, track.cover, next.duration, next.playing);
        }
        boolean changed = next == null ? track != null
                : !same || next.playing != track.playing || (track.cover == null && next.cover != null);
        current = found;
        track = next;
        if (changed) listener.onNowPlayingChanged();
    }

    /** The track this session reports, or null when it has no metadata or title any more. */
    private Track describe(MediaController c) {
        MediaMetadata m;
        try {
            m = c.getMetadata();
        } catch (RuntimeException e) {
            return null;
        }
        String title = m != null ? m.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
        if (title == null) return null;
        String artist = first(m.getString(MediaMetadata.METADATA_KEY_ARTIST),
                m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST), m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
        Bitmap cover = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (cover == null) cover = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (cover == null) cover = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String app = c.getPackageName();
        try {
            PackageManager pm = ctx.getPackageManager();
            app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(c.getPackageName(), 0)));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return new Track(title, artist, app, c.getPackageName(), cover, m.getLong(MediaMetadata.METADATA_KEY_DURATION),
                state(c) == PlaybackState.STATE_PLAYING);
    }

    private static int state(MediaController c) {
        try {
            PlaybackState s = c.getPlaybackState();
            return s != null ? s.getState() : PlaybackState.STATE_NONE;
        } catch (RuntimeException e) {   // the player's process died mid-call
            return PlaybackState.STATE_NONE;
        }
    }

    private static boolean hasTitle(MediaController c) {
        try {
            MediaMetadata m = c.getMetadata();
            return m != null && m.getString(MediaMetadata.METADATA_KEY_TITLE) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- remote control of the player, from the card's buttons ----

    void playPause() {
        if (current == null) return;
        boolean playing = state(current) == PlaybackState.STATE_PLAYING;
        send(c -> {
            if (playing) c.pause();
            else c.play();
        });
    }

    void next() {
        send(MediaController.TransportControls::skipToNext);
    }

    void previous() {
        send(MediaController.TransportControls::skipToPrevious);
    }

    /** A button press for the player; one that quit a moment ago must not take Home down with it. */
    private void send(java.util.function.Consumer<MediaController.TransportControls> action) {
        if (current == null) return;
        try {
            action.accept(current.getTransportControls());
        } catch (RuntimeException ignored) {
        }
    }

    /** Opens the player's own screen for what plays (its session activity), or false when it offers none. */
    boolean openPlayer() {
        if (current == null) return false;
        try {
            android.app.PendingIntent pi = current.getSessionActivity();
            if (pi == null) return false;
            // The player is in the background; its activity may start only because Home (in front) says so.
            android.os.Bundle options = null;
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                options = android.app.ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
            }
            pi.send(ctx, 0, null, null, null, null, options);
            return true;
        } catch (RuntimeException | android.app.PendingIntent.CanceledException e) {
            android.util.Log.w("TvHome.NowPlaying", "player screen: " + e);
            return false;
        }
    }

    /** Stops the player and hides the card until something plays again. */
    void close() {
        send(c -> {
            c.pause();
            c.stop();
        });
        closed = true;
        pick();
    }

    /** The cover blurred into the shelf's backdrop: a tiny copy scaled back up with filtering, darkened a little. */
    static android.graphics.drawable.BitmapDrawable soft(Context ctx, Bitmap cover) {
        Bitmap tiny;
        try {
            tiny = Bitmap.createScaledBitmap(cover, 8, 8, true);
        } catch (RuntimeException e) {   // a recycled or hardware cover: the card shows without the backdrop
            return null;
        }
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(1.4f);
        android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix();
        dim.setScale(0.6f, 0.6f, 0.6f, 1f);
        cm.postConcat(dim);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        Bitmap out = Bitmap.createBitmap(128, 80, Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(out).drawBitmap(tiny, null, new android.graphics.Rect(0, 0, 128, 80), paint);
        tiny.recycle();
        android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(ctx.getResources(), out);
        d.setFilterBitmap(true);
        return d;
    }

    private static String first(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }
}
