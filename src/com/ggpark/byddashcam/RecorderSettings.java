package com.ggpark.byddashcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;

import java.io.File;

public final class RecorderSettings {
    private static final String PREFS = "recorder_settings";
    private static final String KEY_LEGACY_CAMERA_CROP_PERCENT_PREFIX =
            "camera_crop_percent_";
    private static final String KEY_FISHEYE_CROP_PERCENT =
            "fisheye_crop_percent";
    private static final String KEY_CAMERA_FLIP_HORIZONTAL_PREFIX =
            "camera_flip_horizontal_";
    private static final String KEY_CAMERA_FLIP_VERTICAL_PREFIX =
            "camera_flip_vertical_";
    private static final String KEY_CAMERA_NAME_PREFIX = "camera_name_";
    private static final String KEY_COMBINED_POSITION_PREFIX =
            "combined_position_";
    private static final String KEY_CONTINUOUS_RECORDING_ENABLED =
            "continuous_recording_enabled";
    private static final String KEY_DATE_FORMAT = "date_format";
    private static final String KEY_MIN_FREE_PERCENT = "min_free_percent";
    private static final String KEY_PHONE_ACCESS_CODE = "phone_access_code";
    private static final String KEY_PHONE_ACCESS_ENABLED = "phone_access_enabled";
    private static final String KEY_PHONE_ACCESS_PIN = "phone_access_pin";
    private static final String KEY_QUOTA_BYTES = "quota_bytes";
    private static final String KEY_QUOTA_CONFIGURED = "quota_configured";
    private static final String KEY_RESOLUTION = "resolution";
    private static final String KEY_RETENTION_DAYS = "retention_days";
    private static final String KEY_SEGMENT_MINUTES = "segment_minutes";
    private static final String KEY_VEHICLE_MODEL_ID = "vehicle_model_id";
    private static final String KEY_VOLUME_INDEX = "volume_index";
    private static final String KEY_GPS_OVERLAY_ENABLED = "gps_overlay_enabled";
    private static final String KEY_GPS_SPEED_UNIT = "gps_speed_unit";
    private static final String KEY_GPS_SHOW_COORDINATES = "gps_show_coordinates";
    private static final String KEY_GPS_TRACK_ENABLED = "gps_track_enabled";
    private static final String KEY_PARKING_IMPACT_THRESHOLD_G = "parking_impact_threshold_g";
    private static final String KEY_PARKING_RECORDING_SECONDS = "parking_recording_seconds";
    private static final String KEY_PARKING_AUTO_LOCK = "parking_auto_lock";
    private static final String KEY_TELEGRAM_ENABLED = "telegram_enabled";
    private static final String KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token";
    private static final String KEY_TELEGRAM_CHAT_ID = "telegram_chat_id";
    private static final String KEY_MQTT_ENABLED = "mqtt_enabled";
    private static final String KEY_MQTT_HOST = "mqtt_host";
    private static final String KEY_MQTT_PORT = "mqtt_port";
    private static final String KEY_MQTT_USERNAME = "mqtt_username";
    private static final String KEY_MQTT_PASSWORD = "mqtt_password";
    private static final String KEY_MQTT_TOPIC_PREFIX = "mqtt_topic_prefix";
    private static final String KEY_CLOUDFLARE_ENABLED = "cloudflare_enabled";
    private static final String KEY_CAMERA_MOTION_ENABLED = "camera_motion_enabled";
    private static final String KEY_CAMERA_MOTION_SENSITIVITY = "camera_motion_sensitivity";
    private static final String KEY_TELEMETRY_ENABLED = "telemetry_enabled";

