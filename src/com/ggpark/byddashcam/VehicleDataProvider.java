package com.ggpark.byddashcam;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * BYD 비공개 API에 Java Reflection으로 접근해 차량 텔레메트리를 100ms 주기로 폴링합니다.
 * BYD API 미지원 기기에서도 graceful degradation: UNAVAILABLE 텔레메트리를 콜백합니다.
 */
public final class VehicleDataProvider {
    public interface Listener {
        void onTelemetryUpdated(VehicleTelemetry telemetry);
    }

    private static final String TAG = "BYDCamera";
    private static final long POLL_INTERVAL_MS = 100L;

    private Object speedDevice;
    private Method methodGetCurrentSpeed;
    private Method methodGetAccelerateDeepness;
    private Method methodGetBrakeDeepness;

    private Object gearDevice;
    private Method methodGetCurrentGear;

    private Object lightDevice;
    private Method methodGetTurnLightFlashState;
    private Method methodGetLightStatus;

    private ScheduledExecutorService executor;
    private volatile Listener listener;
    private boolean anyDeviceAvailable;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start(Context context) {
        Context vehicleContext = new VehicleContextWrapper(context);
        initDevices(vehicleContext);
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        poll();
                    }
                },
                0L,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean isAnyDeviceAvailable() {
        return anyDeviceAvailable;
    }

    private void initDevices(Context context) {
        // 속도/가속/브레이크 디바이스
        try {
            Class<?> cls = Class.forName(
                    "android.hardware.bydauto.speed.BYDAutoSpeedDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            speedDevice = getInstance.invoke(null, context);
            methodGetCurrentSpeed = cls.getMethod("getCurrentSpeed");
            methodGetAccelerateDeepness = cls.getMethod("getAccelerateDeepness");
            methodGetBrakeDeepness = cls.getMethod("getBrakeDeepness");
            anyDeviceAvailable = true;
            Log.i(TAG, "BYD speed device initialized");
        } catch (Exception e) {
            Log.w(TAG, "BYD speed device unavailable: " + e.getMessage());
        }

        // 기어박스 디바이스
        try {
            Class<?> cls = Class.forName(
                    "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            gearDevice = getInstance.invoke(null, context);
            methodGetCurrentGear = cls.getMethod("getCurrentGear");
            anyDeviceAvailable = true;
            Log.i(TAG, "BYD gear device initialized");
        } catch (Exception e) {
            Log.w(TAG, "BYD gear device unavailable: " + e.getMessage());
        }

        // 조명 디바이스
        try {
            Class<?> cls = Class.forName(
                    "android.hardware.bydauto.light.BYDAutoLightDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            lightDevice = getInstance.invoke(null, context);
            methodGetTurnLightFlashState = cls.getMethod("getTurnLightFlashState");
            methodGetLightStatus = cls.getMethod("getLightStatus");
            anyDeviceAvailable = true;
            Log.i(TAG, "BYD light device initialized");
        } catch (Exception e) {
            Log.w(TAG, "BYD light device unavailable: " + e.getMessage());
        }
    }

    private void poll() {
        try {
            int speedKmh = 0;
            int acceleratorPercent = 0;
            int brakePercent = 0;

            if (speedDevice != null) {
                try {
                    Object v = methodGetCurrentSpeed.invoke(speedDevice);
                    if (v instanceof Number) {
                        speedKmh = Math.max(0, Math.min(255, ((Number) v).intValue()));
                    }
                } catch (Exception ignored) {
                }
                try {
                    Object v = methodGetAccelerateDeepness.invoke(speedDevice);
                    if (v instanceof Number) {
                        acceleratorPercent =
                                Math.max(0, Math.min(100, ((Number) v).intValue()));
                    }
                } catch (Exception ignored) {
                }
                try {
                    Object v = methodGetBrakeDeepness.invoke(speedDevice);
                    if (v instanceof Number) {
                        brakePercent =
                                Math.max(0, Math.min(100, ((Number) v).intValue()));
                    }
                } catch (Exception ignored) {
                }
            }

            int gearBlinkBeltFlags = 0;
            if (gearDevice != null) {
                try {
                    Object v = methodGetCurrentGear.invoke(gearDevice);
                    if (v instanceof Number) {
                        int g = ((Number) v).intValue();
                        // BYD 기어값: -1=R, 0=N, 1=P, 2=D
                        // 플래그: bit0=P, bit1=R, bit2=N, bit3=D
                        if (g == 1) {
                            gearBlinkBeltFlags |= 0x01; // P
                        } else if (g == -1) {
                            gearBlinkBeltFlags |= 0x02; // R
                        } else if (g == 0) {
                            gearBlinkBeltFlags |= 0x04; // N
                        } else if (g >= 2) {
                            gearBlinkBeltFlags |= 0x08; // D
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            if (lightDevice != null) {
                try {
                    // 방향지시등: 1=off, 2=left, 4=right, 6=hazard
                    Object v = methodGetTurnLightFlashState.invoke(lightDevice);
                    if (v instanceof Number) {
                        int state = ((Number) v).intValue();
                        if (state == 2 || state == 6) {
                            gearBlinkBeltFlags |= (1 << 4); // 좌회전
                        }
                        if (state == 4 || state == 6) {
                            gearBlinkBeltFlags |= (1 << 5); // 우회전
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            int lightFlags = 0;
            if (lightDevice != null) {
                try {
                    Object v = methodGetLightStatus.invoke(lightDevice);
                    if (v instanceof Number) {
                        lightFlags = ((Number) v).intValue() & 0xff;
                    }
                } catch (Exception ignored) {
                }
            }

            Listener l = listener;
            if (l != null) {
                l.onTelemetryUpdated(new VehicleTelemetry(
                        speedKmh,
                        acceleratorPercent,
                        brakePercent,
                        gearBlinkBeltFlags,
                        lightFlags));
            }
        } catch (Exception e) {
            Log.w(TAG, "Vehicle telemetry poll failed", e);
        }
    }
}
