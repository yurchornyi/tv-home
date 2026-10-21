package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.util.Log;
import android.view.KeyEvent;

/** The sleep timer's alarm (SleepTimer): pauses whatever plays and puts the TV to sleep through SleepService. */
public class SleepReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        context.getSharedPreferences("sleep", Context.MODE_PRIVATE).edit().remove("at").apply();
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am != null) {
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
        }
        boolean slept = SleepService.sleepNow();
        Log.i("TvHome", "sleep timer: " + (slept ? "sleeping" : "SleepService not enabled, cannot sleep"));
    }
}
