package com.gathin.tvhome;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One launcher build, several devices: assets/profiles.json names the config.xml keys to change on a given
 * device (a projector has no HDMI inputs to show, a TV has), matched by what Android says the device is. The
 * first entry whose model, device, product or manufacturer substring matches wins; an entry with "default": true
 * applies when none does. A theme's config.json still overrides the profile (Config).
 */
final class DeviceProfile {

    private static final String TAG = "TvHome.Profile";

    private DeviceProfile() { }

    /** The matching profile's "config" object, or null when there is none. */
    static JSONObject load(Context ctx) {
        try (InputStream in = ctx.getAssets().open("profiles.json")) {
            byte[] buf = new byte[65536];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONArray all = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
            JSONObject fallback = null;
            for (int i = 0; i < all.length(); i++) {
                JSONObject p = all.getJSONObject(i);
                if (p.optBoolean("default", false)) {
                    if (fallback == null) fallback = p;
                    continue;
                }
                if (matches(p, "model", Build.MODEL) || matches(p, "device", Build.DEVICE)
                        || matches(p, "product", Build.PRODUCT) || matches(p, "manufacturer", Build.MANUFACTURER)) {
                    Log.i(TAG, "profile: " + p.optString("name", "?") + " for " + Build.MANUFACTURER + " " + Build.MODEL);
                    return p.optJSONObject("config");
                }
            }
            if (fallback != null) {
                Log.i(TAG, "profile: " + fallback.optString("name", "default") + " for " + Build.MANUFACTURER + " " + Build.MODEL);
                return fallback.optJSONObject("config");
            }
        } catch (Exception e) {
            Log.w(TAG, "profiles unreadable: " + e);
        }
        return null;
    }

    private static boolean matches(JSONObject p, String key, String value) {
        JSONArray a = p.optJSONArray(key);
        if (a == null || value == null) return false;
        String v = value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").toLowerCase(Locale.ROOT);
            if (!s.isEmpty() && v.contains(s)) return true;
        }
        return false;
    }
}
