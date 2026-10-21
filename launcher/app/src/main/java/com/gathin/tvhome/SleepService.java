package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/**
 * Puts the TV to sleep for the sleep timer (SleepTimer, SleepReceiver). An app may not turn the screen off by
 * itself; an accessibility service may lock it, which on a TV without a lock screen is standby. It filters no
 * keys and reads nothing. build-and-install.sh enables it:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.SleepService
 */
public class SleepService extends AccessibilityService {

    private static SleepService instance;

    /** True when the service is running and took the request. */
    static boolean sleepNow() {
        SleepService s = instance;
        if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        return s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    static boolean running() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
