package com.ggpark.byddashcam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

public final class BackgroundAccess {
    private static final String AUTO_PROMPT_SUPPRESSED =
            "auto_prompt_suppressed";
    private static final String PREFERENCES = "background_access";
    private static final String TAG = "BYDCamera";

    private BackgroundAccess() {
    }

    public static boolean isGranted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager powerManager =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(
                        context.getPackageName());
    }

    public static boolean shouldShowAutomaticPrompt(Context context) {
        return isRequestSupported(context)
                && !isGranted(context)
                && !preferences(context).getBoolean(
                        AUTO_PROMPT_SUPPRESSED,
                        false);
    }

    public static boolean isRequestSupported(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        Uri packageUri = Uri.parse("package:" + context.getPackageName());
        Intent request = new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                packageUri);
        ResolveInfo handler =
                context.getPackageManager().resolveActivity(request, 0);
        if (handler == null || handler.activityInfo == null) {
            return false;
        }
        String activityName = handler.activityInfo.name;
        return activityName == null
                || !activityName.toLowerCase().contains(".unsupport.");
    }

    public static void setAutomaticPromptSuppressed(
            Context context,
            boolean suppressed) {
        preferences(context)
                .edit()
                .putBoolean(AUTO_PROMPT_SUPPRESSED, suppressed)
                .apply();
    }

    public static void request(Activity activity) {
        if (isGranted(activity)) {
            return;
        }
        if (!isRequestSupported(activity)) {
            Log.w(
                    TAG,
                    "Battery-optimization exemption is not exposed by this Android build");
            return;
        }
        Uri packageUri = Uri.parse("package:" + activity.getPackageName());
        Intent request = new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                packageUri);
        try {
            activity.startActivity(request);
        } catch (RuntimeException exception) {
            Log.w(
                    TAG,
                    "Direct battery-optimization request is unavailable; "
                            + "opening the optimization list",
                    exception);
            try {
                activity.startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException fallbackException) {
                Log.w(
                        TAG,
                        "Battery-optimization list is unavailable; opening app details",
                        fallbackException);
                activity.startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        packageUri));
            }
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE);
    }
}
