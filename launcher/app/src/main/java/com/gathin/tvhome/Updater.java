package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the launcher current on every TV it is on (config.xml update_url): now and then it reads a small JSON
 * ({"versionCode": 6, "versionName": "2.5", "apk": "https://..."}, which launcher/release.sh publishes with each
 * release), and when that names a newer build it downloads the APK in the background. Installing is one OK
 * away: the Settings menu (hold OK on the Settings pill) offers "Update to 2.5", and Android asks once to
 * confirm. An update must carry the same signature as the installed build, or Android refuses it.
 */
final class Updater {

    interface Listener { void onUpdateReady(String versionName); }

    private static final String TAG = "TvHome.Updater";
    private static final long CHECK_MS = 60 * 60 * 1000L;

    private final Context ctx;
    private final String url;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    /** Told on the main thread when a check finds nothing newer (Launcher settings shows "Up to date"). */
    private volatile Runnable upToDate;

    void onUpToDate(Runnable r) {
        upToDate = r;
    }

    Updater(Context ctx, String url, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.url = url == null ? "" : url.trim();
        this.listener = listener;
    }

    boolean enabled() {
        return !url.isEmpty();
    }

    /** The version downloaded and waiting to be installed, or null. */
    String readyVersion() {
        SharedPreferences p = prefs();
        if (p.getInt("ready_code", 0) <= BuildConfig.VERSION_CODE || !apkFile().isFile()) return null;
        return p.getString("ready_name", "");
    }

    /** Looks for a newer build at most once an hour; tells the listener once one is downloaded. */
    void check() {
        check(false);
    }

    /** now: the launcher starting, the screen coming on or the Settings menu ask at once (at most every few minutes). */
    void check(boolean now) {
        if (!enabled() || busy) return;
        if (readyVersion() != null) {
            listener.onUpdateReady(readyVersion());
            return;
        }
        long since = System.currentTimeMillis() - prefs().getLong("checked_at", 0);
        if (since < (now ? 3 * 60 * 1000L : CHECK_MS)) {
            Runnable r = upToDate;
            if (now && r != null) main.post(r);   // looked a moment ago and found nothing
            return;
        }
        busy = true;
        new Thread(() -> {
            try {
                JSONObject info = new JSONObject(new String(get(url, 64 * 1024), StandardCharsets.UTF_8));
                int code = info.optInt("versionCode", 0);
                String name = info.optString("versionName", String.valueOf(code));
                String apk = info.optString("apk", "");
                prefs().edit().putLong("checked_at", System.currentTimeMillis()).apply();
                if (code <= BuildConfig.VERSION_CODE || apk.isEmpty()) {
                    Runnable r = upToDate;
                    if (r != null) main.post(r);
                    return;
                }
                File tmp = new File(ctx.getCacheDir(), "update.part");
                download(apk, tmp);
                if (!tmp.renameTo(apkFile())) throw new IllegalStateException("rename failed");
                prefs().edit().putInt("ready_code", code).putString("ready_name", name).apply();
                main.post(() -> listener.onUpdateReady(name));
            } catch (Exception e) {
                Log.w(TAG, "update check failed: " + e);
            } finally {
                busy = false;
            }
        }, "updater").start();
    }

    /**
     * Hands the downloaded APK to Android's installer, asking for no confirmation; when Android still wants one it
     * comes back through UpdateReceiver. After the install Home restarts on its own.
     */
    void install() {
        File apk = apkFile();
        if (readyVersion() == null) return;
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(ctx.getPackageName());
            // No confirmation for the launcher's own update where Android allows it (Android 12+, when this app
            // installed the build in place, which it has after its first self-update); otherwise Android asks.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                // The stream must be closed before the commit (a second close would fail with EBADF).
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("tv-launcher.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent done = new Intent(ctx, UpdateReceiver.class).setAction(UpdateReceiver.ACTION);
                PendingIntent pi = PendingIntent.getBroadcast(ctx, id, done,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pi.getIntentSender());
            }
        } catch (Exception e) {
            Log.w(TAG, "install failed: " + e);
            android.widget.Toast.makeText(ctx, R.string.update_failed, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** Forgets a download Android refused or that was installed, so the next check starts clean. */
    void clearReady() {
        apkFile().delete();
        prefs().edit().remove("ready_code").remove("ready_name").apply();
    }

    private File apkFile() {
        return new File(ctx.getCacheDir(), "update.apk");
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "tv-launcher/" + BuildConfig.VERSION_NAME);
        return c;
    }

    private static byte[] get(String url, int max) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IllegalStateException("too large");
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 64L * 1024 * 1024) throw new IllegalStateException("too large");
            }
            if (total < 100 * 1024) throw new IllegalStateException("too small to be the launcher");
        } finally {
            c.disconnect();
        }
    }
}
