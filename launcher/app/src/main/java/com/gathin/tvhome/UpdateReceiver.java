package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Hears back from Android's installer about an update (Updater.install): brings up the confirmation it asks for,
 * says when it failed. Success needs no handling: the new build starts as Home by itself.
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
}
