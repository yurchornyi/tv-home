package com.gathin.tvhome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Looks for a launcher update twice a day over Wi-Fi, as KIK does, so a TV that keeps an app in front for days still
 * downloads a new build; Home installs or offers it the next time it comes up (Updater, MainActivity).
 * Home schedules it on every start, so it needs no boot permission.
 */
public class UpdateJob extends JobService {

    private static final int ID = 0x7476;   // "tv"
    private static final long EVERY_MS = 12 * 60 * 60 * 1000L;

    static void schedule(Context context, String updateUrl) {
        Context ctx = context.getApplicationContext();
        try {
            JobScheduler jobs = ctx.getSystemService(JobScheduler.class);
            if (updateUrl == null || updateUrl.trim().isEmpty()) {
                jobs.cancel(ID);
                return;
            }
            if (jobs.getPendingJob(ID) != null) return;
            jobs.schedule(new JobInfo.Builder(ID, new ComponentName(ctx, UpdateJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPeriodic(EVERY_MS)
                    .build());
        } catch (RuntimeException e) {
            Log.w("TvHome.Updater", "update job unavailable: " + e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                new Updater(this, Config.load(this).updateUrl, version -> { }).checkBlocking();
            } catch (RuntimeException e) {
                Log.w("TvHome.Updater", "update job failed: " + e);
            }
            jobFinished(params, false);
        }, "update-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
