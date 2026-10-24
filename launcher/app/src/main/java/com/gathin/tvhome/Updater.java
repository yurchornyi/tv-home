package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * release), and when that names a newer build it downloads the APK in the background, the way KIK updates itself:
 * the file must match the release's SHA-256, be this package at that version and carry this build's signature.
 * Where Android installs it without asking (silent()) Home does so when nobody is watching; elsewhere Home offers
 * "Update / Later" over the screen, and the Settings menu (hold OK on the Settings pill) keeps "Update to 2.5".
 * UpdateJob looks twice a day over Wi-Fi even while another app is in front.
 */
final class Updater {

    interface Listener { void onUpdateReady(String versionName); }

    private static final String TAG = "TvHome.Updater";
    private static final long CHECK_MS = 60 * 60 * 1000L;
    /** When install() handed a build to Android (prefs "updater"): the replaced build then starts Home again. */
    static final String INSTALLING_AT = "installing_at";

    private final Context ctx;
    private final String url;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** One check at a time across Home, Launcher settings and UpdateJob: they share the same files. */
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY = new java.util.concurrent.atomic.AtomicBoolean();
    /** Told on the main thread when a check finds nothing newer (Launcher settings shows "Up to date"). */
    private volatile Runnable upToDate;
    /** When a check last failed (no network, say): the routine hourly look waits a while instead of trying on every
     *  return to Home; a look asked for at once (the screen coming on, the Settings menu) still goes ahead. */
    private static volatile long failedAt;
    private static final long RETRY_MS = 5 * 60 * 1000L;

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

    /** What the downloaded version brings (the release's notes), "" when it says nothing. */
    String readyNotes() {
        return readyVersion() == null ? "" : prefs().getString("ready_notes", "").trim();
    }

    /** Looks for a newer build at most once an hour; tells the listener once one is downloaded. */
    void check() {
        check(false);
    }

    /** now: the launcher starting, the screen coming on or the Settings menu ask at once (at most every few minutes). */
    void check(boolean now) {
        if (!enabled() || BUSY.get()) return;
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
        if (!now && SystemClock.elapsedRealtime() - failedAt < RETRY_MS) return;
        if (!BUSY.compareAndSet(false, true)) return;
        new Thread(this::fetch, "updater").start();
    }

    /** The hourly check on the caller's thread (UpdateJob runs it on its own); quiet when one is already waiting. */
    void checkBlocking() {
        if (!enabled() || readyVersion() != null) return;
        if (System.currentTimeMillis() - prefs().getLong("checked_at", 0) < CHECK_MS) return;
        if (SystemClock.elapsedRealtime() - failedAt < RETRY_MS) return;
        if (BUSY.compareAndSet(false, true)) fetch();
    }

    private void fetch() {
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
            verify(tmp, code, info.optString("sha256", ""));
            if (!tmp.renameTo(apkFile())) throw new IllegalStateException("rename failed");
            prefs().edit().putInt("ready_code", code).putString("ready_name", name)
                    .putString("ready_notes", info.optString("notes", "")).apply();
            Log.i(TAG, "downloaded " + name);
            main.post(() -> listener.onUpdateReady(name));
        } catch (Exception e) {
            Log.w(TAG, "update check failed: " + e);
            failedAt = SystemClock.elapsedRealtime();
        } finally {
            BUSY.set(false);
        }
    }

    /**
     * Android installs the update without asking: Android 12+ and this build was installed by the launcher itself
     * (after its first self-update). An adb install, or Android 9 on the TCL, asks every time.
     */
    boolean silent() {
        if (android.os.Build.VERSION.SDK_INT < 31) return false;
        try {
            return ctx.getPackageName().equals(ctx.getPackageManager()
                    .getInstallSourceInfo(ctx.getPackageName()).getInstallingPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /** As KIK checks its own: the release's SHA-256 (when it gives one), this package, that version, this signature. */
    @SuppressWarnings("deprecation")
    private void verify(File apk, int code, String sha) throws Exception {
        sha = sha.trim().toLowerCase(java.util.Locale.ROOT);
        // release.sh always writes one: a release without it is not one of ours.
        if (sha.isEmpty()) throw new SecurityException("no checksum");
        if (!sha.equals(AppInstaller.sha256(apk))) throw new SecurityException("checksum mismatch");
        PackageManager pm = ctx.getPackageManager();
        PackageInfo got = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (got == null || !ctx.getPackageName().equals(got.packageName) || got.versionCode != code) {
            throw new SecurityException("not this launcher at " + code);
        }
        // The new build must carry the key this one was signed with. Some older TVs read no signatures from an
        // archive; Android itself still refuses a foreign one then.
        String theirs = AppInstaller.signer(pm, apk);
        Signature[] mine = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
        if (theirs != null && mine != null && mine.length > 0 && !theirs.equals(
                AppInstaller.sha256(mine[0].toByteArray()))) {
            throw new SecurityException("signed by someone else");
        }
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
                // Android kills this process to replace it, and some TVs (TCL UnionTV) then bring up the task
                // behind Home, Netflix as a rule, instead of the new Home: UpdateReceiver.Replaced starts it.
                // commit(), not apply(): the process may be gone before an apply reaches the disk.
                prefs().edit().putLong(INSTALLING_AT, System.currentTimeMillis()).commit();
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
