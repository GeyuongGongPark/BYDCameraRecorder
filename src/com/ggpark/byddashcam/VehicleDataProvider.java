package com.ggpark.byddashcam;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import dalvik.system.InMemoryDexClassLoader;

/**
 * BYD 비공개 API에 Java Reflection으로 접근해 차량 텔레메트리를 폴링합니다.
 * Fast poll(100ms): 속도/기어/조명 — 빠르게 변하는 값
 * Slow poll(5s):   배터리/에너지 모드 — 천천히 변하는 값
 *
 * BeetleLauncher 분석으로 확인한 방식:
 *   com.byd.data.collect 앱의 DEX를 InMemoryDexClassLoader로 메모리 로드 후
 *   해당 ClassLoader로 BYD 클래스에 접근 — Permission 문제 우회.
 */
public final class VehicleDataProvider {
    public interface Listener {
        void onTelemetryUpdated(VehicleTelemetry telemetry);
    }

    private static final String TAG = "BYDCamera";
    private static final String OEM_PKG = "com.byd.data.collect";
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
    private Method methodGetTurnLightFlashState; // 주 방향지시등 소스 (BeetleLauncher 확인)
    private Method methodGetLightStatus;         // getLightStatus(int type) — 조명/방향지시등 fallback

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

    // 첫 번째 폴에서는 무조건 콜백 발생
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
        initDevices(context);
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

    // -----------------------------------------------------------------------
    // DEX ClassLoader — com.byd.data.collect APK에서 메모리 로드
    // -----------------------------------------------------------------------

    private ClassLoader bydClassLoader = null;

