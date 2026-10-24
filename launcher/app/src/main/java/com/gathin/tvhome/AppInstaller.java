package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Installs one of the owner's own apps from its public GitHub release (KIK: config.xml kik_update_url), the way the
 * launcher updates itself (Updater): reads the release's update.json, downloads the APK, checks its SHA-256, that it
 * is KIK and that KIK's own key signed it, and hands it to Android's installer, which asks the owner to confirm
 * (UpdateReceiver shows that screen). The package and the key are fixed here, not taken from update.json: whoever
 * could change that file could name anything there.
 */
final class AppInstaller {

    interface Done { void onDone(boolean started); }

    private static final String TAG = "TvHome.Install";
    static final String KIK_PACKAGE = "kik.app";
    /** SHA-256 of the certificate KIK's releases are signed with. */
    static final String KIK_SIGNER = "d8708680a4ebfee138bdb6a40bac6108e9200cb701f67026977147c3353add0a";

    private AppInstaller() { }

    static void install(Context context, String updateUrl, Done done) {
        Context ctx = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean started = false;
            try {
                JSONObject info = new JSONObject(new String(get(updateUrl, 64 * 1024), "UTF-8"));
                String pkg = KIK_PACKAGE;
                if (!pkg.equals(info.optString("application_id"))) throw new SecurityException("not KIK");
                String apkUrl = info.getString("apk_url");
                if (!apkUrl.startsWith("https://")) throw new SecurityException("not https");
                String sha = info.optString("sha256", "").trim().toLowerCase(java.util.Locale.ROOT);
                if (sha.isEmpty()) throw new SecurityException("no checksum");
                File apk = new File(ctx.getCacheDir(), "install-" + pkg + ".apk");
                download(apkUrl, apk);
                if (!sha.equals(sha256(apk))) throw new SecurityException("checksum mismatch");
                android.content.pm.PackageInfo got = ctx.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
                if (got == null || !pkg.equals(got.packageName)) throw new SecurityException("not KIK");
                String signer = signer(ctx.getPackageManager(), apk);
                if (signer != null && !KIK_SIGNER.equals(signer)) throw new SecurityException("signed by someone else");
                PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(pkg);
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream in = new FileInputStream(apk);
                         OutputStream out = session.openWrite("app.apk", 0, apk.length())) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    Intent status = new Intent(ctx, UpdateReceiver.class).setAction(UpdateReceiver.ACTION);
                    PendingIntent pi = PendingIntent.getBroadcast(ctx, id, status,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    session.commit(pi.getIntentSender());
                }
                started = true;
            } catch (Exception e) {
                Log.w(TAG, "install failed: " + e);
            }
            boolean result = started;
            main.post(() -> done.onDone(result));
        }, "app-install").start();
    }

    private static byte[] get(String url, int max) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IllegalStateException("too large");
            }
            return out.toByteArray();
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection c = open(url);
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(to)) throw new IllegalStateException("rename failed");
    }

    /** GitHub answers release downloads with redirects to another host, which HttpURLConnection will not follow alone. */
    private static HttpURLConnection open(String url) throws Exception {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                continue;
            }
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            return c;
        }
        throw new java.io.IOException("too many redirects");
    }

    /** SHA-256 of the certificate that signed an APK file, or null where this Android cannot read it from a file. */
    @SuppressWarnings("deprecation")
    static String signer(android.content.pm.PackageManager pm, File apk) {
        try {
            android.content.pm.Signature[] sigs = null;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(apk.getPath(),
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi != null && pi.signingInfo != null) sigs = pi.signingInfo.getApkContentsSigners();
            }
            if (sigs == null || sigs.length == 0) {
                android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(apk.getPath(),
                        android.content.pm.PackageManager.GET_SIGNATURES);
                if (pi != null) sigs = pi.signatures;
            }
            if (sigs == null || sigs.length == 0) return null;
            return hex(MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray()));
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
