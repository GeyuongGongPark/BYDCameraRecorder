package com.ggpark.byddashcam;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * BYD 비공개 API에 Java Reflection으로 접근해 차량 텔레메트리를 폴링합니다.
 * Fast poll(100ms): 속도/기어/조명 — 빠르게 변하는 값
 * Slow poll(5s):   배터리/에너지 모드 — 천천히 변하는 값
 * BYD API 미지원 기기에서도 graceful degradation: UNAVAILABLE 텔레메트리를 콜백합니다.
 */
public final class VehicleDataProvider {
    public interface Listener {
        void onTelemetryUpdated(VehicleTelemetry telemetry);
    }

    private static final String TAG = "BYDCamera";
    private static final long FAST_POLL_MS = 100L;
    private static final long SLOW_POLL_MS = 5000L;

    // Fast poll 디바이스
    private Object speedDevice;
    private Method methodGetCurrentSpeed;
    private Method methodGetAccelerateDeepness;
    private Method methodGetBrakeDeepness;

    private Object gearDevice;
    private Method methodGetGearboxAutoModeType;

    private Object lightDevice;
    private Method methodGetLightStatus; // getLightStatus(int type)

    // Slow poll 디바이스
    private Object statisticDevice;
    private Method methodGetElecPercentageValue;
    private Method methodGetElecDrivingRangeValue;

    private Object energyDevice;
    private Method methodGetEnergyMode;
    private Method methodGetOperationMode;

    private Object bodyworkDevice;
    private Method methodGetPowerLevel;

    // Slow poll 결과 (fast poll에서 참조)
    private volatile int slowBatteryPercent = -1;
    private volatile int slowDrivingRangeKm = -1;
    private volatile int slowEnergyMode = -1;
    private volatile int slowOperationMode = -1;
    private volatile int slowPowerLevel = -1;

    private ScheduledExecutorService fastExecutor;
    private ScheduledExecutorService slowExecutor;
    private volatile Listener listener;
    private volatile LogBuffer logBuffer;
    private boolean anyDeviceAvailable;

    // gear 디버그 최초 1회 로그 플래그
    private boolean gearInvokeErrorLogged = false;
    private boolean gearNullLogged = false;

    // 첫 번째 폴에서는 무조건 콜백 발생 (이전 값과 동일해도)
    private boolean isFirstPoll = true;

    // 이전 값 — 변경 시에만 LogBuffer 기록 및 리스너 콜백
    private int prevRawSpeed = Integer.MIN_VALUE;
    private int prevRawGear = Integer.MIN_VALUE;
    private int prevRawAccel = Integer.MIN_VALUE;
    private int prevRawBrake = Integer.MIN_VALUE;
    private int prevRawTurn = Integer.MIN_VALUE;
    private int prevRawLight = Integer.MIN_VALUE;
    private int prevBattery = Integer.MIN_VALUE;
    private int prevDrivingRange = Integer.MIN_VALUE;
    private int prevEnergyMode = Integer.MIN_VALUE;
    private int prevOperationMode = Integer.MIN_VALUE;
    private int prevPowerLevel = Integer.MIN_VALUE;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setLogBuffer(LogBuffer logBuffer) {
        this.logBuffer = logBuffer;
    }

