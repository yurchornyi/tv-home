package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SmartTube's own settings carried by the account (Account): its sign-in, subscriptions' groups, player and interface
 * choices. SmartTube backs itself up now and then into Android/media/<its package>/data; a TV allowed to read all
 * files (build-and-install grants it) zips that folder, encrypts it with a key made from the account's PIN, and sends
 * it whenever it changes. A TV signed in to the account hands the newest one to SmartTube as a zip, the way a file
 * manager would (its BackupReceiverActivity); SmartTube then asks to restore it. The Worker only ever holds bytes it
 * cannot read: the PIN never leaves the TV in a form that opens them.
 */
final class AppSettings {

    static final String SMARTTUBE = "org.smarttube.stable";
    private static final String TAG = "TvHome.AppSettings";

    private AppSettings() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
    }

    /** SmartTube's package on this TV (another of its names counts, Aliases), or null when it is not installed. */
    static String smartTube(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        String pkg = Aliases.installed(pm, SMARTTUBE);
        try {
            pm.getPackageInfo(pkg, 0);
            return pkg;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Whether this TV may read SmartTube's backup folder (all-files access). */
    static boolean canRead() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private static File media(String pkg) {
        return new File(Environment.getExternalStorageDirectory(), "Android/media/" + pkg);
    }

    /** The key for the backups: PBKDF2 of the PIN with the e-mail as salt; made once at sign-in, kept on this TV. */
    static byte[] key(String email, String pin) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), ("tvhome:" + email.trim().toLowerCase(java.util.Locale.ROOT))
                .getBytes("UTF-8"), 100_000, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    /**
     * Account.push: SmartTube's backup folder zipped, encrypted, sent, when it changed since it was last sent. Runs on
     * the account's network thread. Nothing when SmartTube is not here, its folder cannot be read, or there is no key.
     */
    static void push(Context ctx, byte[] key, Account.Sender send) throws Exception {
        String pkg = smartTube(ctx);
        if (pkg == null || key == null || !canRead()) return;
        File data = new File(media(pkg), "data");
        if (!data.isDirectory()) return;
        StringBuilder sig = new StringBuilder();
        signature(data, "", sig);
        String hash = Integer.toHexString(sig.toString().hashCode());
        if (hash.equals(prefs(ctx).getString("sent", ""))) return;
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipped)) {
            add(zip, data, "data/");
        }
        long updated = send.send(SMARTTUBE, encrypt(key, zipped.toByteArray()));
        // What this TV sent is what it has: it is never offered back to it as another TV's.
        prefs(ctx).edit().putString("sent", hash).putLong("restored", updated).apply();
        Log.i(TAG, "SmartTube settings sent, " + zipped.size() + " bytes");
    }

    /**
     * Hands SmartTube the account's newest backup of its settings, when there is one this TV has not taken in yet
     * (or always, when asked by hand). The download runs on the account's thread; SmartTube opens on the main one.
     */
    static void restore(Activity activity, boolean always, Account.Done done) {
        String pkg = smartTube(activity);
        if (pkg == null) {
            done.onDone(false, "no smarttube");
            return;
        }
        long known = always ? -1 : prefs(activity).getLong("restored", 0);
        Account.fetchApp(activity, SMARTTUBE, known, (bytes, updated, error) -> {
            if (activity.isFinishing() || bytes == null) {
                done.onDone(false, error);
                return;
            }
            try {
                byte[] key = Account.backupKey(activity);
                if (key == null) throw new IllegalStateException("no key");
                File dir = new File(activity.getCacheDir(), "share");
                if (!dir.isDirectory()) dir.mkdirs();
                File zip = new File(dir, "SmartTubeBackup.zip");
                try (FileOutputStream out = new FileOutputStream(zip)) {
                    out.write(decrypt(key, bytes));
                }
                Uri uri = androidx.core.content.FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", zip);
                Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/zip").setPackage(pkg)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(view);
                prefs(activity).edit().putLong("restored", updated).apply();
                done.onDone(true, null);
            } catch (ActivityNotFoundException e) {
                done.onDone(false, "no smarttube");
            } catch (Exception e) {
                Log.w(TAG, "restore failed: " + e);
                done.onDone(false, "bad backup");
            }
        });
    }

    /** Forgets what was sent and taken in (signed out, or another account). */
    static void forget(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static void signature(File dir, String path, StringBuilder into) {
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) signature(f, path + f.getName() + "/", into);
            else into.append(path).append(f.getName()).append(':').append(f.length()).append(':').append(f.lastModified()).append('\n');
        }
    }

    private static void add(ZipOutputStream zip, File dir, String path) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        byte[] buf = new byte[16384];
        for (File f : files) {
            if (f.isDirectory()) {
                add(zip, f, path + f.getName() + "/");
                continue;
            }
            zip.putNextEntry(new ZipEntry(path + f.getName()));
            try (InputStream in = new FileInputStream(f)) {
                int n;
                while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
            }
            zip.closeEntry();
        }
    }

    /** AES-GCM: a fresh 12-byte nonce, then the ciphertext with its tag. */
    private static byte[] encrypt(byte[] key, byte[] plain) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] sealed = c.doFinal(plain);
        byte[] out = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(sealed, 0, out, iv.length, sealed.length);
        return out;
    }

    private static byte[] decrypt(byte[] key, byte[] box) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, box, 0, 12));
        return c.doFinal(box, 12, box.length - 12);
    }
}
