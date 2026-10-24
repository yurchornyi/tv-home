package com.gathin.tvhome;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.List;

/**
 * Exists so Android lets the launcher read media sessions for the Now Playing card (NowPlaying). It reads no
 * notifications. While it is connected it also follows the music apps' sessions (ShelfFeed.music), so every song
 * played there, not only the one playing when Home comes back, joins the music shelves (Listened). It reads only the
 * title and artist, never the cover. build-and-install enables it with `cmd notification allow_listener`.
 */
public class NowPlayingListener extends NotificationListenerService {

    private MediaSessionManager msm;
    private final List<MediaController> watched = new ArrayList<>();

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { recordAll(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { recordAll(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::watch;

    @Override
    public void onListenerConnected() {
        msm = getSystemService(MediaSessionManager.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessions, new ComponentName(this, NowPlayingListener.class));
            watch(msm.getActiveSessions(new ComponentName(this, NowPlayingListener.class)));
        } catch (RuntimeException e) {
            msm = null;
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        msm = null;
    }

    private void watch(List<MediaController> all) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (all == null) return;
        for (MediaController c : all) {
            if (!ShelfFeed.music(c.getPackageName())) continue;
            c.registerCallback(callback);
            watched.add(c);
        }
        recordAll();
    }

    private void recordAll() {
        for (MediaController c : watched) {
            try {
                MediaMetadata m = c.getMetadata();
                PlaybackState s = c.getPlaybackState();
                if (m == null || s == null || s.getState() != PlaybackState.STATE_PLAYING) continue;
                String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                Listened.record(this, new NowPlaying.Track(m.getString(MediaMetadata.METADATA_KEY_TITLE), artist, "",
                        c.getPackageName(), null, 0, true));
            } catch (RuntimeException ignored) {
                // the player's process died mid-call
            }
        }
    }
}