    public void start(Context context) {
        Context vehicleContext = new VehicleContextWrapper(context);
        initDevices(vehicleContext);
        fastExecutor = Executors.newSingleThreadScheduledExecutor();
        fastExecutor.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        pollFast();
                    }
                },
                0L,
                FAST_POLL_MS,
                TimeUnit.MILLISECONDS);
        slowExecutor = Executors.newSingleThreadScheduledExecutor();
        slowExecutor.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        pollSlow();
                    }
                },
                0L,
                SLOW_POLL_MS,
                TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (fastExecutor != null) {
            fastExecutor.shutdownNow();
            fastExecutor = null;
        }
        if (slowExecutor != null) {
            slowExecutor.shutdownNow();
            slowExecutor = null;
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
            methodGetLightStatus = cls.getMethod("getLightStatus", Integer.TYPE);
            anyDeviceAvailable = true;
            Log.i(TAG, "BYD light device initialized");
        } catch (Exception e) {
            Log.w(TAG, "BYD light device unavailable: " + e.getMessage());
        }

        // 주행 통계 디바이스 (배터리 잔량, 주행 가능 거리)
        try {
            Class<?> cls = Class.forName(
                    "android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            statisticDevice = getInstance.invoke(null, context);
            methodGetElecPercentageValue = cls.getMethod("getElecPercentageValue");
            methodGetElecDrivingRangeValue = cls.getMethod("getElecDrivingRangeValue");
            anyDeviceAvailable = true;
            Log.i(TAG, "BYD statistic device initialized");
        } catch (Exception e) {
            Log.w(TAG, "BYD statistic device unavailable: " + e.getMessage());
        }

        // 에너지 모드 디바이스
        try {
            Class<?> cls = Class.forName(
                    "android.hardware.bydauto.energy.BYDAutoEnergyDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            energyDevice = getInstance.invoke(null, context);
            methodGetEnergyMode = cls.getMethod("getEnergyMode");
            methodGetOperationMode = cls.getMethod("getOperationMode");
            anyDeviceAvailable = true;
            Log.i(TAG, "BYD energy device initialized");
        } catch (Exception e) {
            Log.w(TAG, "BYD energy device unavailable: " + e.getMessage());
        }

        // 차체(Bodywork) 디바이스 — 전원 단계(시동 상태) 조회용
        try {
            Class<?> cls = Class.forName(
                    "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            bodyworkDevice = getInstance.invoke(null, context);
            methodGetPowerLevel = cls.getMethod("getPowerLevel");
            anyDeviceAvailable = true;
            Log.i(TAG, "BYD bodywork device initialized");
        } catch (Exception e) {
            Log.w(TAG, "BYD bodywork device unavailable: " + e.getMessage());
        }
    }

    private void pollSlow() {
        try {
            if (statisticDevice != null) {
                try {
                    Object pct = methodGetElecPercentageValue.invoke(statisticDevice);
                    if (pct instanceof Number) {
                        slowBatteryPercent = Math.max(0, Math.min(100,
                                (int) ((Number) pct).doubleValue()));
                    }
                } catch (Exception ignored) {
                }
                try {
                    Object range = methodGetElecDrivingRangeValue.invoke(statisticDevice);
                    if (range instanceof Number) {
                        slowDrivingRangeKm = Math.max(0, ((Number) range).intValue());
                    }
                } catch (Exception ignored) {
                }
            }
            if (energyDevice != null) {
                try {
                    Object mode = methodGetEnergyMode.invoke(energyDevice);
                    if (mode instanceof Number) {
                        slowEnergyMode = ((Number) mode).intValue();
                    }
                } catch (Exception ignored) {
                }
                try {
                    Object op = methodGetOperationMode.invoke(energyDevice);
                    if (op instanceof Number) {
                        slowOperationMode = ((Number) op).intValue();
                    }
                } catch (Exception ignored) {
                }
            }
            if (bodyworkDevice != null) {
                try {
                    Object pl = methodGetPowerLevel.invoke(bodyworkDevice);
                    if (pl instanceof Number) {
                        slowPowerLevel = ((Number) pl).intValue();
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Vehicle slow poll failed", e);
        }
    }

    private void pollFast() {
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
            if (gearDevice == null) {
                if (!gearNullLogged) {
                    gearNullLogged = true;
                    LogBuffer buf = logBuffer;
                    if (buf != null) {
                        buf.append("BYDGearErr", "gearDevice is null");
                    }
                }
            } else if (gearDevice != null) {
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
                        // GEARBOX_AUTO_MODE_P=1, R=2, N=3, D=4, M=5, S=6
                        if (g == 1) {
                            gearBlinkBeltFlags |= 0x01; // P
                        } else if (g == 2) {
                            gearBlinkBeltFlags |= 0x02; // R
                        } else if (g == 3) {
                            gearBlinkBeltFlags |= 0x04; // N
                        } else if (g == 4 || g == 5 || g == 6) {
                            gearBlinkBeltFlags |= 0x08; // D (M/S 포함)
                        }
                    }
                } catch (Exception e) {
                    if (!gearInvokeErrorLogged) {
                        gearInvokeErrorLogged = true;
                        LogBuffer buf = logBuffer;
                        if (buf != null) {
                            buf.append("BYDGearErr", e.getClass().getSimpleName()
                                    + ": " + e.getMessage());
                        }
                        Log.w(TAG, "Gear invoke failed", e);
                    }
                }
            }

            int lightFlags = 0;
            if (lightDevice != null) {
                try {
                    // LIGHT_SIDE=1, LIGHT_LOW_BEAM=2, LIGHT_HIGH_BEAM=3
                    // LIGHT_LEFT_TURN_SIGNAL=4, LIGHT_RIGHT_TURN_SIGNAL=5, LIGHT_FRONT_FOG=6
                    Object sideLight  = methodGetLightStatus.invoke(lightDevice, 1);
                    Object lowBeam    = methodGetLightStatus.invoke(lightDevice, 2);
                    Object highBeam   = methodGetLightStatus.invoke(lightDevice, 3);
                    Object leftTurn   = methodGetLightStatus.invoke(lightDevice, 4);
                    Object rightTurn  = methodGetLightStatus.invoke(lightDevice, 5);
                    Object frontFog   = methodGetLightStatus.invoke(lightDevice, 6);
                    if (sideLight instanceof Number && ((Number) sideLight).intValue() != 0) {
                        lightFlags |= 0x01; // bit0=위치등
                    }
                    if (lowBeam instanceof Number && ((Number) lowBeam).intValue() != 0) {
                        lightFlags |= 0x02; // bit1=하향등
                    }
                    if (highBeam instanceof Number && ((Number) highBeam).intValue() != 0) {
                        lightFlags |= 0x04; // bit2=상향등
                    }
                    if (frontFog instanceof Number && ((Number) frontFog).intValue() != 0) {
                        lightFlags |= 0x08; // bit3=안개등
                    }
                    if (leftTurn instanceof Number && ((Number) leftTurn).intValue() != 0) {
                        gearBlinkBeltFlags |= (1 << 4); // 좌회전
                    }
                    if (rightTurn instanceof Number && ((Number) rightTurn).intValue() != 0) {
                        gearBlinkBeltFlags |= (1 << 5); // 우회전
                    }
                } catch (Exception ignored) {
                }
            }

            // 변경 감지
            int rawGear = rawGearValue;
            int rawTurn = lightDevice != null ? (gearBlinkBeltFlags >> 4) : Integer.MIN_VALUE;
            int rawLight = lightDevice != null ? lightFlags : Integer.MIN_VALUE;
            int rawSpeed = speedDevice != null ? speedKmh : Integer.MIN_VALUE;
            int rawAccel = speedDevice != null ? acceleratorPercent : Integer.MIN_VALUE;
            int rawBrake = speedDevice != null ? brakePercent : Integer.MIN_VALUE;

            boolean fastChanged = rawSpeed != prevRawSpeed || rawGear != prevRawGear
                    || rawAccel != prevRawAccel || rawBrake != prevRawBrake
                    || rawTurn != prevRawTurn || rawLight != prevRawLight;
            boolean slowChanged = slowBatteryPercent != prevBattery
                    || slowDrivingRangeKm != prevDrivingRange
                    || slowEnergyMode != prevEnergyMode
                    || slowOperationMode != prevOperationMode
                    || slowPowerLevel != prevPowerLevel;

            if (fastChanged) {
                LogBuffer buf = logBuffer;
                if (buf != null) {
                    buf.append("BYDRaw",
                            "speed=" + speedKmh
                            + " gearRaw=" + rawGear
                            + " accel=" + acceleratorPercent
                            + " brake=" + brakePercent
                            + " turn=" + (gearBlinkBeltFlags >> 4)
                            + " light=0x" + Integer.toHexString(lightFlags));
                }
                prevRawSpeed = rawSpeed;
                prevRawGear = rawGear;
                prevRawAccel = rawAccel;
                prevRawBrake = rawBrake;
                prevRawTurn = rawTurn;
                prevRawLight = rawLight;
            }

            if (slowChanged) {
                prevBattery = slowBatteryPercent;
                prevDrivingRange = slowDrivingRangeKm;
                prevEnergyMode = slowEnergyMode;
                prevOperationMode = slowOperationMode;
                prevPowerLevel = slowPowerLevel;
            }

            Listener l = listener;
            if (l != null && (isFirstPoll || fastChanged || slowChanged)) {
                isFirstPoll = false;
                l.onTelemetryUpdated(new VehicleTelemetry(
                        speedKmh,
                        acceleratorPercent,
                        brakePercent,
                        gearBlinkBeltFlags,
                        lightFlags,
                        rawGearValue,
                        slowBatteryPercent,
                        slowDrivingRangeKm,
                        slowEnergyMode,
                        slowOperationMode,
                        slowPowerLevel));
            }
        } catch (Exception e) {
            Log.w(TAG, "Vehicle telemetry poll failed", e);
        }
    }
}
