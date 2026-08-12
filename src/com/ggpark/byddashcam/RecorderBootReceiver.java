package com.ggpark.byddashcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class RecorderBootReceiver extends BroadcastReceiver {
    private static final String TAG = "BYDCamera";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!RecorderStartup.isStartupAction(action)) {
            Log.w(TAG, "Ignoring unexpected recorder startup action: " + action);
            return;
        }
        Log.i(TAG, "Recorder startup broadcast received: " + action);
        RecorderStartup.scheduleFallbacks(context, "broadcast " + action);
        RecorderStartup.startIfEnabled(context, "broadcast " + action);
    }
}
