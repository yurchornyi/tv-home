package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The optional account (Launcher settings, AccountActivity): an e-mail and a PIN of four to six digits, kept by the
 * Worker (config.xml tmdb_proxy, tools/tmdb-proxy/account.js). A TV signed in sends what Backup keeps (the tile order,
 * what is on Home, the shelf and channel choices) and the list of its apps whenever that changes, and takes in the
 * newest one another TV sent when Home comes to the front. The apps another TV of the account has and this one lacks
 * are offered for installing, one after another (AccountActivity). Nothing is sent while signed out.
 */
final class Account {

    interface Done { void onDone(boolean ok, String error); }

    private static final String TAG = "TvHome.Account";
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Home asks the account for news at most this often. */
    private static final long PULL_EVERY_MS = 60_000L;
    private static long lastPull;

    private Account() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("account", Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context ctx) {
        return !prefs(ctx).getString("token", "").isEmpty();
    }

    static String email(Context ctx) {
        return prefs(ctx).getString("email", "");
    }

    private static String base(Context ctx) {
        String p = ctx.getString(R.string.tmdb_proxy).trim();
        return p.isEmpty() ? "" : (p.endsWith("/") ? p : p + "/") + "account/";
    }

    static boolean available(Context ctx) {
        return !base(ctx).isEmpty();
    }

