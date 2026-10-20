package com.gathin.tvhome;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bluetooth headphones and speakers for the top bar: which one is connected (shown as a headphones icon next to the
 * network icon) and the banner when one connects (BluetoothReceiver, AudioBanner). Remotes and keyboards don't count:
 * only the audio/video device class does.
 */
final class AudioDevices {

    interface Listener { void onAudioChanged(); }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Name of the connected audio device, or null. Kept by BluetoothReceiver and refresh(). */
    private static volatile String connected;
    /** The connected device is a speaker rather than headphones. */
    private static volatile boolean speaker;
    /** Battery the connected device last reported, or -1. */
    private static volatile int battery = -1;

    static int battery() {
        return battery;
    }

    static void setBattery(int level) {
        battery = level;
    }

    private AudioDevices() { }

    static String connected() {
        return connected;
    }

    static void addListener(Listener l) { listeners.add(l); }
    static void removeListener(Listener l) { listeners.remove(l); }

    static boolean speaker() {
        return speaker;
    }

    /** The icon for this kind of device: a speaker or headphones. */
    static int icon(boolean isSpeaker) {
        return isSpeaker ? R.drawable.ic_speaker : R.drawable.ic_headphones;
    }

    static void set(String name, boolean isSpeaker) {
        connected = name;
        speaker = isSpeaker;
        for (Listener l : listeners) l.onAudioChanged();
    }

    static boolean isAudio(BluetoothDevice d) {
        try {
            BluetoothClass c = d.getBluetoothClass();
            return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Loudspeakers, portable and hi-fi audio count as speakers; everything else in the class as headphones. */
    static boolean isSpeaker(BluetoothDevice d) {
        try {
            BluetoothClass c = d.getBluetoothClass();
            if (c == null) return false;
            int m = c.getDeviceClass();
            return m == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER || m == BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO
                    || m == BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO || m == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO;
        } catch (SecurityException e) {
            return false;
        }
    }

    static String name(BluetoothDevice d) {
        try {
            String n = android.os.Build.VERSION.SDK_INT >= 30 ? d.getAlias() : null;
            if (n == null || n.isEmpty()) n = d.getName();
            return n != null ? n : "";
        } catch (SecurityException e) {
            return "";
        }
    }

    /** Battery percentage the headset reports (hidden API), or -1. */
    static int battery(BluetoothDevice d) {
        try {
            Object v = BluetoothDevice.class.getMethod("getBatteryLevel").invoke(d);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Asks the A2DP profile which device is connected now (after a restart nothing told us). */
    static void refresh(Context ctx) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;
        try {
            adapter.getProfileProxy(ctx.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    String found = null;
                    boolean isSpeaker = false;
                    try {
                        for (BluetoothDevice d : proxy.getConnectedDevices()) {
                            found = name(d);
                            isSpeaker = isSpeaker(d);
                            battery = battery(d);
                            break;
                        }
                    } catch (SecurityException ignored) {
                    }
                    adapter.closeProfileProxy(profile, proxy);
                    String now = found;
                    boolean nowSpeaker = isSpeaker;
                    if (now == null ? connected != null : !now.equals(connected)) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> set(now, nowSpeaker));
                    }
                }

                @Override public void onServiceDisconnected(int profile) { }
            }, BluetoothProfile.A2DP);
        } catch (SecurityException ignored) {
        }
    }
}
