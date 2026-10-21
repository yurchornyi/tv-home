package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The owner's choices in one file, backup.json in the launcher's external files folder (readable over adb, see
 * tools/backup-settings.sh): the tile order, what was added to or removed from Home, the shelf's poster switches,
 * the channel choices and counts. Written whenever they change; a file put there from outside (restore, or a
 * copy from another TV) is taken in at the next start. Nothing leaves the TV by itself.
 */
final class Backup {

    private static final String TAG = "TvHome.Backup";
    static final String FILE_NAME = "backup.json";
    private static final String[] FILES = {Order.FILE_NAME, "library_apps.txt", "library_home_removed.txt"};
    private static final String[] PREFS = {"shelf", "channels"};

    private Backup() { }

    private static File file(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, FILE_NAME);
    }

    private static SharedPreferences state(Context ctx) {
        return ctx.getSharedPreferences("backup", Context.MODE_PRIVATE);
    }

    /** Writes the file when something changed since the last time. */
    static void export(Context ctx) {
        File f = file(ctx);
        if (f == null) return;
        try {
            String json = snapshot(ctx).toString(1);
            String hash = Integer.toHexString(json.hashCode());
            if (hash.equals(state(ctx).getString("exported", "")) && f.isFile()) return;
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) throw new IllegalStateException("rename failed");
            state(ctx).edit().putString("exported", hash).putString("imported", hash).apply();
        } catch (Exception e) {
            Log.w(TAG, "export failed: " + e);
        }
    }

    /** Takes in a file that is not the launcher's own last export (a restore); true when something was applied. */
    static boolean importIfChanged(Context ctx) {
        File f = file(ctx);
        if (f == null || !f.isFile()) return false;
        try {
            byte[] raw;
            try (InputStream in = new FileInputStream(f)) {
                raw = readAll(in);
            }
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            String hash = Integer.toHexString(o.toString(1).hashCode());
            SharedPreferences st = state(ctx);
            if (hash.equals(st.getString("imported", "")) || hash.equals(st.getString("exported", ""))) return false;
            JSONObject files = o.optJSONObject("files");
            if (files != null) {
                for (String name : FILES) {
                    if (!files.has(name)) continue;
                    AtomicFile af = new AtomicFile(new File(ctx.getFilesDir(), name));
                    FileOutputStream out = af.startWrite();
                    try {
                        out.write(files.getString(name).getBytes(StandardCharsets.UTF_8));
                        af.finishWrite(out);
                    } catch (Exception e) {
                        af.failWrite(out);
                        throw e;
                    }
                }
            }
            JSONObject prefs = o.optJSONObject("prefs");
            if (prefs != null) {
                for (String name : PREFS) {
                    JSONObject p = prefs.optJSONObject(name);
                    if (p == null) continue;
                    SharedPreferences.Editor e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear();
                    for (Iterator<String> it = p.keys(); it.hasNext(); ) {
                        String k = it.next();
                        Object v = p.get(k);
                        if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                        else if (v instanceof Integer) e.putInt(k, (Integer) v);
                        else if (v instanceof Long) e.putLong(k, (Long) v);
                        else if (v instanceof JSONArray) {
                            Set<String> set = new HashSet<>();
                            JSONArray a = (JSONArray) v;
                            for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                            e.putStringSet(k, set);
                        } else e.putString(k, String.valueOf(v));
                    }
                    e.apply();
                }
            }
            st.edit().putString("imported", hash).putString("exported", hash).apply();
            Log.i(TAG, "settings restored from " + f);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "import failed: " + e);
            return false;
        }
    }

    private static JSONObject snapshot(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        o.put("launcher", BuildConfig.VERSION_NAME);
        JSONObject files = new JSONObject();
        for (String name : FILES) {
            File f = new File(ctx.getFilesDir(), name);
            if (!f.isFile()) continue;
            try (InputStream in = new FileInputStream(f)) {
                files.put(name, new String(readAll(in), StandardCharsets.UTF_8));
            }
        }
        o.put("files", files);
        JSONObject prefs = new JSONObject();
        for (String name : PREFS) {
            JSONObject p = new JSONObject();
            for (Map.Entry<String, ?> e : ctx.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                Object v = e.getValue();
                if (v instanceof Set) p.put(e.getKey(), new JSONArray((Set<?>) v));
                else p.put(e.getKey(), v);
            }
            prefs.put(name, p);
        }
        o.put("prefs", prefs);
        return o;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