    public static final int DEFAULT_FISHEYE_CROP_PERCENT = 15;
    public static final int DEFAULT_MIN_FREE_PERCENT = 5;
    public static final long DEFAULT_QUOTA_BYTES = 20L * 1024L * 1024L * 1024L;
    public static final int DEFAULT_RETENTION_DAYS = 21;
    public static final int DEFAULT_SEGMENT_MINUTES = 3;
    public static final int MAX_CAMERA_CROP_PERCENT = 35;
    public static final int MAX_MIN_FREE_PERCENT = 25;
    public static final long MAX_QUOTA_BYTES =
            1000L * 1024L * 1024L * 1024L;
    public static final int MAX_RETENTION_DAYS = 3650;
    public static final int MAX_SEGMENT_MINUTES = 10;
    public static final int MIN_MIN_FREE_PERCENT = 1;
    public static final long MIN_QUOTA_BYTES =
            256L * 1024L * 1024L;
    public static final int MIN_RETENTION_DAYS = 1;
    public static final int MIN_SEGMENT_MINUTES = 1;
    private final int fisheyeCropPercent;
    private static final int MAX_CAMERA_NAME_LENGTH = 32;

    private final boolean[] cameraFlipHorizontal;
    private final boolean[] cameraFlipVertical;
    private final String[] cameraNames;
    private final int[] combinedLayout;
    public final boolean continuousRecordingEnabled;
    public final DisplayDateFormat dateFormat;
    public final int minFreePercent;
    public final String phoneAccessCode;
    public final boolean phoneAccessEnabled;
    public final String phoneAccessPin;
    public final long quotaBytes;
    public final boolean quotaConfigured;
    public final VideoResolution resolution;
    public final int retentionDays;
    public final int segmentMinutes;
    public final String vehicleModelId;
    public final int volumeIndex;
    public final boolean gpsOverlayEnabled;
    public final String gpsSpeedUnit;
    public final boolean gpsShowCoordinates;
    public final boolean gpsTrackEnabled;
    public final float parkingImpactThresholdG;
    public final int parkingRecordingSeconds;
    public final boolean parkingAutoLock;
    public final boolean telegramEnabled;
    public final String telegramBotToken;
    public final String telegramChatId;
    public final boolean mqttEnabled;
    public final String mqttHost;
    public final int mqttPort;
    public final String mqttUsername;
    public final String mqttPassword;
    public final String mqttTopicPrefix;
    public final boolean cloudflareEnabled;
    public final boolean cameraMotionEnabled;
    public final int cameraMotionSensitivity;
    public final boolean telemetryEnabled;

