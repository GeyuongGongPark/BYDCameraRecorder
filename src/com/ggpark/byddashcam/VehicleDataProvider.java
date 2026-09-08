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
    private Method methodGetGearboxAutoModeType;

    private Object lightDevice;
    private Method methodGetTurnLightFlashState;
    private Method methodGetLightStatus; // getLightStatus(int type)

    private ScheduledExecutorService executor;
    private volatile Listener listener;
    private volatile LogBuffer logBuffer;
    private boolean anyDeviceAvailable;

    // 이전 raw 값 (변경 시에만 로그)
    private int prevRawSpeed = Integer.MIN_VALUE;
    private int prevRawGear = Integer.MIN_VALUE;
    private int prevRawAccel = Integer.MIN_VALUE;
    private int prevRawBrake = Integer.MIN_VALUE;
    private int prevRawTurn = Integer.MIN_VALUE;
    private int prevRawLight = Integer.MIN_VALUE;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setLogBuffer(LogBuffer logBuffer) {
        this.logBuffer = logBuffer;
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
            methodGetGearboxAutoModeType = cls.getMethod("getGearboxAutoModeType");
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
            methodGetLightStatus = cls.getMethod("getLightStatus", Integer.TYPE);
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
            int rawGearValue = Integer.MIN_VALUE; // API 원시 반환값 (디버깅용)
            if (gearDevice != null) {
                try {
                    Object v = methodGetGearboxAutoModeType.invoke(gearDevice);
                    if (v instanceof Number) {
                        int g = ((Number) v).intValue();
                        rawGearValue = g;
                        // BYDAutoGearboxDevice.getGearboxAutoModeType() 반환값
                        // — GEARBOX_AUTO_MODE_P/R/N/D 상수 실제 값은 차량 테스트로 확인
                        // — raw 값은 오버레이에 ?:X 로 표시됨
                        // 아래는 공통 AT 순서 추정값 (P=0,R=1,N=2,D=3 또는 P=3,R=2,N=1,D=0)
                        // 실차 확인 후 수정 필요
                        if (g == 0) {
                            gearBlinkBeltFlags |= 0x01; // P 추정
                        } else if (g == 1) {
                            gearBlinkBeltFlags |= 0x02; // R 추정
                        } else if (g == 2) {
                            gearBlinkBeltFlags |= 0x04; // N 추정
                        } else if (g == 3) {
                            gearBlinkBeltFlags |= 0x08; // D 추정
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            if (lightDevice != null) {
                try {
                    // 방향지시등: 0/1=off, 2/3=left, 4/5=right (kinex HalLightListener 기준)
                    Object v = methodGetTurnLightFlashState.invoke(lightDevice);
                    if (v instanceof Number) {
                        int state = ((Number) v).intValue();
                        if (state == 2 || state == 3) {
                            gearBlinkBeltFlags |= (1 << 4); // 좌회전
                        }
                        if (state == 4 || state == 5) {
                            gearBlinkBeltFlags |= (1 << 5); // 우회전
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            int lightFlags = 0;
            if (lightDevice != null) {
                try {
                    // type=2: 하향등(low beam), type=3: 상향등(high beam)
                    Object lowBeam = methodGetLightStatus.invoke(lightDevice, 2);
                    Object highBeam = methodGetLightStatus.invoke(lightDevice, 3);
                    if (lowBeam instanceof Number && ((Number) lowBeam).intValue() != 0) {
                        lightFlags |= 0x02; // bit1=하향등
                    }
                    if (highBeam instanceof Number && ((Number) highBeam).intValue() != 0) {
                        lightFlags |= 0x04; // bit2=상향등
                    }
                } catch (Exception ignored) {
                }
            }

            // raw 값이 바뀔 때만 LogBuffer에 기록
            int rawGear = rawGearValue;
            int rawTurn = lightDevice != null ? (gearBlinkBeltFlags >> 4) : Integer.MIN_VALUE;
            int rawLight = lightDevice != null ? lightFlags : Integer.MIN_VALUE;
            int rawSpeed = speedDevice != null ? speedKmh : Integer.MIN_VALUE;
            int rawAccel = speedDevice != null ? acceleratorPercent : Integer.MIN_VALUE;
            int rawBrake = speedDevice != null ? brakePercent : Integer.MIN_VALUE;

            LogBuffer buf = logBuffer;
            if (buf != null && (rawSpeed != prevRawSpeed || rawGear != prevRawGear
                    || rawAccel != prevRawAccel || rawBrake != prevRawBrake
                    || rawTurn != prevRawTurn || rawLight != prevRawLight)) {
                buf.append("BYDRaw",
                        "speed=" + speedKmh
                        + " gearRaw=" + rawGear
                        + " accel=" + acceleratorPercent
                        + " brake=" + brakePercent
                        + " turn=" + (gearBlinkBeltFlags >> 4)
                        + " light=0x" + Integer.toHexString(lightFlags));
                prevRawSpeed = rawSpeed;
                prevRawGear = rawGear;
                prevRawAccel = rawAccel;
                prevRawBrake = rawBrake;
                prevRawTurn = rawTurn;
                prevRawLight = rawLight;
            }

            Listener l = listener;
            if (l != null) {
                l.onTelemetryUpdated(new VehicleTelemetry(
                        speedKmh,
                        acceleratorPercent,
                        brakePercent,
                        gearBlinkBeltFlags,
                        lightFlags,
                        rawGearValue));
            }
        } catch (Exception e) {
            Log.w(TAG, "Vehicle telemetry poll failed", e);
        }
    }
}
