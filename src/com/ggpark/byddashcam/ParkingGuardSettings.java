package com.ggpark.byddashcam;

/**
 * 주차 감시 모드 설정 컨테이너 (immutable).
 */
public final class ParkingGuardSettings {
    public static final float DEFAULT_IMPACT_THRESHOLD_G = 2.5f;
    public static final int DEFAULT_RECORDING_SECONDS = 120;
    public static final float MIN_IMPACT_THRESHOLD_G = 1.5f;
    public static final float MAX_IMPACT_THRESHOLD_G = 5.0f;
    public static final int MIN_RECORDING_SECONDS = 30;
    public static final int MAX_RECORDING_SECONDS = 300;
    /**
     * 레이더 트리거 레벨 기본값.
     * 2=GREEN(녹색경고), 3=YELLOW(황색경고), 4=RED(적색경고)
     */
    public static final int DEFAULT_RADAR_TRIGGER_LEVEL = 3; // YELLOW

    public final float impactThresholdG;
    public final int recordingSeconds;
    public final boolean autoLockSegment;
    public final boolean cameraMotionEnabled;
    public final int cameraMotionSensitivity;
    /** 레이더 근접 감지 활성 여부 */
    public final boolean radarEnabled;
    /** 레이더 트리거 레벨: 2=GREEN, 3=YELLOW, 4=RED */
    public final int radarTriggerLevel;

    public ParkingGuardSettings(
            float impactThresholdG,
            int recordingSeconds,
            boolean autoLockSegment,
            boolean cameraMotionEnabled,
            int cameraMotionSensitivity,
            boolean radarEnabled,
            int radarTriggerLevel) {
        this.impactThresholdG = clampFloat(
                impactThresholdG,
                MIN_IMPACT_THRESHOLD_G,
                MAX_IMPACT_THRESHOLD_G);
        this.recordingSeconds = clamp(
                recordingSeconds,
                MIN_RECORDING_SECONDS,
                MAX_RECORDING_SECONDS);
        this.autoLockSegment = autoLockSegment;
        this.cameraMotionEnabled = cameraMotionEnabled;
        this.cameraMotionSensitivity = clamp(cameraMotionSensitivity, 1, 5);
        this.radarEnabled = radarEnabled;
        this.radarTriggerLevel = clamp(radarTriggerLevel, 2, 4);
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
