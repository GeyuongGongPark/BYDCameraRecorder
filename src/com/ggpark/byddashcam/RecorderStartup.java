package com.ggpark.byddashcam;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class RecorderStartup {
    public static final String ACTION_WATCHDOG =
            "com.ggpark.byddashcam.action.STARTUP_WATCHDOG";

    private static final long FALLBACK_INTERVAL_MILLIS = 15L * 60L * 1000L;
    private static final int JOB_ID = 4801;
    private static final int WATCHDOG_REQUEST_CODE = 4802;
    private static final String TAG = "BYDCamera";

    private RecorderStartup() {
    }

    public static boolean isStartupAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
                || "android.intent.action.USER_UNLOCKED".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                || ACTION_WATCHDOG.equals(action);
    }

    public static void scheduleFallbacks(Context context, String reason) {
        scheduleWatchdog(context, reason);
        schedulePersistedJob(context, reason);
    }

    public static void scheduleShortRecovery(Context context, String reason) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.w(TAG, "Startup short recovery unavailable: no AlarmManager; " + reason);
            return;
        }
        try {
            alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 10_000L,
                    watchdogIntent(context));
            Log.i(TAG, "Startup short recovery scheduled: " + reason);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Startup short recovery scheduling failed: " + reason, exception);
        }
    }

    public static boolean startIfEnabled(Context context, String reason) {
        RecorderSettings settings;
        try {
            settings = RecorderSettings.load(context);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Startup settings load failed: " + reason, exception);
            return false;
        }
        if (!settings.continuousRecordingEnabled && !settings.phoneAccessEnabled) {
            Log.i(TAG, "Startup skipped because recorder features are off: " + reason);
            return false;
        }
        Intent serviceIntent = new Intent(context, CameraRecorderService.class);
        serviceIntent.putExtra(CameraRecorderService.EXTRA_STARTUP_REASON, reason);
        if (Build.VERSION.SDK_INT >= 26) {
            if (startForegroundService(context, serviceIntent, reason)) {
                return true;
            }
        }
        try {
            context.startService(serviceIntent);
            Log.i(TAG, "Recorder service start requested with startService: " + reason);
            return true;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Recorder service startService failed: " + reason, exception);
            return false;
        }
    }

    private static PendingIntent watchdogIntent(Context context) {
        Intent intent = new Intent(context, RecorderBootReceiver.class);
        intent.setAction(ACTION_WATCHDOG);
        return PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void scheduleWatchdog(Context context, String reason) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.w(TAG, "Startup watchdog unavailable: no AlarmManager; " + reason);
            return;
        }
        try {
            alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + FALLBACK_INTERVAL_MILLIS,
                    FALLBACK_INTERVAL_MILLIS,
                    watchdogIntent(context));
            Log.i(TAG, "Startup watchdog scheduled: " + reason);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Startup watchdog scheduling failed: " + reason, exception);
        }
    }

    private static void schedulePersistedJob(Context context, String reason) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        JobScheduler scheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            Log.w(TAG, "Persisted startup job unavailable: no JobScheduler; " + reason);
            return;
        }
        try {
            JobInfo job = new JobInfo.Builder(
                    JOB_ID,
                    new ComponentName(context, RecorderStartupJobService.class))
                    .setPersisted(true)
                    .setPeriodic(FALLBACK_INTERVAL_MILLIS)
                    .build();
            int result = scheduler.schedule(job);
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.i(TAG, "Persisted startup job scheduled: " + reason);
            } else {
                Log.w(TAG, "Persisted startup job rejected: " + reason);
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Persisted startup job scheduling failed: " + reason, exception);
        }
    }

    private static boolean startForegroundService(
            Context context,
            Intent serviceIntent,
            String reason) {
        try {
            Method method =
                    Context.class.getMethod("startForegroundService", Intent.class);
            method.invoke(context, serviceIntent);
            Log.i(TAG, "Recorder foreground-service start requested: " + reason);
            return true;
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            Log.e(TAG, "Foreground-service API unavailable: " + reason, exception);
        } catch (InvocationTargetException exception) {
            Log.e(
                    TAG,
                    "Recorder foreground-service start failed: " + reason,
                    exception.getCause());
        } catch (RuntimeException exception) {
            Log.e(TAG, "Recorder foreground-service call failed: " + reason, exception);
        }
        return false;
    }
}
