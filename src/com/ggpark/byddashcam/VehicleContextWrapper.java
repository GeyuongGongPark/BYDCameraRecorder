package com.ggpark.byddashcam;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

/**
 * BYD 비공개 API에 접근하기 위한 Context 래퍼.
 * checkPermission 계열 메서드를 항상 PERMISSION_GRANTED로 오버라이드합니다.
 */
final class VehicleContextWrapper extends ContextWrapper {
    VehicleContextWrapper(Context base) {
        super(base);
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkCallingPermission(String permission) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkSelfPermission(String permission) {
        return PackageManager.PERMISSION_GRANTED;
    }
}
