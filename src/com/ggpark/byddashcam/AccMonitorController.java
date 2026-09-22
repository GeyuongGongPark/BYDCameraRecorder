package com.ggpark.byddashcam;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ACC(시동) 상태 감지 컨트롤러.
 *
 * BYDAutoBodyworkDevice.getPowerLevel()을 500ms 폴링하여 ACC ON/OFF를 감지합니다.
 * 오탐 방지를 위해 OFF는 3회 연속 판정 후 확정됩니다.
 *
 * powerLevel 매핑:
 *   0 = OFF (시동 꺼짐)
 *   1 = ACC (ACC 전원만 ON)
 *   2 = ON  (시동 ON)
 *   3 = OK  (주행 준비 완료)
 *   ACC_ON_THRESHOLD(1) 이상이면 ACC ON으로 판정합니다.
 */
public final class AccMonitorController {
    public interface Listener {
        /** ACC/시동이 꺼졌습니다 → 센트리 모드 진입 조건 */
        void onAccOff();
        /** ACC/시동이 켜졌습니다 → 주행 복귀 조건 */
        void onAccOn();
    }

    private static final String TAG = "BYDCamera";
    private static final long POLL_MS = 500L;
    // OFF 확정을 위한 연속 판정 횟수 (1.5초 지속돼야 OFF 확정)
    private static final int OFF_CONFIRM_COUNT = 3;
    // powerLevel >= 1이면 ACC ON (ACC 또는 시동)
    private static final int ACC_ON_MIN_LEVEL = 1;

    private final Context context;
    private volatile Listener listener;

    private Object bodyworkDevice;
    private Method methodGetPowerLevel;
    private boolean deviceReady = false;

    private ScheduledExecutorService executor;

    // 상태 추적 (폴링 스레드에서만 접근)
    private boolean accOn = true;    // 초기값: ON (안전 기본값)
    private int offCount = 0;
    private boolean initialized = false; // 첫 폴링 완료 여부

    public AccMonitorController(Context context) {
        this.context = context;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        initDevice();
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                poll();
            }
        }, 0L, POLL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** 현재 알려진 ACC 상태를 반환합니다. */
    public boolean isAccOn() {
        return accOn;
    }

    private void initDevice() {
        try {
            Class<?> cls = Class.forName(
                    "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            bodyworkDevice = getInstance.invoke(null, context);
            if (bodyworkDevice == null) {
                Log.w(TAG, "AccMonitor: bodywork device null");
                return;
            }
            methodGetPowerLevel = cls.getMethod("getPowerLevel");
            deviceReady = true;
            Log.i(TAG, "AccMonitor: bodywork device OK");
        } catch (Exception e) {
            Log.w(TAG, "AccMonitor: bodywork device unavailable: " + e.getMessage());
        }
    }

    private void poll() {
        if (!deviceReady) return;
        try {
            Object v = methodGetPowerLevel.invoke(bodyworkDevice);
            if (!(v instanceof Number)) return;
            int level = ((Number) v).intValue();

            // 센티넬 값(4=FAKE_OK, 255=INVALID) 무시
            if (level < 0 || level > 3) return;

            boolean nowOn = level >= ACC_ON_MIN_LEVEL;

            if (!initialized) {
                // 첫 번째 폴링: 초기 상태 설정, 콜백 없음
                initialized = true;
                accOn = nowOn;
                return;
            }

            if (nowOn) {
                offCount = 0;
                if (!accOn) {
                    accOn = true;
                    Listener l = listener;
                    if (l != null) l.onAccOn();
                    Log.i(TAG, "AccMonitor: ACC ON (powerLevel=" + level + ")");
                }
            } else {
                offCount++;
                if (offCount >= OFF_CONFIRM_COUNT && accOn) {
                    accOn = false;
                    Listener l = listener;
                    if (l != null) l.onAccOff();
                    Log.i(TAG, "AccMonitor: ACC OFF (powerLevel=" + level + ", confirmed=" + offCount + ")");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "AccMonitor poll error: " + e.getMessage());
        }
    }
}
