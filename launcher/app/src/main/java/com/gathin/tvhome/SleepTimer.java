package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

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
        setAt(ctx, System.currentTimeMillis() + minutes * 60_000L);
    }

    /** Adds minutes to a running timer (the warning card's +15 min), or sets one from now when none runs. */
    static void extend(Context ctx, int minutes) {
        long end = endsAt(ctx);
        setAt(ctx, Math.max(end, System.currentTimeMillis()) + minutes * 60_000L);
    }

    private static void setAt(Context ctx, long at) {
        prefs(ctx).edit().putLong("at", at).apply();
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        schedule(am, at, pending(ctx));
        // A minute before: the card that offers to keep the TV on (SleepWarning).
        SleepWarning.hide();
        long warnAt = at - SleepWarning.LEAD_MS;
        if (warnAt > System.currentTimeMillis() + 5_000) schedule(am, warnAt, warning(ctx));
        else am.cancel(warning(ctx));
    }

    /**
     * An alarm-clock alarm where exact alarms are allowed. Android 14 asks for that even for setAlarmClock: the
     * manifest's USE_EXACT_ALARM grants it to this sideloaded launcher, and build-and-install allows the app-op too.
     * Should neither hold, an inexact alarm that may fire a little late, rather than a crash and a timer that never
     * fires at all.
     */
    private static void schedule(AlarmManager am, long at, PendingIntent pi) {
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        try {
            if (exact) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, pi), pi);
                return;
            }
        } catch (SecurityException ignored) {
            // fall through to the inexact alarm
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    static void cancel(Context ctx) {
        prefs(ctx).edit().remove("at").apply();
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) {
            am.cancel(pending(ctx));
            am.cancel(warning(ctx));
        }
        SleepWarning.hide();
    }

    private static PendingIntent warning(Context ctx) {
        return PendingIntent.getBroadcast(ctx, 1, new Intent(ctx, SleepWarning.Receiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent pending(Context ctx) {
        return PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, SleepReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
