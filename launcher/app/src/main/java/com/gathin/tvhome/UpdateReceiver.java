package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Hears back from Android's installer about an update (Updater.install): brings up the confirmation it asks for,
 * says when it failed. Success is handled by Replaced below, in the new build.
 */
public class UpdateReceiver extends BroadcastReceiver {

    static final String ACTION = "com.gathin.tvhome.action.UPDATE_STATUS";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (RuntimeException ignored) {
                }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, context.getString(R.string.update_failed) + (why != null ? "\n" + why : ""),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The launcher was just replaced by a newer build. When Updater.install() asked for it (in the last ten minutes)
     * Home comes back: some TVs would otherwise show whatever app sat behind Home, so Netflix seemed to start on
     * its own. An adb install leaves no mark and starts nothing.
     */
    public static class Replaced extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
            android.content.SharedPreferences p = context.getSharedPreferences("updater", Context.MODE_PRIVATE);
            long at = p.getLong(Updater.INSTALLING_AT, 0);
            p.edit().remove(Updater.INSTALLING_AT).apply();
            long since = System.currentTimeMillis() - at;
            if (at == 0 || since < 0 || since > 10 * 60 * 1000L) return;
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(home);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
