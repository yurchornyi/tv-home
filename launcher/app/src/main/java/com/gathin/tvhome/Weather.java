package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The temperature next to the top-bar clock (config.xml weather). Where: the TV's own last known location from
 * Android's location providers (network/fused, from Wi-Fi), rounded to about a kilometre; nothing is asked for.
 * What: the current temperature and weather code from Open-Meteo (free, no key), shown as a monochrome symbol. At most one request per
 * {@link #MAX_AGE_MS}, the last answer cached in preferences so Home shows it at once after a restart.
 */
final class Weather {

    interface Listener {
        /** A reading as "code|temperature" (WMO weather code, whole degrees), or "". Called on the main thread. */
        void onWeather(String reading);
    }

    private static final String TAG = "TvHome.Weather";
    private static final String PREFS = "weather";
    static final long MAX_AGE_MS = 30 * 60 * 1000L;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    Weather(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** The cached reading (possibly stale), or "". */
    String cached() {
        return prefs().getString("reading", "");
    }

    /** Fetches a new reading when the cached one is older than MAX_AGE_MS; the listener hears only a change. */
    void refresh(Listener listener) {
        if (busy) return;
        if (System.currentTimeMillis() - prefs().getLong("reading_at", 0) < MAX_AGE_MS) return;
        Location where = lastLocation();
        if (where == null) return;
        busy = true;
        new Thread(() -> {
            String text = null;
            try {
                text = fetch(where.getLatitude(), where.getLongitude());
            } catch (Exception e) {
                Log.w(TAG, "weather fetch failed: " + e);
            } finally {
                busy = false;
            }
            if (text == null) return;
            String shown = text;
            prefs().edit().putString("reading", shown).putLong("reading_at", System.currentTimeMillis()).apply();
            main.post(() -> listener.onWeather(shown));
        }, "weather").start();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The newest fix any provider still holds; null without location permission or a fix. */
    private Location lastLocation() {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        for (String provider : new String[]{"fused", LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        return best;
    }

    private static String fetch(double lat, double lon) throws Exception {
        // Two decimals (about 1 km) is plenty for weather and sends no more of the location than needed.
        String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code",
                lat, lon);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            JSONObject current = new JSONObject(out.toString(StandardCharsets.UTF_8.name())).getJSONObject("current");
            long temp = Math.round(current.getDouble("temperature_2m"));
            return current.optInt("weather_code", -1) + "|" + temp;
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather code to a monochrome symbol. */
    static int icon(int code) {
        if (code == 0) return R.drawable.ic_wx_sun;
        if (code == 1 || code == 2) return R.drawable.ic_wx_partly;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_wx_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_storm;
        return R.drawable.ic_wx_cloud;
    }
}
