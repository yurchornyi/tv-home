package com.gathin.tvhome;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * Headphones or a speaker connecting or leaving, from any app: updates the top bar's headphones icon and, on connect,
 * shows the banner over whatever is on screen (AudioBanner). ACL broadcasts reach manifest receivers despite the
 * background limits. TEST_BANNER (adb, needs the DUMP permission the shell has) shows the banner on demand.
 */
public class BluetoothReceiver extends BroadcastReceiver {

    static final String TEST_BANNER = "com.gathin.tvhome.action.TEST_AUDIO_BANNER";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (TEST_BANNER.equals(action)) {
            String name = intent.getStringExtra("name");
            AudioBanner.show(context, name != null ? name.replace('_', ' ') : "Headphones",
                    intent.getIntExtra("battery", -1), intent.getBooleanExtra("speaker", false));
            return;
        }
        BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (d == null || !AudioDevices.isAudio(d)) return;
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            String name = AudioDevices.name(d);
            boolean speaker = AudioDevices.isSpeaker(d);
            AudioDevices.set(name, speaker);
            // The headset reports its battery a moment after the link comes up.
            PendingResult pending = goAsync();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                int level = AudioDevices.battery(d);
                AudioDevices.setBattery(level);
                AudioBanner.show(context, name, level, speaker);
                pending.finish();
            }, 1500);
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            AudioDevices.set(null, false);
        }
    }
}
