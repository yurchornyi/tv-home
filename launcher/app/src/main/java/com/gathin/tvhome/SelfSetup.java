package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Keeps the launcher's own system switches on: the sleep timer's accessibility service (SleepService) and the
 * Now Playing notification listener (NowPlayingListener). build-and-install grants WRITE_SECURE_SETTINGS once over
 * adb, and the grant outlives every update, so each start puts back whatever an update, a reset or a settings
 * screen turned off. A TV set up with no adb has no grant; the Settings menu then offers the screens instead.
 */
final class SelfSetup {

    private static final String TAG = "TvHome.Setup";

    private SelfSetup() { }

    static boolean canWriteSettings(Context ctx) {
        return ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    static void ensure(Context ctx) {
        if (!canWriteSettings(ctx)) return;
        try {
            if (addTo(ctx, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, new ComponentName(ctx, SleepService.class))) {
                Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Log.i(TAG, "sleep timer service turned on");
            }
            if (addTo(ctx, "enabled_notification_listeners", new ComponentName(ctx, NowPlayingListener.class))) {
                Log.i(TAG, "now playing access turned on");
            }
        } catch (Exception e) {
            Log.w(TAG, "could not turn on: " + e);
        }
    }

    /** Appends the component to a ':' list in secure settings, keeping the rest; true when it was missing. */
    private static boolean addTo(Context ctx, String key, ComponentName c) {
        String now = Settings.Secure.getString(ctx.getContentResolver(), key);
        String full = c.flattenToString(), brief = c.flattenToShortString();
        if (now != null) {
            for (String s : now.split(":")) if (s.equals(full) || s.equals(brief)) return false;
        }
        Settings.Secure.putString(ctx.getContentResolver(), key, now == null || now.isEmpty() ? full : now + ":" + full);
        return true;
    }
}
