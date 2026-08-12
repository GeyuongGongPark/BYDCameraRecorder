package com.ggpark.byddashcam;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

public final class RecorderStartupJobService extends JobService {
    private static final String TAG = "BYDCamera";

    @Override
    public boolean onStartJob(JobParameters parameters) {
        Log.i(TAG, "Persisted recorder startup job started");
        RecorderStartup.startIfEnabled(this, "persisted JobScheduler fallback");
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        Log.w(TAG, "Persisted recorder startup job stopped before completion");
        return true;
    }
}
