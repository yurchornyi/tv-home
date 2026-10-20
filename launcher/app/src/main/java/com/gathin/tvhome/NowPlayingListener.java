package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets the launcher read media sessions for the Now Playing card (NowPlaying). It reads no
 * notifications. build-and-install enables it with `cmd notification allow_listener`.
 */
public class NowPlayingListener extends NotificationListenerService {
}