    public RecorderSettings(
            int volumeIndex,
            long quotaBytes,
            boolean quotaConfigured,
            int retentionDays,
            int segmentMinutes,
            int minFreePercent,
            VideoResolution resolution,
            boolean continuousRecordingEnabled,
            boolean phoneAccessEnabled,
            String phoneAccessCode,
            String phoneAccessPin,
            DisplayDateFormat dateFormat,
            String[] cameraNames,
            int[] combinedLayout,
            boolean[] cameraFlipHorizontal,
            boolean[] cameraFlipVertical,
            int fisheyeCropPercent,
            String vehicleModelId,
            boolean gpsOverlayEnabled,
            String gpsSpeedUnit,
            boolean gpsShowCoordinates,
            boolean gpsTrackEnabled,
            float parkingImpactThresholdG,
            int parkingRecordingSeconds,
            boolean parkingAutoLock,
            boolean telegramEnabled,
            String telegramBotToken,
            String telegramChatId,
            boolean mqttEnabled,
            String mqttHost,
            int mqttPort,
            String mqttUsername,
            String mqttPassword,
            String mqttTopicPrefix,
            boolean cloudflareEnabled,
            boolean cameraMotionEnabled,
            int cameraMotionSensitivity,
            boolean telemetryEnabled) {
        this.volumeIndex = Math.max(0, volumeIndex);
        this.quotaBytes = clampLong(
                quotaBytes,
                MIN_QUOTA_BYTES,
                MAX_QUOTA_BYTES);
        this.quotaConfigured = quotaConfigured;
        this.retentionDays = clamp(
                retentionDays,
                MIN_RETENTION_DAYS,
                MAX_RETENTION_DAYS);
        this.segmentMinutes = clamp(
                segmentMinutes,
                MIN_SEGMENT_MINUTES,
                MAX_SEGMENT_MINUTES);
        this.minFreePercent = clamp(
                minFreePercent,
                MIN_MIN_FREE_PERCENT,
                MAX_MIN_FREE_PERCENT);
        // Resolution selection is temporarily disabled: every recording uses
        // the native maximum profile. The lower profiles and both settings
        // controls are kept in the code so the choice can be re-enabled later.
        this.resolution = VideoResolution.NATIVE;
        this.continuousRecordingEnabled = continuousRecordingEnabled;
        this.phoneAccessEnabled = phoneAccessEnabled;
        this.phoneAccessCode = phoneAccessCode == null ? "" : phoneAccessCode;
        this.phoneAccessPin = phoneAccessPin == null ? "" : phoneAccessPin;
        this.dateFormat =
                dateFormat == null ? DisplayDateFormat.LOCAL_SHORT : dateFormat;
        this.cameraNames = normalizeCameraNames(cameraNames);
        this.combinedLayout = normalizeCombinedLayout(combinedLayout);
        this.cameraFlipHorizontal =
                normalizeCameraFlips(cameraFlipHorizontal);
        this.cameraFlipVertical =
                normalizeCameraFlips(cameraFlipVertical);
        this.fisheyeCropPercent =
                clamp(
                        fisheyeCropPercent,
                        0,
                        MAX_CAMERA_CROP_PERCENT);
        this.vehicleModelId = vehicleModelId == null ? "" : vehicleModelId;
        this.gpsOverlayEnabled = gpsOverlayEnabled;
        this.gpsSpeedUnit = gpsSpeedUnit == null ? "kmh" : gpsSpeedUnit;
        this.gpsShowCoordinates = gpsShowCoordinates;
        this.gpsTrackEnabled = gpsTrackEnabled;
        this.parkingImpactThresholdG = clampFloat(
                parkingImpactThresholdG,
                ParkingGuardSettings.MIN_IMPACT_THRESHOLD_G,
                ParkingGuardSettings.MAX_IMPACT_THRESHOLD_G);
        this.parkingRecordingSeconds = clamp(
                parkingRecordingSeconds,
                ParkingGuardSettings.MIN_RECORDING_SECONDS,
                ParkingGuardSettings.MAX_RECORDING_SECONDS);
        this.parkingAutoLock = parkingAutoLock;
        this.telegramEnabled = telegramEnabled;
        this.telegramBotToken = telegramBotToken == null ? "" : telegramBotToken;
        this.telegramChatId = telegramChatId == null ? "" : telegramChatId;
        this.mqttEnabled = mqttEnabled;
        this.mqttHost = mqttHost == null ? "" : mqttHost;
        this.mqttPort = clamp(mqttPort, 1, 65535);
        this.mqttUsername = mqttUsername == null ? "" : mqttUsername;
        this.mqttPassword = mqttPassword == null ? "" : mqttPassword;
        this.mqttTopicPrefix = mqttTopicPrefix == null ? "byd" : mqttTopicPrefix;
        this.cloudflareEnabled = cloudflareEnabled;
        this.cameraMotionEnabled = cameraMotionEnabled;
        this.cameraMotionSensitivity = clamp(cameraMotionSensitivity, 1, 5);
        this.telemetryEnabled = telemetryEnabled;
    }

