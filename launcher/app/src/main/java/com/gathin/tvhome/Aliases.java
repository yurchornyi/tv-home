package com.gathin.tvhome;

import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One app published under more than one package name. Kyivstar TV is com.kyivstar.tv.tvapp on some TVs (the XGIMI
 * projector) and com.kyivstar.tv.androidtv on others (TCL): the same app, with the same activities and deep links.
 * config.xml, assets/defaults.json, backups and assets/channels name the first of a group; on a TV that has another
 * member installed, those names are read as the installed one, so the dock, the shelf and the channel row come out
 * the same on every TV.
 */
final class Aliases {

    /** Each group's first name is the one files are keyed by. */
    private static final String[][] GROUPS = {
            {"com.kyivstar.tv.tvapp", "com.kyivstar.tv.androidtv"},
    };

    private Aliases() { }

    private static String[] group(String pkg) {
        for (String[] g : GROUPS) {
            for (String p : g) if (p.equals(pkg)) return g;
        }
        return null;
    }

    /** The group's first name (what assets/channels is keyed by), or pkg itself outside any group. */
    static String canonical(String pkg) {
        String[] g = group(pkg);
        return g == null ? pkg : g[0];
    }

    /** The member of pkg's group installed on this TV; pkg itself when it is installed, outside a group, or none is. */
    static String installed(PackageManager pm, String pkg) {
        String[] g = group(pkg);
        if (g == null || isInstalled(pm, pkg)) return pkg;
        for (String p : g) if (!p.equals(pkg) && isInstalled(pm, p)) return p;
        return pkg;
    }

    /** The list with every name read as its installed member, in the same order. */
    static List<String> localize(PackageManager pm, List<String> pkgs) {
        List<String> out = new ArrayList<>(pkgs.size());
        for (String p : pkgs) out.add(installed(pm, p));
        return out;
    }

    /**
     * Text (an order file, a preference key) with every whole package name of a group replaced by the member
     * installed here. Unchanged when no member is installed or the text names none.
     */
    static String localize(PackageManager pm, String text) {
        for (String[] g : GROUPS) {
            String here = null;
            for (String p : g) {
                if (isInstalled(pm, p)) {
                    here = p;
                    break;
                }
            }
            if (here == null) continue;
            for (String p : g) {
                if (p.equals(here) || !text.contains(p)) continue;
                // A whole name only, not part of a longer package such as com.kyivstar.tv.tvapp.beta; a preference
                // key prefix like "mode_" may come right before it.
                Pattern name = Pattern.compile("(?<![A-Za-z0-9.])" + Pattern.quote(p) + "(?![A-Za-z0-9_]|\\.[A-Za-z])");
                text = name.matcher(text).replaceAll(Matcher.quoteReplacement(here));
            }
        }
        return text;
    }

    /**
     * A parsed JSON value (a backup, assets/defaults.json) with every key and string localized. Done on the parsed
     * value, not the file's text, where an escaped newline would put an "n" right before a name in the order file.
     */
    static Object localizeJson(PackageManager pm, Object json) throws JSONException {
        if (json instanceof String) return localize(pm, (String) json);
        if (json instanceof JSONObject) {
            JSONObject in = (JSONObject) json, out = new JSONObject();
            for (Iterator<String> it = in.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(localize(pm, k), localizeJson(pm, in.get(k)));
            }
            return out;
        }
        if (json instanceof JSONArray) {
            JSONArray in = (JSONArray) json, out = new JSONArray();
            for (int i = 0; i < in.length(); i++) out.put(localizeJson(pm, in.get(i)));
            return out;
        }
        return json;
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
