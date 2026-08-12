package com.ggpark.byddashcam;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Locale;

public final class FrameSourceFactory {
    private static final String BYD_CAMERA_PACKAGE = "com.byd.avc";
    private static final String BYD_CAMERA_PERMISSION =
            "android.permission.BYDAUTO_PANORAMA_COMMON";
    private static final String[] REQUIRED_CAMERA_PERMISSIONS = {
        android.Manifest.permission.CAMERA,
        BYD_CAMERA_PERMISSION
    };

    private FrameSourceFactory() {
    }

    public static FrameSource create(Context context, FrameSource.Listener listener) {
        // 차량 프로파일을 결정하고 FrameProcessor 상수를 초기화합니다.
        VehicleProfileRegistry.detectAndActivate(context);
        if (shouldUseFixture(context)) {
            return new FixtureFrameSource(listener);
        }
        return new AvmCameraController(listener);
    }

    public static boolean hasRequiredCameraPermissions(Context context) {
        if (shouldUseFixture(context)) {
            return true;
        }
        for (String permission : REQUIRED_CAMERA_PERMISSIONS) {
            if (context.checkSelfPermission(permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static String[] requiredCameraPermissions() {
        return REQUIRED_CAMERA_PERMISSIONS.clone();
    }

    public static boolean shouldUseFixture(Context context) {
        return isEmulatorBuild() && !hasBydCameraPackage(context);
    }

    private static boolean hasBydCameraPackage(Context context) {
        try {
            context.getPackageManager().getPackageInfo(
                    BYD_CAMERA_PACKAGE,
                    PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException | SecurityException exception) {
            return false;
        }
    }

    private static boolean isEmulatorBuild() {
        String fingerprint = lower(Build.FINGERPRINT);
        String hardware = lower(Build.HARDWARE);
        String model = lower(Build.MODEL);
        String product = lower(Build.PRODUCT);
        return fingerprint.startsWith("generic")
                || fingerprint.contains("emulator")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || model.contains("android sdk built for")
                || model.contains("emulator")
                || product.contains("sdk");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }
}