    /** This TV's own id, so it does not take in the state it sent itself. */
    private static String deviceId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String id = p.getString("device_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    /**
     * Signs in, or makes the account when the e-mail has none yet. A new account gets this TV's Home; an existing one
     * gives this TV its Home (applied: Home rebuilds when Launcher settings close) and its apps to install.
     */
    static void signIn(Context context, String email, String pin, Done done) {
        Context ctx = context.getApplicationContext();
        NET.execute(() -> {
            String error = null;
            try {
                JSONObject body = new JSONObject().put("email", email.trim()).put("pin", pin.trim());
                JSONObject r = call(ctx, "POST", "signin", null, body.toString());
                prefs(ctx).edit().putString("token", r.getString("token")).putString("email", r.optString("email", email))
                        .putBoolean("admin", r.optBoolean("admin")).putLong("remote_updated", 0).remove("synced").remove("apps").apply();
                if (r.optBoolean("created") || r.optLong("updated") == 0) push(ctx);
                else pull(ctx, true);
            } catch (HttpError e) {
                error = e.message;
            } catch (Exception e) {
                Log.w(TAG, "sign in failed: " + e);
                error = "offline";
            }
            String result = error;
            MAIN.post(() -> done.onDone(result == null, result));
        });
    }

    /** Ends this TV's sign-in; what it has stays as it is. */
    static void signOut(Context context, Runnable done) {
        Context ctx = context.getApplicationContext();
        String token = prefs(ctx).getString("token", "");
        prefs(ctx).edit().remove("token").remove("email").remove("admin").remove("synced").remove("apps").remove("remote_updated").apply();
        NET.execute(() -> {
            try {
                if (!token.isEmpty()) call(ctx, "POST", "signout", token, "{}");
            } catch (Exception e) {
                Log.w(TAG, "sign out: " + e);
            }
            MAIN.post(done);
        });
    }

    /** The owner's own account (the Worker's ADMIN_EMAIL): it sees every account and can delete one. */
    static boolean admin(Context ctx) {
        return signedIn(ctx) && prefs(ctx).getBoolean("admin", false);
    }

    /** One account as the owner's list shows it. */
    static final class Entry {
        final String email, device;
        final long created, updated;
        final int apps;
        final boolean blocked;

        Entry(JSONObject o) {
            blocked = o.optBoolean("blocked");
            email = o.optString("email");
            device = o.optString("device");
            created = o.optLong("created");
            updated = o.optLong("updated");
            apps = o.optInt("apps");
        }
    }

    interface Listed { void onList(List<Entry> accounts, String error); }

    /** Every account, the most recently synced first (admin only). */
    static void accounts(Context context, Listed done) {
        Context ctx = context.getApplicationContext();
        NET.execute(() -> {
            List<Entry> out = new ArrayList<>();
            String error = null;
            try {
                JSONArray a = call(ctx, "GET", "admin/accounts", prefs(ctx).getString("token", ""), null).getJSONArray("accounts");
                for (int i = 0; i < a.length(); i++) out.add(new Entry(a.getJSONObject(i)));
            } catch (HttpError e) {
                error = e.message;
            } catch (Exception e) {
                error = "offline";
            }
            String result = error;
            MAIN.post(() -> done.onList(out, result));
        });
    }

    /** Deletes an account, its Home and every TV's sign-in to it (admin only). */
    static void delete(Context context, String email, Done done) {
        admin(context, "admin/delete", email, null, done);
    }

    /** Blocks an account (no sign-in, no sync, its Home kept) or lets it in again (admin only). */
    static void block(Context context, String email, boolean blocked, Done done) {
        admin(context, "admin/block", email, blocked, done);
    }

    private static void admin(Context context, String path, String email, Boolean blocked, Done done) {
        Context ctx = context.getApplicationContext();
        NET.execute(() -> {
            String error = null;
            try {
                JSONObject body = new JSONObject().put("email", email);
                if (blocked != null) body.put("blocked", blocked.booleanValue());
                call(ctx, "POST", path, prefs(ctx).getString("token", ""), body.toString());
            } catch (HttpError e) {
                error = e.message;
            } catch (Exception e) {
                error = "offline";
            }
            String result = error;
            MAIN.post(() -> done.onDone(result == null, result));
        });
    }

    /** Backup wrote a new file: send it when it is not what this TV last sent or took in. */
    static void changed(Context context) {
        Context ctx = context.getApplicationContext();
        if (!signedIn(ctx)) return;
        NET.execute(() -> {
            try {
                push(ctx);
            } catch (Exception e) {
                Log.w(TAG, "push failed: " + e);
            }
        });
    }

    /** Home is in front: takes in what another TV sent since; done hears true when Home has to rebuild. */
    static void refresh(Context context, java.util.function.Consumer<Boolean> done) {
        Context ctx = context.getApplicationContext();
        if (!signedIn(ctx) || System.currentTimeMillis() - lastPull < PULL_EVERY_MS) return;
        lastPull = System.currentTimeMillis();
        NET.execute(() -> {
            boolean applied = false;
            try {
                applied = pull(ctx, false);
            } catch (Exception e) {
                Log.w(TAG, "pull failed: " + e);
            }
            boolean result = applied;
            MAIN.post(() -> done.accept(result));
        });
    }

    /** Sync now (AccountActivity): this TV's changes go up, then the newest comes down. */
    static void syncNow(Context context, Done done) {
        Context ctx = context.getApplicationContext();
        NET.execute(() -> {
            String error = null;
            try {
                if (!pull(ctx, false)) push(ctx);
            } catch (HttpError e) {
                error = e.message;
            } catch (Exception e) {
                error = "offline";
            }
            String result = error;
            MAIN.post(() -> done.onDone(result == null, result));
        });
    }

    private static void push(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        String token = p.getString("token", "");
        if (token.isEmpty()) return;
        JSONObject backup = Backup.snapshot(ctx);
        backup.remove("launcher");   // an update alone is no change to send
        String hash = Integer.toHexString(backup.toString().hashCode());
        if (hash.equals(p.getString("synced", ""))) return;
        JSONObject state = new JSONObject().put("backup", backup).put("apps", installedApps(ctx))
                .put("device_id", deviceId(ctx));
        JSONObject body = new JSONObject().put("state", state).put("device", Build.MANUFACTURER + " " + Build.MODEL);
        JSONObject r = call(ctx, "PUT", "state", token, body.toString());
        p.edit().putString("synced", hash).putLong("remote_updated", r.optLong("updated")).apply();
        Log.i(TAG, "state sent");
    }

    /** Takes in the account's state when it is newer than what this TV has; true when something was applied. */
    private static boolean pull(Context ctx, boolean force) throws Exception {
        SharedPreferences p = prefs(ctx);
        String token = p.getString("token", "");
        if (token.isEmpty()) return false;
        JSONObject r = call(ctx, "GET", "state", token, null);
        long updated = r.optLong("updated");
        JSONObject state = r.optJSONObject("state");
        if (state == null || (!force && updated <= p.getLong("remote_updated", 0))) return false;
        JSONArray apps = state.optJSONArray("apps");
        SharedPreferences.Editor e = p.edit().putLong("remote_updated", updated);
        if (apps != null) e.putString("apps", apps.toString());
        e.apply();
        if (deviceId(ctx).equals(state.optString("device_id")) && !force) return false;
        JSONObject backup = state.optJSONObject("backup");
        if (backup == null) return false;
        Backup.apply(ctx, backup);
        JSONObject now = Backup.snapshot(ctx);
        now.remove("launcher");
        p.edit().putString("synced", Integer.toHexString(now.toString().hashCode())).apply();
        Log.i(TAG, "state taken in from " + r.optString("device"));
        return true;
    }

    /** The apps on this TV the owner installed (not the TV's own), by package, with their names. */
    private static JSONArray installedApps(Context ctx) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        Map<String, String> found = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(ctx.getPackageName())) continue;
                if (!found.containsKey(ai.packageName)) found.put(ai.packageName, String.valueOf(ai.loadLabel(pm)));
            }
        }
        JSONArray out = new JSONArray();
        for (Map.Entry<String, String> a : found.entrySet()) {
            out.put(new JSONObject().put("pkg", a.getKey()).put("label", a.getValue()));
        }
        return out;
    }

    /** One app another TV of the account has. */
    static final class App {
        final String pkg, label;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    /** The account's apps this TV does not have (another of its package names counts as having it, Aliases). */
    static List<App> missing(Context ctx) {
        List<App> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString("apps", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String pkg = o.getString("pkg");
                if (has(pm, Aliases.installed(pm, pkg))) continue;
                out.add(new App(pkg, o.optString("label", pkg)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static boolean has(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static final class HttpError extends Exception {
        final String message;

        HttpError(String message) {
            super(message);
            this.message = message;
        }
    }

    private static JSONObject call(Context ctx, String method, String path, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base(ctx) + path).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = in == null ? "{}" : new String(readAll(in), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(text.isEmpty() ? "{}" : text);
            if (code == 401) {
                // Signed out elsewhere (or the token is gone): this TV is signed out too.
                prefs(ctx).edit().remove("token").apply();
            }
            if (code >= 400) throw new HttpError(o.optString("error", "HTTP " + code));
            return o;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