    public static RecorderSettings load(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean quotaConfigured =
                preferences.getBoolean(
                        KEY_QUOTA_CONFIGURED,
                        preferences.contains(KEY_QUOTA_BYTES));
        return new RecorderSettings(
                preferences.getInt(KEY_VOLUME_INDEX, 0),
                quotaConfigured
                        ? preferences.getLong(
                                KEY_QUOTA_BYTES,
                                DEFAULT_QUOTA_BYTES)
                        : calculateDefaultQuotaBytes(context),
                quotaConfigured,
                preferences.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS),
                preferences.getInt(KEY_SEGMENT_MINUTES, DEFAULT_SEGMENT_MINUTES),
                preferences.getInt(KEY_MIN_FREE_PERCENT, DEFAULT_MIN_FREE_PERCENT),
                VideoResolution.fromId(
                        preferences.getString(
                                KEY_RESOLUTION,
                                VideoResolution.DEFAULT.id)),
                preferences.getBoolean(KEY_CONTINUOUS_RECORDING_ENABLED, false),
                preferences.getBoolean(KEY_PHONE_ACCESS_ENABLED, true),
                preferences.getString(KEY_PHONE_ACCESS_CODE, ""),
                preferences.getString(KEY_PHONE_ACCESS_PIN, ""),
                DisplayDateFormat.fromId(
                        preferences.getString(
                                KEY_DATE_FORMAT,
                                DisplayDateFormat.LOCAL_SHORT.id)),
                loadCameraNames(preferences),
                loadCombinedLayout(preferences),
                loadCameraFlips(preferences, KEY_CAMERA_FLIP_HORIZONTAL_PREFIX),
                loadCameraFlips(preferences, KEY_CAMERA_FLIP_VERTICAL_PREFIX),
                loadFisheyeCropPercent(preferences),
                preferences.getString(KEY_VEHICLE_MODEL_ID, ""),
                preferences.getBoolean(KEY_GPS_OVERLAY_ENABLED, true),
                preferences.getString(KEY_GPS_SPEED_UNIT, "kmh"),
                preferences.getBoolean(KEY_GPS_SHOW_COORDINATES, false),
                preferences.getBoolean(KEY_GPS_TRACK_ENABLED, true),
                preferences.getFloat(
                        KEY_PARKING_IMPACT_THRESHOLD_G,
                        ParkingGuardSettings.DEFAULT_IMPACT_THRESHOLD_G),
                preferences.getInt(
                        KEY_PARKING_RECORDING_SECONDS,
                        ParkingGuardSettings.DEFAULT_RECORDING_SECONDS),
                preferences.getBoolean(KEY_PARKING_AUTO_LOCK, true),
                preferences.getBoolean(KEY_TELEGRAM_ENABLED, false),
                preferences.getString(KEY_TELEGRAM_BOT_TOKEN, ""),
                preferences.getString(KEY_TELEGRAM_CHAT_ID, ""),
                preferences.getBoolean(KEY_MQTT_ENABLED, false),
                preferences.getString(KEY_MQTT_HOST, ""),
                preferences.getInt(KEY_MQTT_PORT, 1883),
                preferences.getString(KEY_MQTT_USERNAME, ""),
                preferences.getString(KEY_MQTT_PASSWORD, ""),
                preferences.getString(KEY_MQTT_TOPIC_PREFIX, "byd"),
                preferences.getBoolean(KEY_CLOUDFLARE_ENABLED, false),
                preferences.getBoolean(KEY_CAMERA_MOTION_ENABLED, false),
                preferences.getInt(KEY_CAMERA_MOTION_SENSITIVITY, 3),
                preferences.getBoolean(KEY_TELEMETRY_ENABLED, true));
    }

    public void save(Context context) {
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_VOLUME_INDEX, volumeIndex)
                .putLong(KEY_QUOTA_BYTES, quotaBytes)
                .putBoolean(KEY_QUOTA_CONFIGURED, quotaConfigured)
                .putInt(KEY_RETENTION_DAYS, retentionDays)
                .putInt(KEY_SEGMENT_MINUTES, segmentMinutes)
                .putInt(KEY_MIN_FREE_PERCENT, minFreePercent)
                .putString(KEY_RESOLUTION, resolution.id)
                .putBoolean(
                        KEY_CONTINUOUS_RECORDING_ENABLED,
                        continuousRecordingEnabled)
                .putBoolean(KEY_PHONE_ACCESS_ENABLED, phoneAccessEnabled)
                .putString(KEY_PHONE_ACCESS_CODE, phoneAccessCode)
                .putString(KEY_PHONE_ACCESS_PIN, phoneAccessPin)
                .putString(KEY_DATE_FORMAT, dateFormat.id)
                .putInt(
                        KEY_FISHEYE_CROP_PERCENT,
                        fisheyeCropPercent)
                .putString(KEY_VEHICLE_MODEL_ID, vehicleModelId)
                .putBoolean(KEY_GPS_OVERLAY_ENABLED, gpsOverlayEnabled)
                .putString(KEY_GPS_SPEED_UNIT, gpsSpeedUnit)
                .putBoolean(KEY_GPS_SHOW_COORDINATES, gpsShowCoordinates)
                .putBoolean(KEY_GPS_TRACK_ENABLED, gpsTrackEnabled)
                .putFloat(KEY_PARKING_IMPACT_THRESHOLD_G, parkingImpactThresholdG)
                .putInt(KEY_PARKING_RECORDING_SECONDS, parkingRecordingSeconds)
                .putBoolean(KEY_PARKING_AUTO_LOCK, parkingAutoLock)
                .putBoolean(KEY_TELEGRAM_ENABLED, telegramEnabled)
                .putString(KEY_TELEGRAM_BOT_TOKEN, telegramBotToken)
                .putString(KEY_TELEGRAM_CHAT_ID, telegramChatId)
                .putBoolean(KEY_MQTT_ENABLED, mqttEnabled)
                .putString(KEY_MQTT_HOST, mqttHost)
                .putInt(KEY_MQTT_PORT, mqttPort)
                .putString(KEY_MQTT_USERNAME, mqttUsername)
                .putString(KEY_MQTT_PASSWORD, mqttPassword)
                .putString(KEY_MQTT_TOPIC_PREFIX, mqttTopicPrefix)
                .putBoolean(KEY_CLOUDFLARE_ENABLED, cloudflareEnabled)
                .putBoolean(KEY_CAMERA_MOTION_ENABLED, cameraMotionEnabled)
                .putInt(KEY_CAMERA_MOTION_SENSITIVITY, cameraMotionSensitivity)
                .putBoolean(KEY_TELEMETRY_ENABLED, telemetryEnabled);
        for (int index = 0; index < cameraNames.length; index++) {
            editor.putString(KEY_CAMERA_NAME_PREFIX + index, cameraNames[index]);
            editor.putInt(
                    KEY_COMBINED_POSITION_PREFIX + index,
                    combinedLayout[index]);
            editor.putBoolean(
                    KEY_CAMERA_FLIP_HORIZONTAL_PREFIX + index,
                    cameraFlipHorizontal[index]);
            editor.putBoolean(
                    KEY_CAMERA_FLIP_VERTICAL_PREFIX + index,
                    cameraFlipVertical[index]);
        }
        editor.commit();
    }

    public String cameraName(int cameraIndex) {
        if (cameraIndex < 0 || cameraIndex >= cameraNames.length) {
            return defaultCameraName(cameraIndex);
        }
        return cameraNames[cameraIndex];
    }

    public String[] cameraNames() {
        return cameraNames.clone();
    }

    public int fisheyeCropPercent() {
        return fisheyeCropPercent;
    }

    public boolean cameraFlipHorizontal(int cameraIndex) {
        return cameraIndex >= 0
                && cameraIndex < cameraFlipHorizontal.length
                && cameraFlipHorizontal[cameraIndex];
    }

    public boolean[] cameraFlipHorizontal() {
        return cameraFlipHorizontal.clone();
    }

    public boolean cameraFlipVertical(int cameraIndex) {
        return cameraIndex >= 0
                && cameraIndex < cameraFlipVertical.length
                && cameraFlipVertical[cameraIndex];
    }

    public boolean[] cameraFlipVertical() {
        return cameraFlipVertical.clone();
    }

    public int combinedCameraIndex(int position) {
        if (position < 0 || position >= combinedLayout.length) {
            return position;
        }
        return combinedLayout[position];
    }

    public int[] combinedLayout() {
        return combinedLayout.clone();
    }

    public RecorderSettings withContinuousRecordingEnabled(boolean enabled) {
        return new RecorderSettings(
                volumeIndex,
                quotaBytes,
                quotaConfigured,
                retentionDays,
                segmentMinutes,
                minFreePercent,
                resolution,
                enabled,
                phoneAccessEnabled,
                phoneAccessCode,
                phoneAccessPin,
                dateFormat,
                cameraNames,
                combinedLayout,
                cameraFlipHorizontal,
                cameraFlipVertical,
                fisheyeCropPercent,
                vehicleModelId,
                gpsOverlayEnabled,
                gpsSpeedUnit,
                gpsShowCoordinates,
                gpsTrackEnabled,
                parkingImpactThresholdG,
                parkingRecordingSeconds,
                parkingAutoLock,
                telegramEnabled,
                telegramBotToken,
                telegramChatId,
                mqttEnabled,
                mqttHost,
                mqttPort,
                mqttUsername,
                mqttPassword,
                mqttTopicPrefix,
                cloudflareEnabled,
                cameraMotionEnabled,
                cameraMotionSensitivity,
                telemetryEnabled);
    }

    public RecorderSettings withPhoneAccess(
            boolean enabled,
            String accessCode,
            String accessPin) {
        return new RecorderSettings(
                volumeIndex,
                quotaBytes,
                quotaConfigured,
                retentionDays,
                segmentMinutes,
                minFreePercent,
                resolution,
                continuousRecordingEnabled,
                enabled,
                accessCode,
                accessPin,
                dateFormat,
                cameraNames,
                combinedLayout,
                cameraFlipHorizontal,
                cameraFlipVertical,
                fisheyeCropPercent,
                vehicleModelId,
                gpsOverlayEnabled,
                gpsSpeedUnit,
                gpsShowCoordinates,
                gpsTrackEnabled,
                parkingImpactThresholdG,
                parkingRecordingSeconds,
                parkingAutoLock,
                telegramEnabled,
                telegramBotToken,
                telegramChatId,
                mqttEnabled,
                mqttHost,
                mqttPort,
                mqttUsername,
                mqttPassword,
                mqttTopicPrefix,
                cloudflareEnabled,
                cameraMotionEnabled,
                cameraMotionSensitivity,
                telemetryEnabled);
    }

    public RecorderSettings withPhoneAccessPin(String accessPin) {
        return new RecorderSettings(
                volumeIndex,
                quotaBytes,
                quotaConfigured,
                retentionDays,
                segmentMinutes,
                minFreePercent,
                resolution,
                continuousRecordingEnabled,
                phoneAccessEnabled,
                phoneAccessCode,
                accessPin,
                dateFormat,
                cameraNames,
                combinedLayout,
                cameraFlipHorizontal,
                cameraFlipVertical,
                fisheyeCropPercent,
                vehicleModelId,
                gpsOverlayEnabled,
                gpsSpeedUnit,
                gpsShowCoordinates,
                gpsTrackEnabled,
                parkingImpactThresholdG,
                parkingRecordingSeconds,
                parkingAutoLock,
                telegramEnabled,
                telegramBotToken,
                telegramChatId,
                mqttEnabled,
                mqttHost,
                mqttPort,
                mqttUsername,
                mqttPassword,
                mqttTopicPrefix,
                cloudflareEnabled,
                cameraMotionEnabled,
                cameraMotionSensitivity,
                telemetryEnabled);
    }

    public RecorderSettings withVehicleModelId(String newModelId) {
        return new RecorderSettings(
                volumeIndex,
                quotaBytes,
                quotaConfigured,
                retentionDays,
                segmentMinutes,
                minFreePercent,
                resolution,
                continuousRecordingEnabled,
                phoneAccessEnabled,
                phoneAccessCode,
                phoneAccessPin,
                dateFormat,
                cameraNames,
                combinedLayout,
                cameraFlipHorizontal,
                cameraFlipVertical,
                fisheyeCropPercent,
                newModelId,
                gpsOverlayEnabled,
                gpsSpeedUnit,
                gpsShowCoordinates,
                gpsTrackEnabled,
                parkingImpactThresholdG,
                parkingRecordingSeconds,
                parkingAutoLock,
                telegramEnabled,
                telegramBotToken,
                telegramChatId,
                mqttEnabled,
                mqttHost,
                mqttPort,
                mqttUsername,
                mqttPassword,
                mqttTopicPrefix,
                cloudflareEnabled,
                cameraMotionEnabled,
                cameraMotionSensitivity,
                telemetryEnabled);
    }

    private static String defaultCameraName(int cameraIndex) {
        return "Camera " + (cameraIndex + 1);
    }

    private static String[] loadCameraNames(SharedPreferences preferences) {
        String[] names = new String[FrameProcessor.CAMERA_COUNT];
        for (int index = 0; index < names.length; index++) {
            names[index] =
                    preferences.getString(
                            KEY_CAMERA_NAME_PREFIX + index,
                            defaultCameraName(index));
        }
        return names;
    }

    private static int[] loadCombinedLayout(SharedPreferences preferences) {
        int[] layout = new int[FrameProcessor.CAMERA_COUNT];
        for (int index = 0; index < layout.length; index++) {
            layout[index] =
                    preferences.getInt(
                            KEY_COMBINED_POSITION_PREFIX + index,
                            index);
        }
        return layout;
    }

    private static boolean[] loadCameraFlips(
            SharedPreferences preferences,
            String keyPrefix) {
        boolean[] flips = new boolean[FrameProcessor.CAMERA_COUNT];
        for (int index = 0; index < flips.length; index++) {
            flips[index] =
                    preferences.getBoolean(keyPrefix + index, false);
        }
        return flips;
    }

    private static int loadFisheyeCropPercent(
            SharedPreferences preferences) {
        if (preferences.contains(KEY_FISHEYE_CROP_PERCENT)) {
            return preferences.getInt(
                    KEY_FISHEYE_CROP_PERCENT,
                    DEFAULT_FISHEYE_CROP_PERCENT);
        }
        return preferences.getInt(
                KEY_LEGACY_CAMERA_CROP_PERCENT_PREFIX + 0,
                DEFAULT_FISHEYE_CROP_PERCENT);
    }

    private static String[] normalizeCameraNames(String[] source) {
        String[] names = new String[FrameProcessor.CAMERA_COUNT];
        for (int index = 0; index < names.length; index++) {
            String name =
                    source != null && index < source.length && source[index] != null
                            ? source[index].trim().replaceAll("\\s+", " ")
                            : "";
            if (name.isEmpty()) {
                name = defaultCameraName(index);
            }
            names[index] =
                    name.length() > MAX_CAMERA_NAME_LENGTH
                            ? name.substring(0, MAX_CAMERA_NAME_LENGTH)
                            : name;
        }
        return names;
    }

    private static int[] normalizeCombinedLayout(int[] source) {
        int[] layout = new int[FrameProcessor.CAMERA_COUNT];
        boolean[] used = new boolean[FrameProcessor.CAMERA_COUNT];
        for (int position = 0; position < layout.length; position++) {
            int cameraIndex =
                    source != null && position < source.length
                            ? source[position]
                            : position;
            if (cameraIndex < 0
                    || cameraIndex >= FrameProcessor.CAMERA_COUNT
                    || used[cameraIndex]) {
                return new int[]{0, 1, 2, 3};
            }
            layout[position] = cameraIndex;
            used[cameraIndex] = true;
        }
        return layout;
    }

    private static boolean[] normalizeCameraFlips(boolean[] source) {
        boolean[] flips = new boolean[FrameProcessor.CAMERA_COUNT];
        if (source != null) {
            System.arraycopy(
                    source,
                    0,
                    flips,
                    0,
                    Math.min(source.length, flips.length));
        }
        return flips;
    }

    private static long calculateDefaultQuotaBytes(Context context) {
        File storageRoot = context.getExternalFilesDir(null);
        if (storageRoot == null) {
            storageRoot = context.getFilesDir();
        }
        long availableBytes;
        try {
            availableBytes = new StatFs(storageRoot.getAbsolutePath()).getAvailableBytes();
        } catch (RuntimeException exception) {
            return DEFAULT_QUOTA_BYTES;
        }
        long quotaBytes = (availableBytes / 10L) * 9L;
        long gibibyte = 1024L * 1024L * 1024L;
        if (quotaBytes > gibibyte) {
            quotaBytes = (quotaBytes / gibibyte) * gibibyte;
        }
        return quotaBytes;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clampLong(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clampFloat(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