    private void initBydClassLoader(Context context) {
        // 1. 시스템 클래스패스에 BYD 클래스가 있는지 먼저 확인
        try {
            Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
            bydClassLoader = context.getClassLoader();
            Log.i(TAG, "BYD SDK: 시스템 클래스패스에서 발견");
            return;
        } catch (ClassNotFoundException ignored) {
        }

        // 2. com.byd.data.collect APK DEX를 InMemoryDexClassLoader로 로드
        try {
            ApplicationInfo ai = context.getPackageManager()
                    .getApplicationInfo(OEM_PKG, 0);
            List<String> apkPaths = new ArrayList<>();
            if (ai.sourceDir != null) apkPaths.add(ai.sourceDir);
            if (ai.splitSourceDirs != null) {
                for (String s : ai.splitSourceDirs) apkPaths.add(s);
            }
            if (apkPaths.isEmpty()) {
                Log.w(TAG, "BYD SDK: " + OEM_PKG + " APK 경로 없음");
                return;
            }

            List<ByteBuffer> dexBuffers = new ArrayList<>();
            for (String apkPath : apkPaths) {
                extractDexBuffers(apkPath, dexBuffers);
            }
            if (dexBuffers.isEmpty()) {
                Log.w(TAG, "BYD SDK: DEX 없음 — " + apkPaths);
                return;
            }

            ByteBuffer[] arr = dexBuffers.toArray(new ByteBuffer[0]);
            InMemoryDexClassLoader loader =
                    new InMemoryDexClassLoader(arr, context.getClassLoader());
            // 프로브 클래스 로드 확인
            Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice", false, loader);
            bydClassLoader = loader;
            Log.i(TAG, "BYD SDK: " + OEM_PKG + " DEX 로드 완료 (" + dexBuffers.size() + " dex)");
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "BYD SDK: " + OEM_PKG + " 미설치 (에뮬레이터?)");
        } catch (Exception e) {
            Log.w(TAG, "BYD SDK: DEX 로드 실패 — " + e.getMessage());
        }
    }

    private static void extractDexBuffers(String apkPath, List<ByteBuffer> out) {
        try {
            ZipFile zip = new ZipFile(apkPath);
            try {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.matches("classes\\d*\\.dex")) {
                        InputStream is = zip.getInputStream(entry);
                        out.add(ByteBuffer.wrap(readAllBytes(is)));
                        is.close();
                    }
                }
            } finally {
                zip.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "BYD SDK: DEX 추출 실패 " + apkPath + " — " + e.getMessage());
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private Class<?> loadBydClass(String fqn) throws ClassNotFoundException {
        if (bydClassLoader != null) {
            return Class.forName(fqn, true, bydClassLoader);
        }
        return Class.forName(fqn);
    }

    /**
     * getInstance(Context) 또는 getsInstance(Context) 중 하나를 호출합니다.
     * BeetleLauncher는 두 팩토리 메서드를 모두 fallback으로 시도합니다.
     */
    private Object getDeviceInstance(Class<?> cls, Context context) throws Exception {
        Method factory;
        try {
            factory = cls.getMethod("getInstance", Context.class);
        } catch (NoSuchMethodException e) {
            factory = cls.getMethod("getsInstance", Context.class);
        }
        return factory.invoke(null, context);
    }

    // -----------------------------------------------------------------------
    // 디바이스 초기화
    // -----------------------------------------------------------------------

    private void initDevices(Context context) {
        initBydClassLoader(context);

        // 속도/가속/브레이크 디바이스
        try {
            Class<?> cls = loadBydClass("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
            speedDevice = getDeviceInstance(cls, context);
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
            Class<?> cls = loadBydClass("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            gearDevice = getDeviceInstance(cls, context);
            methodGetGearboxAutoModeType = cls.getMethod("getGearboxAutoModeType");
            anyDeviceAvailable = true;
            Log.i(TAG, "BYD gear device initialized");
        } catch (Exception e) {
            Log.w(TAG, "BYD gear device unavailable: " + e.getMessage());
        }

        // 조명 디바이스
        try {
            Class<?> cls = loadBydClass("android.hardware.bydauto.light.BYDAutoLightDevice");
            lightDevice = getDeviceInstance(cls, context);
            try {
                methodGetTurnLightFlashState = cls.getMethod("getTurnLightFlashState");
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "getTurnLightFlashState 없음 — getLightStatus fallback 사용");
            }
            try {
                methodGetLightStatus = cls.getMethod("getLightStatus", Integer.TYPE);
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "getLightStatus(int) 없음");
            }
            anyDeviceAvailable = true;
            Log.i(TAG, "BYD light device initialized");
        } catch (Exception e) {
            Log.w(TAG, "BYD light device unavailable: " + e.getMessage());
        }

        // 주행 통계 디바이스 (배터리 잔량, 주행 가능 거리)
        try {
            Class<?> cls = loadBydClass(
                    "android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
            statisticDevice = getDeviceInstance(cls, context);
            methodGetElecPercentageValue = cls.getMethod("getElecPercentageValue");
            methodGetElecDrivingRangeValue = cls.getMethod("getElecDrivingRangeValue");
            anyDeviceAvailable = true;
            Log.i(TAG, "BYD statistic device initialized");
        } catch (Exception e) {
            Log.w(TAG, "BYD statistic device unavailable: " + e.getMessage());
        }

        // 에너지 모드 디바이스
        try {
            Class<?> cls = loadBydClass("android.hardware.bydauto.energy.BYDAutoEnergyDevice");
            energyDevice = getDeviceInstance(cls, context);
            methodGetEnergyMode = cls.getMethod("getEnergyMode");
            methodGetOperationMode = cls.getMethod("getOperationMode");
            anyDeviceAvailable = true;
            Log.i(TAG, "BYD energy device initialized");
        } catch (Exception e) {
            Log.w(TAG, "BYD energy device unavailable: " + e.getMessage());
        }

        // 차체(Bodywork) 디바이스 — 전원 단계(시동 상태) 조회용
        try {
            Class<?> cls = loadBydClass(
                    "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            bodyworkDevice = getDeviceInstance(cls, context);
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
            int rawGearValue = Integer.MIN_VALUE;
            if (gearDevice == null) {
                if (!gearNullLogged) {
                    gearNullLogged = true;
                    LogBuffer buf = logBuffer;
                    if (buf != null) buf.append("BYDGearErr", "gearDevice is null");
                }
            } else {
                try {
                    Object v = methodGetGearboxAutoModeType.invoke(gearDevice);
                    if (v instanceof Number) {
                        int g = ((Number) v).intValue();
                        rawGearValue = g;
                        // API 문서 기준 기어 매핑:
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
                // 방향지시등: getTurnLightFlashState 우선, 없으면 getLightStatus fallback
                if (methodGetTurnLightFlashState != null) {
                    try {
                        Object v = methodGetTurnLightFlashState.invoke(lightDevice);
                        if (v instanceof Number) {
                            int state = ((Number) v).intValue();
                            // BeetleLauncher 확인: 2/3=left, 4/5=right
                            if (state == 2 || state == 3) {
                                gearBlinkBeltFlags |= (1 << 4); // 좌회전
                            } else if (state == 4 || state == 5) {
                                gearBlinkBeltFlags |= (1 << 5); // 우회전
                            }
                        }
                    } catch (Exception ignored) {
                    }
                } else if (methodGetLightStatus != null) {
                    try {
                        Object leftTurn  = methodGetLightStatus.invoke(lightDevice, 4);
                        Object rightTurn = methodGetLightStatus.invoke(lightDevice, 5);
                        if (leftTurn instanceof Number && ((Number) leftTurn).intValue() != 0)
                            gearBlinkBeltFlags |= (1 << 4);
                        if (rightTurn instanceof Number && ((Number) rightTurn).intValue() != 0)
                            gearBlinkBeltFlags |= (1 << 5);
                    } catch (Exception ignored) {
                    }
                }

                // 차량 조명 상태 (위치등/하향등/상향등/안개등)
                if (methodGetLightStatus != null) {
                    try {
                        Object sideLight = methodGetLightStatus.invoke(lightDevice, 1);
                        Object lowBeam   = methodGetLightStatus.invoke(lightDevice, 2);
                        Object highBeam  = methodGetLightStatus.invoke(lightDevice, 3);
                        Object frontFog  = methodGetLightStatus.invoke(lightDevice, 6);
                        if (sideLight instanceof Number && ((Number) sideLight).intValue() != 0)
                            lightFlags |= 0x01;
                        if (lowBeam instanceof Number && ((Number) lowBeam).intValue() != 0)
                            lightFlags |= 0x02;
                        if (highBeam instanceof Number && ((Number) highBeam).intValue() != 0)
                            lightFlags |= 0x04;
                        if (frontFog instanceof Number && ((Number) frontFog).intValue() != 0)
                            lightFlags |= 0x08;
                    } catch (Exception ignored) {
                    }
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
