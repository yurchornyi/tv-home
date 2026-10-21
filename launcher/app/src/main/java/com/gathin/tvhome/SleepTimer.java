package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The sleep timer of the Settings menu: in so many minutes the TV goes to standby, whatever is on. An alarm fires
 * SleepReceiver, which pauses what plays and asks SleepService to put the screen to sleep; the end time is kept in
 * preferences so the menu can show what is left and Home can say so under the clock.
 */
final class SleepTimer {

    private static final String PREFS = "sleep";

    private SleepTimer() { }

    /** When the TV goes to sleep, in System.currentTimeMillis(), or 0 when no timer is set. */
    static long endsAt(Context ctx) {
        long at = prefs(ctx).getLong("at", 0);
        if (at != 0 && at < System.currentTimeMillis() - 60_000) {
            // Long past: the alarm fired (or was lost on a reboot); either way the timer is over.
            prefs(ctx).edit().remove("at").apply();
            return 0;
        }
        return at;
    }

    /** Whole minutes left, at least 1 while a timer is set; 0 without one. */
    static int minutesLeft(Context ctx) {
        long at = endsAt(ctx);
        if (at == 0) return 0;
        return (int) Math.max(1, (at - System.currentTimeMillis() + 30_000) / 60_000);
    }

    static void set(Context ctx, int minutes) {
        long at = System.currentTimeMillis() + minutes * 60_000L;
        prefs(ctx).edit().putLong("at", at).apply();
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx));
    }

    static void cancel(Context ctx) {
        prefs(ctx).edit().remove("at").apply();
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pending(ctx));
    }

    private static PendingIntent pending(Context ctx) {
        return PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, SleepReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
