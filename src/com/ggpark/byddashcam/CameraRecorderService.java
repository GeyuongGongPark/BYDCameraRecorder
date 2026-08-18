package com.ggpark.byddashcam;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CameraRecorderService extends Service
        implements FrameSource.Listener, SegmentRecorder.Listener {
    public static final class PhonePreviewFrame {
        public final byte[] jpeg;
        public final long version;

        PhonePreviewFrame(byte[] jpeg, long version) {
            this.jpeg = jpeg;
            this.version = version;
        }
    }

    public enum Mode {
        NOT_RECORDING,
        RECORDING,
        PARKING_STANDBY,    // 주차 감시 대기 중 (충격 감지 대기)
        PARKING_RECORDING   // 충격 감지 후 녹화 중
    }

    public interface UiListener {
        void onPreviewFrames(Bitmap[] frames);
        void onServiceState(Mode mode, String message);
        void onRecorderSettingsChanged();
    }

    public final class LocalBinder extends Binder {
        public CameraRecorderService getService() {
            return CameraRecorderService.this;
        }
    }

    public static final String EXTRA_STARTUP_REASON = "startup_reason";
    private static final String CHANNEL_ID = "byd_camera_recording";
    private static final String PARKING_CHANNEL_ID = "byd_parking_guard";
    private static final int NOTIFICATION_ID = 48;
    private static final int PARKING_NOTIFICATION_ID = 49;
    private static final long PHONE_PREVIEW_GRACE_MILLIS = 5000L;
    private static final long PHONE_PREVIEW_WAIT_MILLIS = 10_000L;
    // Quality 75 halves the bytes per frame versus 90 with little visible
    // difference on a phone screen, doubling the deliverable frame rate on
    // the car's Wi-Fi link.
    private static final int PHONE_PREVIEW_JPEG_QUALITY = 75;
    private static final long PREVIEW_RETRY_INTERVAL_NANOS =
            1_000_000_000L;
    private static final long RECORD_INTERVAL_NANOS = 0L;
    private static final long PERFORMANCE_LOG_INTERVAL_NANOS =
            5_000_000_000L;
    // 주차 대기 중 프리버퍼 세그먼트 길이 (초)
    private static final int PRE_BUFFER_SECONDS = 12;
    private static final long INITIAL_RECOVERY_DELAY_MILLIS = 15_000L;
    // 자동 주차 모드 전환
    private static final String KEY_AUTO_MODE_SWITCH = "auto_mode_switch_enabled";
    private static final double AUTO_PARK_SPEED_THRESHOLD_KMH = 5.0;
    private static final long AUTO_PARK_DELAY_MS = 30_000L;  // 30초 정지 후 주차 감시 진입
    private static final long AUTO_RESUME_DELAY_MS = 3_000L; // 3초 후 주행 복귀 확정
    private static final long RECOVERY_INTERVAL_MILLIS = 60_000L;
    private static final long FIRST_RECORDING_FRAME_TIMEOUT_MILLIS = 20_000L;
    private static final String TAG = "BYDCamera";

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final FrameProcessor frameProcessor = new FrameProcessor();
    private final Object previewDeliveryLock = new Object();
    private final Object phonePreviewLock = new Object();
    private final ExecutorService phonePreviewExecutor =
            Executors.newSingleThreadExecutor();
    private volatile byte[][] phonePreviewJpegs =
            new byte[FrameProcessor.CAMERA_COUNT][];
    private volatile byte[][] phoneUncroppedPreviewJpegs =
            new byte[FrameProcessor.CAMERA_COUNT][];
    private final Map<String, byte[]> phoneSegmentPreviewCache =
            new LinkedHashMap<String, byte[]>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, byte[]> eldest) {
                    return size() > 80;
                }
            };

    private static final class CachedSegmentJson {
        final long modifiedAtMillis;
        final boolean locked;
        final String dateFormatId;
        final String json;

        CachedSegmentJson(
                long modifiedAtMillis,
                boolean locked,
                String dateFormatId,
                String json) {
            this.modifiedAtMillis = modifiedAtMillis;
            this.locked = locked;
            this.dateFormatId = dateFormatId;
            this.json = json;
        }
    }

    /**
     * Serialized JSON per finalized segment. Building each entry lists the
     * segment's files on disk, so rebuilding hundreds of unchanged entries on
     * every phone state fetch cost roughly a second of head-unit CPU.
     */
    private final Map<String, CachedSegmentJson> phoneSegmentJsonCache =
            new LinkedHashMap<String, CachedSegmentJson>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, CachedSegmentJson> eldest) {
                    return size() > 600;
                }
            };
    private volatile boolean cameraSourceActive;
    private volatile long lastPhoneClientMillis;
    private long phonePreviewVersion;
    private long phoneUncroppedPreviewVersion;
    private volatile long lastRecordedFrameNanos;
    private volatile Mode mode = Mode.NOT_RECORDING;
    private volatile Integer previewCropPercentOverride;
    private volatile RecorderSettings pendingRecordingSettings;
    private volatile String lastStateMessage = "Not recording";
    private volatile UiListener uiListener;
    private FrameSource frameSource;
    private PhoneAccessServer phoneAccessServer;
    private Bitmap[] pendingPreviewFrames;
    private final int[] phoneCameraSubscriberCounts =
            new int[FrameProcessor.CAMERA_COUNT];
    private final int[] phoneUncroppedSubscriberCounts =
            new int[FrameProcessor.CAMERA_COUNT];
    private int phoneOneShotCameraMask;
    private int phoneOneShotUncroppedMask;
    private byte[] phoneRawPending;
    private byte[] phoneRawSpare;
    private boolean phonePreviewWorkPosted;
    private boolean previewDeliveryPosted;
    private volatile boolean carBitmapPreviewRequired;
    private boolean previewProcessingFailed;
    private long previewRetryAfterNanos;
    private long previewLatencyMaximumNanos;
    private long previewLatencyTotalNanos;
    private long previewPerformanceWindowStartedNanos;
    private int previewPerformanceFrameCount;
    private long phoneEncodeWindowStartedNanos;
    private long phoneEncodeTotalNanos;
    private long phoneEncodeMaximumNanos;
    private int phoneEncodeFrameCount;
    private int phoneEncodeCameraCount;
    private long recordingWindowStartedNanos;
    private long recordingTotalNanos;
    private long recordingMaximumNanos;
    private int recordingFrameCount;
    private SegmentRecorder segmentRecorder;
    private StorageRepository storageRepository;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean segmentRecoveryLoopRunning;
    private volatile long uiStateVersion = 1L;
    private GpsDataProvider gpsDataProvider;
    private GpsOverlayRenderer gpsOverlayRenderer;
    private VehicleDataProvider vehicleDataProvider;
    private ParkingGuardController parkingGuardController;
    // 자동 주차/주행 모드 전환용
    private volatile double lastSpeedKmh = -1.0;
    private volatile boolean lastSpeedFromGps = false;
    private final Runnable autoParkRunnable = new Runnable() {
        @Override public void run() { tryAutoPark(); }
    };
    private final Runnable autoResumeRunnable = new Runnable() {
        @Override public void run() { tryAutoResume(); }
    };
    private TelegramNotifier telegramNotifier;
    private MqttPublisher mqttPublisher;
    private SystemMonitor systemMonitor;
    private CloudflaredTunnel cloudflaredTunnel;

    private final Runnable recordingStartupTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            onRecordingStartupTimeout();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        RecorderStartup.scheduleFallbacks(this, "service created");
        storageRepository = new StorageRepository(this);
        frameSource = FrameSourceFactory.create(this, this);
        segmentRecorder = new SegmentRecorder(storageRepository, this);
        PowerManager powerManager =
                (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BYDCamera:Recording");
        wakeLock.setReferenceCounted(false);
        RecorderSettings initialSettings = RecorderSettings.load(this);
        applyPhoneAccessSetting(initialSettings);
        startGps(initialSettings);
        startVehicleTelemetry(initialSettings);
        systemMonitor = new SystemMonitor();
        initTelegramNotifier(initialSettings);
        initMqttPublisher(initialSettings);
        initCloudflaredTunnel(initialSettings);
        startSegmentRecoveryLoop();
    }

    /**
     * Continuously heals interrupted segments: any segment whose recorder
     * process was killed (or whose background stitch was killed) is stitched
     * into playable videos, and unrecoverable pre-chunk segments are
     * reclaimed. Runs for the service lifetime because interruptions can
     * happen at any moment, not only before startup; the protected set and
     * the age guard keep it away from live work.
     */
    private void startSegmentRecoveryLoop() {
        segmentRecoveryLoopRunning = true;
        Thread recoveryThread = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        long delayMillis = INITIAL_RECOVERY_DELAY_MILLIS;
                        while (segmentRecoveryLoopRunning) {
                            try {
                                Thread.sleep(delayMillis);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            delayMillis = RECOVERY_INTERVAL_MILLIS;
                            try {
                                boolean changed =
                                        storageRepository.recoverInterruptedSegments(
                                                RecorderSettings.load(
                                                        CameraRecorderService.this),
                                                segmentRecorder
                                                        .getProtectedDirectories(),
                                                new StorageRepository.RecoveryListener() {
                                                    @Override
                                                    public void onSegmentRecovered(
                                                            String segmentName) {
                                                        publishState(
                                                                "Recording finalized: "
                                                                        + segmentName);
                                                    }
                                                });
                                if (changed) {
                                    publishState(
                                            "Interrupted recordings recovered");
                                }
                            } catch (IOException | RuntimeException exception) {
                                Log.w(
                                        TAG,
                                        "Interrupted-segment recovery skipped",
                                        exception);
                            }
                        }
                    }
                },
                "byd-segment-recovery");
        recoveryThread.setDaemon(true);
        recoveryThread.setPriority(Thread.MIN_PRIORITY);
        recoveryThread.start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String startupReason = intent == null
                ? "Android sticky service restart"
                : intent.getStringExtra(EXTRA_STARTUP_REASON);
        Log.i(
                TAG,
                "Recorder service start command: reason="
                        + (startupReason == null ? "direct app request" : startupReason)
                        + " flags="
                        + flags
                        + " startId="
                        + startId);
        RecorderSettings settings = RecorderSettings.load(this);
        applyPhoneAccessSetting(settings);
        if (settings.continuousRecordingEnabled) {
            if (canAccessCamera()) {
                startRecording(settings);
            } else {
                publishState(
                        "Continuous recording is waiting for camera permission");
            }
            return START_STICKY;
        }
        return mode == Mode.RECORDING || settings.phoneAccessEnabled
                ? START_STICKY
                : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        segmentRecoveryLoopRunning = false;
        stopGps();
        stopVehicleTelemetry();
        shutdown();
        closePhonePreviewWorker();
        if (telegramNotifier != null) {
            telegramNotifier.shutdown();
            telegramNotifier = null;
        }
        if (mqttPublisher != null) {
            mqttPublisher.stop();
            mqttPublisher = null;
        }
        if (cloudflaredTunnel != null) {
            cloudflaredTunnel.stop();
            cloudflaredTunnel = null;
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        RecorderSettings settings = RecorderSettings.load(this);
        Log.i(
                TAG,
                "Car task removed: recording="
                        + (mode == Mode.RECORDING)
                        + " continuousPreference="
                        + settings.continuousRecordingEnabled
                        + " foregroundServiceContinues="
                        + (mode == Mode.RECORDING));
        if (mode == Mode.RECORDING) {
            acquireWakeLock();
            enterForeground();
        }
        RecorderStartup.scheduleShortRecovery(this, "car task removed");
        RecorderStartup.scheduleFallbacks(this, "car task removed");
        super.onTaskRemoved(rootIntent);
    }

    public Mode getMode() {
        return mode;
    }

    public void attachCarPreviewTexture(
            SurfaceTexture texture,
            int cameraIndex) {
        if (frameSource instanceof AvmCameraController) {
            ((AvmCameraController) frameSource)
                    .attachPreviewTexture(texture, cameraIndex);
        }
    }

    public void detachCarPreviewTexture(
            SurfaceTexture texture,
            int cameraIndex) {
        if (frameSource instanceof AvmCameraController) {
            ((AvmCameraController) frameSource)
                    .detachPreviewTexture(texture, cameraIndex);
        }
    }

    public void setCarBitmapPreviewRequired(boolean required) {
        carBitmapPreviewRequired = required;
    }

    public String getPhoneAccessUrl() throws IOException {
        PhoneAccessServer server = phoneAccessServer;
        if (server == null) {
            throw new IOException("Phone app access is disabled or unavailable");
        }
        return server.getUrl();
    }

    public String getPhoneAccessStatus() {
        RecorderSettings settings = RecorderSettings.load(this);
        if (!settings.phoneAccessEnabled) {
            return "Phone app access is off";
        }
        return phoneAccessServer == null
                ? "Phone app access could not start on this device"
                : "Phone app access is on";
    }

    public byte[] getPhonePreviewJpeg(int cameraIndex) {
        if (cameraIndex < 0 || cameraIndex >= phonePreviewJpegs.length) {
            return null;
        }
        synchronized (phonePreviewLock) {
            phoneOneShotCameraMask |= 1 << cameraIndex;
        }
        return phonePreviewJpegs[cameraIndex];
    }

    public byte[] getPhoneUncroppedPreviewJpeg(int cameraIndex) {
        if (cameraIndex < 0
                || cameraIndex >= phoneUncroppedPreviewJpegs.length) {
            return null;
        }
        synchronized (phonePreviewLock) {
            phoneOneShotUncroppedMask |= 1 << cameraIndex;
        }
        return phoneUncroppedPreviewJpegs[cameraIndex];
    }

    public void addPhoneCameraSubscriber(int cameraIndex, boolean uncropped) {
        if (cameraIndex < 0
                || cameraIndex >= FrameProcessor.CAMERA_COUNT) {
            return;
        }
        synchronized (phonePreviewLock) {
            if (uncropped) {
                phoneUncroppedSubscriberCounts[cameraIndex]++;
            } else {
                phoneCameraSubscriberCounts[cameraIndex]++;
            }
        }
    }

    public void removePhoneCameraSubscriber(int cameraIndex, boolean uncropped) {
        if (cameraIndex < 0
                || cameraIndex >= FrameProcessor.CAMERA_COUNT) {
            return;
        }
        synchronized (phonePreviewLock) {
            int[] counts = uncropped
                    ? phoneUncroppedSubscriberCounts
                    : phoneCameraSubscriberCounts;
            counts[cameraIndex] = Math.max(0, counts[cameraIndex] - 1);
        }
    }

    public PhonePreviewFrame awaitPhonePreview(
            int cameraIndex,
            boolean uncropped,
            long previousVersion) throws InterruptedException {
        if (cameraIndex < 0
                || cameraIndex >= FrameProcessor.CAMERA_COUNT) {
            return null;
        }
        notePhoneClient();
        long deadline =
                System.currentTimeMillis() + PHONE_PREVIEW_WAIT_MILLIS;
        synchronized (phonePreviewLock) {
            long version =
                    uncropped
                            ? phoneUncroppedPreviewVersion
                            : phonePreviewVersion;
            while (version <= previousVersion) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    // Bounded wait: the caller pings its socket and retries,
                    // so a dead client cannot hold a thread and camera
                    // subscription forever while frames are not flowing.
                    return null;
                }
                phonePreviewLock.wait(remaining);
                version =
                        uncropped
                                ? phoneUncroppedPreviewVersion
                                : phonePreviewVersion;
            }
            byte[] jpeg =
                    uncropped
                            ? phoneUncroppedPreviewJpegs[cameraIndex]
                            : phonePreviewJpegs[cameraIndex];
            return new PhonePreviewFrame(jpeg, version);
        }
    }

    public void notePhoneClient() {
        lastPhoneClientMillis = System.currentTimeMillis();
        if (cameraSourceActive || mode == Mode.RECORDING) {
            return;
        }
        synchronized (this) {
            if (mode == Mode.NOT_RECORDING && !cameraSourceActive && canAccessCamera()) {
                RecorderSettings settings = RecorderSettings.load(this);
                frameProcessor.configure(
                        settings.resolution,
                        settings.combinedLayout(),
                        settings.cameraFlipHorizontal(),
                        settings.cameraFlipVertical(),
                        settings.fisheyeCropPercent());
                cameraSourceActive = true;
                frameSource.start();
                publishState("Phone live preview active");
            }
        }
    }

    public List<StorageRepository.SegmentInfo> listSegments(RecorderSettings settings)
            throws IOException {
        return storageRepository.listSegments(
                settings,
                segmentRecorder.getActiveDirectory());
    }

    public StorageRepository.StorageSnapshot getStorageSnapshot(
            RecorderSettings settings) throws IOException {
        return storageRepository.snapshot(settings);
    }

    public List<StorageRepository.StorageVolume> getVolumes() {
        return storageRepository.getVolumes();
    }

    public void setSegmentLocked(
            RecorderSettings settings,
            File directory,
            boolean locked) throws IOException {
        if (segmentRecorder.getActiveDirectory() != null
                && segmentRecorder.getActiveDirectory().equals(directory)) {
            throw new IOException("The active segment cannot be locked until it closes");
        }
        storageRepository.setLocked(settings, directory, locked);
        publishState(locked ? "Recording locked" : "Recording unlocked");
    }

    public void deleteSegments(
            RecorderSettings settings,
            List<File> directories) throws IOException {
        for (File directory : directories) {
            if (segmentRecorder.isProtectedDirectory(directory)) {
                throw new IOException(
                        "The recording is still being written or finalized");
            }
            storageRepository.deleteSegment(
                    settings,
                    directory,
                    segmentRecorder.getActiveDirectory());
        }
        publishState("Selected recordings deleted");
    }

    public void setUiListener(UiListener listener) {
        uiListener = listener;
        if (listener != null) {
            listener.onServiceState(mode, describeMode());
        }
    }

    public void setPreviewCropPercentOverride(Integer cropPercent) {
        previewCropPercentOverride =
                cropPercent == null
                        ? null
                        : Math.max(
                                0,
                                Math.min(
                                        RecorderSettings.MAX_CAMERA_CROP_PERCENT,
                                        cropPercent));
    }

    public void releasePreviewFrames(Bitmap[] frames) {
        frameProcessor.releasePreviewBitmaps(frames);
    }

    public synchronized void startPreview() {
        if (mode == Mode.RECORDING) {
            publishState("Recording already includes live camera preview");
            return;
        }
        if (cameraSourceActive || frameSource.isRunning()) {
            cameraSourceActive = true;
            publishState("Long press a recording to enter bulk action mode");
            return;
        }
        RecorderSettings settings = RecorderSettings.load(this);
        frameProcessor.configure(
                settings.resolution,
                settings.combinedLayout(),
                settings.cameraFlipHorizontal(),
                settings.cameraFlipVertical(),
                settings.fisheyeCropPercent());
        previewProcessingFailed = false;
        previewRetryAfterNanos = 0L;
        mode = Mode.NOT_RECORDING;
        frameSource.start();
        cameraSourceActive = true;
        publishState("Starting camera preview");
    }

    public synchronized void startRecording(RecorderSettings settings) {
        if (mode == Mode.RECORDING) {
            publishState("Recording is already active");
            return;
        }
        RecorderSettings recordingSettings =
                settings.withContinuousRecordingEnabled(true);
        recordingSettings.save(this);
        frameProcessor.configure(
                recordingSettings.resolution,
                recordingSettings.combinedLayout(),
                recordingSettings.cameraFlipHorizontal(),
                recordingSettings.cameraFlipVertical(),
                recordingSettings.fisheyeCropPercent());
        previewProcessingFailed = false;
        previewRetryAfterNanos = 0L;
        pendingRecordingSettings = recordingSettings;
        lastRecordedFrameNanos = 0L;
        mode = Mode.RECORDING;
        scheduleRecordingStartupTimeout();
        enterForeground();
        acquireWakeLock();
        frameSource.start();
        cameraSourceActive = true;
        publishState("Waiting for the first camera frame");
    }

    public synchronized void releasePreview() {
        if (mode == Mode.RECORDING) {
            return;
        }
        if (isPhonePreviewRequested()) {
            publishState("Phone live preview continues");
            return;
        }
        frameSource.stop();
        cameraSourceActive = false;
        publishState("Live preview paused while the app is hidden");
    }

    public synchronized void stopRecording() {
        RecorderSettings.load(this)
                .withContinuousRecordingEnabled(false)
                .save(this);
        if (mode == Mode.PARKING_STANDBY || mode == Mode.PARKING_RECORDING) {
            exitParkingMode();
            return;
        }
        if (mode != Mode.RECORDING) {
            publishState("Not recording");
            return;
        }
        pendingRecordingSettings = null;
        cancelRecordingStartupTimeout();
        segmentRecorder.stop();
        mode = Mode.NOT_RECORDING;
        releaseWakeLock();
        stopForeground(true);
        applyPhoneAccessSetting(RecorderSettings.load(this));
        publishState("Recording finalized; live preview continues");
    }

    @Override
    public synchronized void onCameraFrame(CameraFrame rawFrame) {
        Mode currentMode = mode;
        UiListener currentUiListener = uiListener;
        boolean phonePreviewRequested = isPhonePreviewRequested();
        boolean carBitmapPreviewRequested =
                currentUiListener != null && carBitmapPreviewRequired;
        boolean previewRequested =
                carBitmapPreviewRequested || phonePreviewRequested;
        boolean nativeCarPreviewPossible =
                currentUiListener != null
                        && frameSource instanceof AvmCameraController;
        boolean recordingFrameDue =
                (currentMode == Mode.RECORDING || currentMode == Mode.PARKING_RECORDING
                        || currentMode == Mode.PARKING_STANDBY)
                        && (lastRecordedFrameNanos == 0L
                                || rawFrame.monotonicNanos
                                        - lastRecordedFrameNanos
                                        >= RECORD_INTERVAL_NANOS);
        // 주차 감시 대기 중에는 카메라를 열어 둬야 합니다 (충격 감지 후 즉시 녹화를 위해).
        boolean keepCameraOpen = currentMode == Mode.PARKING_STANDBY
                || currentMode == Mode.PARKING_RECORDING;
        if (!previewRequested && !recordingFrameDue && !nativeCarPreviewPossible
                && !keepCameraOpen) {
            if (currentMode == Mode.NOT_RECORDING) {
                frameSource.stop();
                cameraSourceActive = false;
            }
            return;
        }

        if (previewRequested
                && rawFrame.monotonicNanos >= previewRetryAfterNanos) {
            try {
                if (carBitmapPreviewRequested) {
                    Integer cropOverride = previewCropPercentOverride;
                    Bitmap[] bitmaps =
                            frameProcessor.createPreviewBitmapsFromSource(
                                    rawFrame.data,
                                    rawFrame.width,
                                    rawFrame.height,
                                    rawFrame.format,
                                    rawFrame.dataSize,
                                    cropOverride == null ? -1 : cropOverride);
                    queueLatestPreview(bitmaps);
                    recordPreviewPerformance(
                            rawFrame.monotonicNanos,
                            System.nanoTime());
                }
                if (phonePreviewRequested) {
                    queueLatestPhoneRawFrame(rawFrame);
                }
                if (previewProcessingFailed) {
                    previewProcessingFailed = false;
                    publishState("Live preview recovered");
                }
            } catch (Throwable throwable) {
                Log.e(
                        TAG,
                        "Live preview frame failed; retrying without stopping recording",
                        throwable);
                previewRetryAfterNanos =
                        rawFrame.monotonicNanos
                                + PREVIEW_RETRY_INTERVAL_NANOS;
                if (!previewProcessingFailed) {
                    previewProcessingFailed = true;
                    publishState(
                            "Live preview retrying; recording continues");
                }
            }
        }

        try {
            if (recordingFrameDue) {
                long recordingStartedNanos = System.nanoTime();
                FrameProcessor.ProcessedFrame processed = frameProcessor.process(
                        rawFrame.data,
                        rawFrame.width,
                        rawFrame.height,
                        rawFrame.format,
                        rawFrame.dataSize,
                        rawFrame.monotonicNanos);
                RecorderSettings pending = pendingRecordingSettings;
                if (!segmentRecorder.isStarted()) {
                    if (pending == null) {
                        throw new IOException("Recording settings are unavailable");
                    }
                    segmentRecorder.start(pending);
                    pendingRecordingSettings = null;
                }
                segmentRecorder.offerFrame(processed);
                if (lastRecordedFrameNanos == 0L) {
                    cancelRecordingStartupTimeout();
                    publishState(describeMode());
                }
                lastRecordedFrameNanos = rawFrame.monotonicNanos;
                recordRecordingPerformance(
                        recordingStartedNanos,
                        System.nanoTime());
            }
            // PARKING_STANDBY 중 카메라 모션 감지 (rawFrame의 Y채널 직접 사용)
            if (currentMode == Mode.PARKING_STANDBY && parkingGuardController != null) {
                parkingGuardController.offerCameraFrame(
                        rawFrame.data, rawFrame.width, rawFrame.height);
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "Recording frame processing failed", throwable);
            stopRecordingAfterFailure(throwable);
        }
    }

    public boolean kickNativePreviewRender() {
        return frameSource instanceof AvmCameraController
                && ((AvmCameraController) frameSource).kickPreviewRender();
    }

    @Override
    public void onCameraState(String state) {
        if (mode == Mode.NOT_RECORDING
                && "Direct camera frame stream active".equals(state)) {
            publishState(
                    "Long press a recording to enter bulk action mode");
        } else {
            publishState(state);
        }
    }

    @Override
    public void onRecorderState(String state) {
        publishState(state);
    }

    public synchronized void applyPhoneAccessSetting(RecorderSettings settings) {
        RecorderSettings effectiveSettings = settings;
        if (settings.phoneAccessEnabled
                && (settings.phoneAccessCode.isEmpty()
                        || settings.phoneAccessPin.isEmpty())) {
            effectiveSettings =
                    settings.withPhoneAccess(
                            true,
                            settings.phoneAccessCode.isEmpty()
                                    ? PhoneAccessCode.createAccessCode()
                                    : settings.phoneAccessCode,
                            settings.phoneAccessPin.isEmpty()
                                    ? PhoneAccessCode.createPin()
                                    : settings.phoneAccessPin);
            effectiveSettings.save(this);
        }
        if (!effectiveSettings.phoneAccessEnabled) {
            closePhoneAccessServer();
            if (mode != Mode.RECORDING) {
                stopForeground(true);
            }
            return;
        }
        if (phoneAccessServer == null) {
            try {
                phoneAccessServer = new PhoneAccessServer(
                        this,
                        this,
                        effectiveSettings.phoneAccessCode,
                        effectiveSettings.phoneAccessPin);
            } catch (IOException | RuntimeException exception) {
                Log.e(TAG, "Cannot start phone app access", exception);
                String detail = exception.getMessage();
                lastStateMessage =
                        "Phone app access unavailable: "
                                + (detail == null
                                        ? exception.getClass().getSimpleName()
                                        : detail);
            }
        } else {
            phoneAccessServer.updatePin(effectiveSettings.phoneAccessPin);
        }
        if (mode != Mode.RECORDING) {
            enterForeground();
        }
    }

    public synchronized void applyRecorderSettings(RecorderSettings settings) {
        applyPhoneAccessSetting(settings);
        applyGpsSettings(settings);
        applyNotificationSettings(settings);
        if (mode == Mode.RECORDING) {
            // Segment length and storage policy changes take effect on the
            // running recording instead of waiting for a restart.
            segmentRecorder.updateSettings(
                    settings.withContinuousRecordingEnabled(true));
            frameProcessor.configureTransforms(
                    settings.combinedLayout(),
                    settings.cameraFlipHorizontal(),
                    settings.cameraFlipVertical(),
                    settings.fisheyeCropPercent());
        } else {
            frameProcessor.configure(
                    settings.resolution,
                    settings.combinedLayout(),
                    settings.cameraFlipHorizontal(),
                    settings.cameraFlipVertical(),
                    settings.fisheyeCropPercent());
        }
    }

    private void initTelegramNotifier(RecorderSettings settings) {
        telegramNotifier = new TelegramNotifier(
                settings.telegramBotToken,
                settings.telegramChatId,
                settings.telegramEnabled);
    }

    private void initMqttPublisher(RecorderSettings settings) {
        mqttPublisher = new MqttPublisher(
                settings.mqttHost,
                settings.mqttPort,
                settings.mqttUsername,
                settings.mqttPassword,
                settings.mqttEnabled);
        mqttPublisher.start();
    }

    private void initCloudflaredTunnel(RecorderSettings settings) {
        cloudflaredTunnel = new CloudflaredTunnel(
                this,
                8765,
                RecorderSettings.load(this).phoneAccessCode,
                settings.cloudflareEnabled);
        cloudflaredTunnel.setListener(new CloudflaredTunnel.Listener() {
            @Override
            public void onTunnelUrl(String url) {
                Log.i(TAG, "Cloudflare tunnel URL: " + url);
                if (telegramNotifier != null) {
                    telegramNotifier.send("외부 접속 URL: " + url);
                }
                publishState("터널: " + url);
            }

            @Override
            public void onTunnelStopped() {
                Log.i(TAG, "Cloudflare tunnel stopped");
            }
        });
        if (settings.cloudflareEnabled) {
            cloudflaredTunnel.start();
        }
    }

    private void applyNotificationSettings(RecorderSettings settings) {
        if (telegramNotifier != null) {
            telegramNotifier.update(
                    settings.telegramBotToken,
                    settings.telegramChatId,
                    settings.telegramEnabled);
        }
        if (mqttPublisher != null) {
            mqttPublisher.update(
                    settings.mqttHost,
                    settings.mqttPort,
                    settings.mqttUsername,
                    settings.mqttPassword,
                    settings.mqttEnabled);
        }
        if (cloudflaredTunnel != null) {
            cloudflaredTunnel.update(settings.cloudflareEnabled);
        }
    }

    public SystemMonitor.Snapshot getSystemSnapshot() {
        if (systemMonitor == null) return null;
        return systemMonitor.snapshot(this);
    }

    private void startGps(RecorderSettings settings) {
        gpsDataProvider = new GpsDataProvider();
        gpsDataProvider.setListener(new GpsDataProvider.Listener() {
            @Override
            public void onFixUpdated(GpsFix fix) {
                onGpsFixUpdated(fix);
            }
        });
        gpsDataProvider.start(this);
        applyGpsSettings(settings);
    }

    private void stopGps() {
        if (gpsDataProvider != null) {
            gpsDataProvider.stop();
            gpsDataProvider = null;
        }
        gpsOverlayRenderer = null;
        frameProcessor.setGpsOverlayRenderer(null);
    }

    private void applyGpsSettings(RecorderSettings settings) {
        if (gpsOverlayRenderer == null) {
            gpsOverlayRenderer = new GpsOverlayRenderer(
                    settings.gpsOverlayEnabled,
                    "kmh".equals(settings.gpsSpeedUnit),
                    settings.gpsShowCoordinates);
            frameProcessor.setGpsOverlayRenderer(gpsOverlayRenderer);
        } else {
            gpsOverlayRenderer.setEnabled(settings.gpsOverlayEnabled);
            gpsOverlayRenderer.setUseKmh("kmh".equals(settings.gpsSpeedUnit));
            gpsOverlayRenderer.setShowCoordinates(settings.gpsShowCoordinates);
        }
    }

    private void onGpsFixUpdated(GpsFix fix) {
        frameProcessor.updateGpsFix(fix);
        segmentRecorder.updateGpsFix(fix);
        if (fix != null && fix.isAvailable()) {
            lastSpeedFromGps = true;
            onSpeedUpdated(fix.speedKmh);
        }
    }

    private void startVehicleTelemetry(RecorderSettings settings) {
        if (!settings.telemetryEnabled) {
            return;
        }
        vehicleDataProvider = new VehicleDataProvider();
        vehicleDataProvider.setListener(new VehicleDataProvider.Listener() {
            @Override
            public void onTelemetryUpdated(VehicleTelemetry telemetry) {
                onVehicleTelemetryUpdated(telemetry);
            }
        });
        vehicleDataProvider.start(this);
    }

    private void stopVehicleTelemetry() {
        if (vehicleDataProvider != null) {
            vehicleDataProvider.stop();
            vehicleDataProvider = null;
        }
    }

    private boolean isAutoModeSwitchEnabled() {
        return getSharedPreferences("recorder_settings", MODE_PRIVATE)
                .getBoolean(KEY_AUTO_MODE_SWITCH, true);
    }

    /**
     * 속도가 업데이트될 때마다 호출됩니다.
     * 녹화 중 → 정지 30초 → 주차 감시 자동 진입
     * 주차 감시 중 → 주행 감지 → 즉시 녹화 복귀
     */
    private void onSpeedUpdated(double speedKmh) {
        lastSpeedKmh = speedKmh;
        if (!isAutoModeSwitchEnabled()) {
            return;
        }
        Mode currentMode = mode;
        if (currentMode == Mode.RECORDING) {
            if (speedKmh <= AUTO_PARK_SPEED_THRESHOLD_KMH) {
                // 정지 상태: 딜레이 후 주차 감시 진입
                if (!mainHandler.hasCallbacks(autoParkRunnable)) {
                    mainHandler.postDelayed(autoParkRunnable, AUTO_PARK_DELAY_MS);
                }
            } else {
                // 주행 중: 주차 예약 취소
                mainHandler.removeCallbacks(autoParkRunnable);
            }
        } else if (currentMode == Mode.PARKING_STANDBY
                || currentMode == Mode.PARKING_RECORDING) {
            if (speedKmh > AUTO_PARK_SPEED_THRESHOLD_KMH) {
                // 주행 감지: 딜레이 후 녹화 복귀 (순간 속도 오탐 방지)
                mainHandler.removeCallbacks(autoParkRunnable);
                if (!mainHandler.hasCallbacks(autoResumeRunnable)) {
                    mainHandler.postDelayed(autoResumeRunnable, AUTO_RESUME_DELAY_MS);
                }
            } else {
                mainHandler.removeCallbacks(autoResumeRunnable);
            }
        } else {
            mainHandler.removeCallbacks(autoParkRunnable);
            mainHandler.removeCallbacks(autoResumeRunnable);
        }
    }

    private synchronized void tryAutoPark() {
        if (mode != Mode.RECORDING) {
            return;
        }
        if (lastSpeedKmh > AUTO_PARK_SPEED_THRESHOLD_KMH) {
            return; // 속도가 다시 올라간 경우 취소
        }
        Log.i(TAG, "Auto-switching to parking mode (speed=" + lastSpeedKmh + " km/h)");
        enterParkingMode();
    }

    private synchronized void tryAutoResume() {
        if (mode != Mode.PARKING_STANDBY && mode != Mode.PARKING_RECORDING) {
            return;
        }
        if (lastSpeedKmh <= AUTO_PARK_SPEED_THRESHOLD_KMH) {
            return; // 다시 정지한 경우 취소
        }
        Log.i(TAG, "Auto-resuming recording (speed=" + lastSpeedKmh + " km/h)");
        exitParkingMode();
        RecorderSettings settings = RecorderSettings.load(this);
        startRecording(settings);
    }

    private void onVehicleTelemetryUpdated(VehicleTelemetry telemetry) {
        GpsOverlayRenderer renderer = gpsOverlayRenderer;
        if (renderer != null) {
            renderer.updateTelemetry(telemetry);
        }
        segmentRecorder.updateTelemetry(telemetry);
        // GPS가 없을 때만 텔레메트리 속도 사용 (GPS 우선)
        if (!lastSpeedFromGps && telemetry != null && telemetry.isAvailable()) {
            onSpeedUpdated(telemetry.speedKmh);
        }
    }

    /** 주차 감시 모드로 진입합니다. */
    public synchronized void enterParkingMode() {
        if (mode == Mode.PARKING_STANDBY || mode == Mode.PARKING_RECORDING) {
            publishState("주차 감시 모드가 이미 활성화되어 있습니다");
            return;
        }
        // 기존 일반 녹화 중이라면 중지합니다.
        if (mode == Mode.RECORDING) {
            pendingRecordingSettings = null;
            segmentRecorder.stop();
            releaseWakeLock();
        }
        RecorderSettings settings = RecorderSettings.load(this);
        // continuousRecordingEnabled는 false 유지 (주차 모드는 별도 상태)
        settings.withContinuousRecordingEnabled(false).save(this);
        // 카메라가 꺼져 있으면 시작합니다.
        if (!cameraSourceActive) {
            frameProcessor.configure(
                    settings.resolution,
                    settings.combinedLayout(),
                    settings.cameraFlipHorizontal(),
                    settings.cameraFlipVertical(),
                    settings.fisheyeCropPercent());
            frameSource.start();
            cameraSourceActive = true;
        }
        mode = Mode.PARKING_STANDBY;
        // 프리버퍼 녹화 시작: 충격 발생 시 직전 영상도 보존하기 위해
        RecorderSettings parkingRecordingSettings =
                settings.withContinuousRecordingEnabled(false);
        lastRecordedFrameNanos = 0L;
        pendingRecordingSettings = parkingRecordingSettings;
        try {
            segmentRecorder.startPreBuffer(parkingRecordingSettings, PRE_BUFFER_SECONDS);
        } catch (IOException e) {
            Log.e(TAG, "Pre-buffer start failed; standby continues without pre-buffer", e);
        }
        ParkingGuardSettings parkingSettings = new ParkingGuardSettings(
                settings.parkingImpactThresholdG,
                settings.parkingRecordingSeconds,
                settings.parkingAutoLock,
                settings.cameraMotionEnabled,
                settings.cameraMotionSensitivity);
        parkingGuardController = new ParkingGuardController(
                this,
                new ParkingGuardController.Callback() {
                    @Override
                    public void onImpactRecordingStarted(float gForce) {
                        handleImpactRecordingStarted(gForce);
                    }
                    @Override
                    public void onMotionRecordingStarted() {
                        handleMotionRecordingStarted();
                    }
                    @Override
                    public void onImpactRecordingStopped() {
                        handleImpactRecordingStopped();
                    }
                });
        parkingGuardController.start(parkingSettings);
        acquireWakeLock();
        enterForeground();
        publishState("주차 감시 시작 - 충격 감지 대기");
    }

    /** 주차 감시 모드를 해제합니다. */
    public synchronized void exitParkingMode() {
        if (mode != Mode.PARKING_STANDBY && mode != Mode.PARKING_RECORDING) {
            publishState("주차 감시 모드가 활성화되어 있지 않습니다");
            return;
        }
        if (parkingGuardController != null) {
            parkingGuardController.stop();
            parkingGuardController = null;
        }
        // 프리버퍼 디렉토리 보호 해제 (충격 없이 종료 시 cleanup이 삭제할 수 있도록)
        segmentRecorder.getAndClearPreBufferDirs();
        if (segmentRecorder.isStarted()) {
            segmentRecorder.stop();
        }
        pendingRecordingSettings = null;
        mode = Mode.NOT_RECORDING;
        releaseWakeLock();
        stopForeground(true);
        applyPhoneAccessSetting(RecorderSettings.load(this));
        publishState("주차 감시 종료");
    }

    private synchronized void handleImpactRecordingStarted(float gForce) {
        if (mode != Mode.PARKING_STANDBY) {
            return;
        }
        Log.i(TAG, "Parking impact: " + gForce + "G - starting recording");
        mode = Mode.PARKING_RECORDING;
        RecorderSettings settings = RecorderSettings.load(this);
        // 다음 세그먼트(이벤트 세그먼트)에 충격 메타데이터 기록
        segmentRecorder.setEventMetadata("impact", gForce);
        if (segmentRecorder.isStarted()) {
            // 프리버퍼 디렉토리 잠금 후 이벤트 세그먼트로 rotate
            List<File> preBufferDirs = segmentRecorder.getAndClearPreBufferDirs();
            if (settings.parkingAutoLock) {
                for (File dir : preBufferDirs) {
                    try {
                        storageRepository.setLocked(settings, dir, true);
                        Log.i(TAG, "Pre-buffer dir locked: " + dir.getName());
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to lock pre-buffer dir: " + dir.getName(), e);
                    }
                }
                segmentRecorder.setAutoLockForNextSegment();
            }
            try {
                segmentRecorder.rotateNow(false);
            } catch (IOException exception) {
                Log.e(TAG, "Parking segment rotate failed", exception);
            }
        } else {
            RecorderSettings parkingRecordingSettings =
                    settings.withContinuousRecordingEnabled(false);
            lastRecordedFrameNanos = 0L;
            pendingRecordingSettings = parkingRecordingSettings;
            scheduleRecordingStartupTimeout();
            if (settings.parkingAutoLock) {
                segmentRecorder.setAutoLockForNextSegment();
            }
        }
        enterForeground();
        sendImpactNotification(gForce);
        String impactMsg = "⚠️ 충격 감지: " + String.format("%.1f", gForce) + "G - 주차 녹화 시작";
        if (telegramNotifier != null) {
            telegramNotifier.send(impactMsg);
        }
        if (mqttPublisher != null) {
            RecorderSettings s = RecorderSettings.load(this);
            String prefix = s.mqttTopicPrefix;
            mqttPublisher.publish(prefix + "/parking/impact",
                    String.format("{\"gForce\":%.1f}", gForce));
            mqttPublisher.publish(prefix + "/state", "parking_recording");
        }
        publishState("충격 감지: " + String.format("%.1f", gForce) + "G - 녹화 시작");
    }

    private synchronized void handleMotionRecordingStarted() {
        if (mode != Mode.PARKING_STANDBY) {
            return;
        }
        Log.i(TAG, "Parking camera motion detected - starting recording");
        mode = Mode.PARKING_RECORDING;
        RecorderSettings settings = RecorderSettings.load(this);
        segmentRecorder.setEventMetadata("motion", 0f);
        if (segmentRecorder.isStarted()) {
            List<File> preBufferDirs = segmentRecorder.getAndClearPreBufferDirs();
            if (settings.parkingAutoLock) {
                for (File dir : preBufferDirs) {
                    try {
                        storageRepository.setLocked(settings, dir, true);
                        Log.i(TAG, "Pre-buffer dir locked (motion): " + dir.getName());
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to lock pre-buffer dir: " + dir.getName(), e);
                    }
                }
                segmentRecorder.setAutoLockForNextSegment();
            }
            try {
                segmentRecorder.rotateNow(false);
            } catch (IOException exception) {
                Log.e(TAG, "Motion recording rotate failed", exception);
            }
        } else {
            RecorderSettings parkingRecordingSettings =
                    settings.withContinuousRecordingEnabled(false);
            lastRecordedFrameNanos = 0L;
            pendingRecordingSettings = parkingRecordingSettings;
            scheduleRecordingStartupTimeout();
            if (settings.parkingAutoLock) {
                segmentRecorder.setAutoLockForNextSegment();
            }
        }
        enterForeground();
        if (telegramNotifier != null) {
            telegramNotifier.send("📹 모션 감지: 주차 녹화 시작");
        }
        if (mqttPublisher != null) {
            RecorderSettings s = RecorderSettings.load(this);
            String prefix = s.mqttTopicPrefix;
            mqttPublisher.publish(prefix + "/parking/motion", "{\"detected\":true}");
            mqttPublisher.publish(prefix + "/state", "parking_recording");
        }
        publishState("모션 감지 - 주차 녹화 시작");
    }

    private synchronized void handleImpactRecordingStopped() {
        if (mode != Mode.PARKING_RECORDING) {
            return;
        }
        Log.i(TAG, "Parking recording timeout - returning to standby");
        cancelRecordingStartupTimeout();
        if (segmentRecorder.isStarted()) {
            segmentRecorder.stop();
        }
        // 프리버퍼 모드로 재시작하여 다음 충격 이벤트를 대비
        RecorderSettings settings = RecorderSettings.load(this);
        RecorderSettings parkingSettings = settings.withContinuousRecordingEnabled(false);
        lastRecordedFrameNanos = 0L;
        pendingRecordingSettings = parkingSettings;
        try {
            segmentRecorder.startPreBuffer(parkingSettings, PRE_BUFFER_SECONDS);
        } catch (IOException e) {
            Log.e(TAG, "Pre-buffer restart failed after event", e);
            pendingRecordingSettings = null;
        }
        mode = Mode.PARKING_STANDBY;
        enterForeground();
        publishState("주차 감시 녹화 완료 - 대기 중");
    }

    public synchronized String regeneratePhoneAccessPin() {
        RecorderSettings updated =
                RecorderSettings.load(this)
                        .withPhoneAccessPin(PhoneAccessCode.createPin());
        updated.save(this);
        if (phoneAccessServer != null) {
            phoneAccessServer.updatePin(updated.phoneAccessPin);
        }
        publishState("Phone PIN regenerated; connected phones signed out");
        return updated.phoneAccessPin;
    }

    public String getPhoneAccessPin() {
        RecorderSettings settings = RecorderSettings.load(this);
        if (settings.phoneAccessPin.isEmpty()) {
            settings = settings.withPhoneAccessPin(PhoneAccessCode.createPin());
            settings.save(this);
        }
        return settings.phoneAccessPin;
    }

    public synchronized void setParkingFromPhone(boolean enabled) {
        if (enabled) {
            if (!isParkingGuardActive()) {
                enterParkingMode();
            }
        } else {
            if (isParkingGuardActive()) {
                exitParkingMode();
            }
        }
    }

    public void setRecordingFromPhone(boolean enabled) throws IOException {
        RecorderSettings settings =
                RecorderSettings.load(this)
                        .withContinuousRecordingEnabled(enabled);
        if (enabled) {
            if (!canAccessCamera()) {
                throw new IOException("Camera permissions are required on the car display");
            }
            settings.save(this);
            startRecording(settings);
        } else {
            settings.save(this);
            stopRecording();
        }
    }

    public void saveSettingsFromPhone(String json) {
        RecorderSettings current = RecorderSettings.load(this);
        double quotaGb = PhoneJson.doubleValue(
                json,
                "quotaGb",
                current.quotaBytes / (1024.0 * 1024.0 * 1024.0));
        RecorderSettings updated = new RecorderSettings(
                PhoneJson.intValue(json, "volumeIndex", current.volumeIndex),
                (long) (quotaGb * 1024.0 * 1024.0 * 1024.0),
                true,
                PhoneJson.intValue(json, "retentionDays", current.retentionDays),
                PhoneJson.intValue(json, "segmentMinutes", current.segmentMinutes),
                PhoneJson.intValue(json, "minFreePercent", current.minFreePercent),
                VideoResolution.fromId(
                        PhoneJson.stringValue(
                                json,
                                "resolution",
                                current.resolution.id)),
                current.continuousRecordingEnabled,
                current.phoneAccessEnabled,
                current.phoneAccessCode,
                current.phoneAccessPin,
                DisplayDateFormat.fromId(
                        PhoneJson.stringValue(
                                json,
                                "dateFormat",
                                current.dateFormat.id)),
                new String[]{
                    PhoneJson.stringValue(
                            json,
                            "camera1Name",
                            current.cameraName(0)),
                    PhoneJson.stringValue(
                            json,
                            "camera2Name",
                            current.cameraName(1)),
                    PhoneJson.stringValue(
                            json,
                            "camera3Name",
                            current.cameraName(2)),
                    PhoneJson.stringValue(
                            json,
                            "camera4Name",
                            current.cameraName(3))
                },
                new int[]{
                    PhoneJson.intValue(
                            json,
                            "combinedTopLeft",
                            current.combinedCameraIndex(0)),
                    PhoneJson.intValue(
                            json,
                            "combinedTopRight",
                            current.combinedCameraIndex(1)),
                    PhoneJson.intValue(
                            json,
                            "combinedBottomLeft",
                            current.combinedCameraIndex(2)),
                    PhoneJson.intValue(
                            json,
                            "combinedBottomRight",
                            current.combinedCameraIndex(3))
                },
                new boolean[]{
                    PhoneJson.booleanValue(
                            json,
                            "camera1FlipHorizontal",
                            current.cameraFlipHorizontal(0)),
                    PhoneJson.booleanValue(
                            json,
                            "camera2FlipHorizontal",
                            current.cameraFlipHorizontal(1)),
                    PhoneJson.booleanValue(
                            json,
                            "camera3FlipHorizontal",
                            current.cameraFlipHorizontal(2)),
                    PhoneJson.booleanValue(
                            json,
                            "camera4FlipHorizontal",
                            current.cameraFlipHorizontal(3))
                },
                new boolean[]{
                    PhoneJson.booleanValue(
                            json,
                            "camera1FlipVertical",
                            current.cameraFlipVertical(0)),
                    PhoneJson.booleanValue(
                            json,
                            "camera2FlipVertical",
                            current.cameraFlipVertical(1)),
                    PhoneJson.booleanValue(
                            json,
                            "camera3FlipVertical",
                            current.cameraFlipVertical(2)),
                    PhoneJson.booleanValue(
                            json,
                            "camera4FlipVertical",
                            current.cameraFlipVertical(3))
                },
                PhoneJson.intValue(
                        json,
                        "fisheyeCropPercent",
                        current.fisheyeCropPercent()),
                current.vehicleModelId,
                current.gpsOverlayEnabled,
                current.gpsSpeedUnit,
                current.gpsShowCoordinates,
                current.gpsTrackEnabled,
                current.parkingImpactThresholdG,
                current.parkingRecordingSeconds,
                current.parkingAutoLock,
                PhoneJson.booleanValue(json, "telegramEnabled", current.telegramEnabled),
                PhoneJson.stringValue(json, "telegramBotToken", current.telegramBotToken),
                PhoneJson.stringValue(json, "telegramChatId", current.telegramChatId),
                PhoneJson.booleanValue(json, "mqttEnabled", current.mqttEnabled),
                PhoneJson.stringValue(json, "mqttHost", current.mqttHost),
                PhoneJson.intValue(json, "mqttPort", current.mqttPort),
                PhoneJson.stringValue(json, "mqttUsername", current.mqttUsername),
                PhoneJson.stringValue(json, "mqttPassword", current.mqttPassword),
                PhoneJson.stringValue(json, "mqttTopicPrefix", current.mqttTopicPrefix),
                PhoneJson.booleanValue(json, "cloudflareEnabled", current.cloudflareEnabled),
                current.cameraMotionEnabled,
                current.cameraMotionSensitivity,
                current.telemetryEnabled);
        updated.save(this);
        applyRecorderSettings(updated);
        publishSettingsChanged();
        publishState("Settings updated from phone");
    }

    public void setSegmentLockedFromPhone(String segmentId, boolean locked)
            throws IOException {
        RecorderSettings settings = RecorderSettings.load(this);
        StorageRepository.SegmentInfo segment = findSegment(settings, segmentId);
        setSegmentLocked(settings, segment.directory, locked);
    }

    public File resolvePhoneVideo(String segmentId, String fileName)
            throws IOException {
        RecorderSettings settings = RecorderSettings.load(this);
        StorageRepository.SegmentInfo segment = findSegment(settings, segmentId);
        if (segment.active
                || segment.incomplete
                || !RecordingFiles.isVideoName(fileName)) {
            throw new IOException("Only finalized recorder videos are available");
        }
        File file = new File(segment.directory, fileName);
        if (!file.isFile()
                || file.getCanonicalFile().getParentFile() == null
                || !file.getCanonicalFile().getParentFile()
                        .equals(segment.directory.getCanonicalFile())) {
            throw new IOException("Recording file is unavailable");
        }
        return file;
    }

    public byte[] getPhoneSegmentPreviewJpeg(String segmentId)
            throws IOException {
        RecorderSettings settings = RecorderSettings.load(this);
        StorageRepository.SegmentInfo segment = findSegment(settings, segmentId);
        if (segment.active || segment.incomplete) {
            throw new IOException("Recording previews require finalized files");
        }
        String cacheKey = segment.directory.getAbsolutePath();
        synchronized (phoneSegmentPreviewCache) {
            byte[] cached = phoneSegmentPreviewCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        byte[] jpeg =
                SegmentPreviewLoader.loadCombinedStripJpeg(segment.directory);
        if (jpeg == null) {
            SegmentPreviewLoader.Preview preview =
                    SegmentPreviewLoader.load(segment.directory);
            int thumbnailWidth = 120;
            int thumbnailHeight = 90;
            Bitmap strip = Bitmap.createBitmap(
                    thumbnailWidth * FrameProcessor.CAMERA_COUNT,
                    thumbnailHeight,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(strip);
            canvas.drawColor(Color.BLACK);
            for (int index = 0; index < preview.thumbnails.length; index++) {
                Bitmap thumbnail = preview.thumbnails[index];
                if (thumbnail != null) {
                    canvas.drawBitmap(thumbnail, index * thumbnailWidth, 0, null);
                    thumbnail.recycle();
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            strip.compress(Bitmap.CompressFormat.JPEG, 82, output);
            strip.recycle();
            jpeg = output.toByteArray();
        }
        synchronized (phoneSegmentPreviewCache) {
            phoneSegmentPreviewCache.put(cacheKey, jpeg);
        }
        return jpeg;
    }

    public String createPhoneStateJson() throws IOException {
        long startedNanos = System.nanoTime();
        RecorderSettings settings = RecorderSettings.load(this);
        StorageRepository.StorageSnapshot snapshot =
                storageRepository.snapshot(settings);
        List<StorageRepository.SegmentInfo> segments =
                storageRepository.listSegments(
                        settings,
                        segmentRecorder.getActiveDirectory());
        StringBuilder json = new StringBuilder();
        json.append("{\"recording\":")
                .append(mode == Mode.RECORDING)
                .append(",\"recordingActive\":")
                .append(isRecordingActive())
                .append(",\"guardActive\":")
                .append(isParkingGuardActive())
                .append(",\"eventRecording\":")
                .append(mode == Mode.PARKING_RECORDING)
                .append(",\"mode\":")
                .append(PhoneJson.quote(mode.name()))
                .append(",\"statusMessage\":")
                .append(PhoneJson.quote(lastStateMessage))
                .append(",\"backgroundAccessGranted\":")
                .append(BackgroundAccess.isGranted(this))
                .append(",\"backgroundAccessSupported\":")
                .append(BackgroundAccess.isRequestSupported(this))
                .append(",\"message\":")
                .append(PhoneJson.quote(lastStateMessage))
                .append(",\"wifiName\":")
                .append(PhoneJson.quote(PhoneAccessNetwork.getWifiName(this)))
                .append(",\"settings\":{")
                .append("\"volumeIndex\":")
                .append(settings.volumeIndex)
                .append(',')
                .append("\"resolution\":")
                .append(PhoneJson.quote(settings.resolution.id))
                .append(",\"quotaGb\":")
                .append(settings.quotaBytes / (1024.0 * 1024.0 * 1024.0))
                .append(",\"retentionDays\":")
                .append(settings.retentionDays)
                .append(",\"segmentMinutes\":")
                .append(settings.segmentMinutes)
                .append(",\"minFreePercent\":")
                .append(settings.minFreePercent)
                .append(",\"dateFormat\":")
                .append(PhoneJson.quote(settings.dateFormat.id))
                .append(",\"camera1Name\":")
                .append(PhoneJson.quote(settings.cameraName(0)))
                .append(",\"camera2Name\":")
                .append(PhoneJson.quote(settings.cameraName(1)))
                .append(",\"camera3Name\":")
                .append(PhoneJson.quote(settings.cameraName(2)))
                .append(",\"camera4Name\":")
                .append(PhoneJson.quote(settings.cameraName(3)))
                .append(",\"camera1FlipHorizontal\":")
                .append(settings.cameraFlipHorizontal(0))
                .append(",\"camera2FlipHorizontal\":")
                .append(settings.cameraFlipHorizontal(1))
                .append(",\"camera3FlipHorizontal\":")
                .append(settings.cameraFlipHorizontal(2))
                .append(",\"camera4FlipHorizontal\":")
                .append(settings.cameraFlipHorizontal(3))
                .append(",\"camera1FlipVertical\":")
                .append(settings.cameraFlipVertical(0))
                .append(",\"camera2FlipVertical\":")
                .append(settings.cameraFlipVertical(1))
                .append(",\"camera3FlipVertical\":")
                .append(settings.cameraFlipVertical(2))
                .append(",\"camera4FlipVertical\":")
                .append(settings.cameraFlipVertical(3))
                .append(",\"fisheyeCropPercent\":")
                .append(settings.fisheyeCropPercent())
                .append(",\"combinedTopLeft\":")
                .append(settings.combinedCameraIndex(0))
                .append(",\"combinedTopRight\":")
                .append(settings.combinedCameraIndex(1))
                .append(",\"combinedBottomLeft\":")
                .append(settings.combinedCameraIndex(2))
                .append(",\"combinedBottomRight\":")
                .append(settings.combinedCameraIndex(3))
                .append("},\"volumes\":[");
        List<StorageRepository.StorageVolume> volumes =
                storageRepository.getVolumes();
        for (int index = 0; index < volumes.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            StorageRepository.StorageVolume volume = volumes.get(index);
            json.append("{\"index\":")
                    .append(volume.index)
                    .append(",\"label\":")
                    .append(PhoneJson.quote(volume.label))
                    .append('}');
        }
        json.append("],\"storage\":{")
                .append("\"totalBytes\":")
                .append(snapshot.totalBytes)
                .append(",\"availableBytes\":")
                .append(snapshot.availableBytes)
                .append(",\"recorderBytes\":")
                .append(snapshot.recorderBytes)
                .append(",\"lockedBytes\":")
                .append(snapshot.lockedBytes)
                .append("},\"dateFormats\":[");
        DisplayDateFormat[] formats = DisplayDateFormat.values();
        for (int index = 0; index < formats.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":")
                    .append(PhoneJson.quote(formats[index].id))
                    .append(",\"label\":")
                    .append(PhoneJson.quote(formats[index].label))
                    .append('}');
        }
        json.append("],\"segments\":[");
        for (int index = 0; index < segments.size(); index++) {
            StorageRepository.SegmentInfo segment = segments.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append(segmentJson(segment, settings.dateFormat));
        }
        json.append("]}");
        String stateJson = json.toString();
        Log.i(
                TAG,
                "Phone state serialized: segments="
                        + segments.size()
                        + " bytes="
                        + stateJson.length()
                        + " elapsedMs="
                        + ((System.nanoTime() - startedNanos)
                                / 1_000_000L));
        return stateJson;
    }

    public void requestBackgroundAccessFromPhone() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(
                MainActivity.EXTRA_SHOW_BACKGROUND_ACCESS,
                true);
        startActivity(intent);
    }

    public String createPhoneStatusJson() {
        return "{\"recording\":"
                + (mode == Mode.RECORDING)
                + ",\"recordingActive\":"
                + isRecordingActive()
                + ",\"guardActive\":"
                + isParkingGuardActive()
                + ",\"eventRecording\":"
                + (mode == Mode.PARKING_RECORDING)
                + ",\"mode\":"
                + PhoneJson.quote(mode.name())
                + ",\"statusMessage\":"
                + PhoneJson.quote(lastStateMessage)
                + ",\"message\":"
                + PhoneJson.quote(lastStateMessage)
                + ",\"stateVersion\":"
                + uiStateVersion
                + "}";
    }

    /**
     * Compact stitch-progress payload pushed over the finalizing WebSocket:
     * one entry per segment currently being assembled.
     */
    public String createFinalizingProgressJson() {
        StringBuilder json = new StringBuilder("{\"finalizing\":[");
        boolean first = true;
        for (Map.Entry<String, Integer> entry
                : SegmentStitcher.activeProgressByName().entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"id\":")
                    .append(PhoneJson.quote(entry.getKey()))
                    .append(",\"percent\":")
                    .append(entry.getValue())
                    .append('}');
        }
        return json.append("]}").toString();
    }

    private void acquireWakeLock() {
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private boolean canAccessCamera() {
        return FrameSourceFactory.hasRequiredCameraPermissions(this);
    }

    private Notification buildNotification() {
        createNotificationChannel();
        Notification.Builder builder = createNotificationBuilder();
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        String contentText;
        if (mode == Mode.RECORDING) {
            contentText = getString(R.string.notif_recording);
        } else if (mode == Mode.PARKING_STANDBY) {
            contentText = getString(R.string.notif_parking_standby);
        } else if (mode == Mode.PARKING_RECORDING) {
            contentText = getString(R.string.notif_parking_recording);
        } else {
            contentText = getString(R.string.notif_phone_available);
        }
        return builder
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle("BYD Camera Recorder")
                .setContentText(contentText)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            Class<?> channelClass = Class.forName("android.app.NotificationChannel");
            Constructor<?> constructor =
                    channelClass.getConstructor(
                            String.class,
                            CharSequence.class,
                            int.class);
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Method method =
                    NotificationManager.class.getMethod(
                            "createNotificationChannel",
                            channelClass);
            // 녹화 채널 (IMPORTANCE_LOW = 2)
            method.invoke(manager, constructor.newInstance(
                    CHANNEL_ID,
                    "Camera recording",
                    2));
            // 주차 감시 채널 (IMPORTANCE_HIGH = 4)
            method.invoke(manager, constructor.newInstance(
                    PARKING_CHANNEL_ID,
                    "주차 감시",
                    4));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create notification channel", exception);
        }
    }

    private Notification.Builder createNotificationBuilder() {
        if (Build.VERSION.SDK_INT < 26) {
            return new Notification.Builder(this);
        }
        try {
            Constructor<Notification.Builder> constructor =
                    Notification.Builder.class.getConstructor(
                            Context.class,
                            String.class);
            return constructor.newInstance(this, CHANNEL_ID);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create channel notification", exception);
        }
    }

    private String describeMode() {
        switch (mode) {
            case RECORDING:
                return "Recording active";
            case PARKING_STANDBY:
                return "Parking guard active";
            case PARKING_RECORDING:
                return "Parking guard recording";
            case NOT_RECORDING:
            default:
                return "Not recording";
        }
    }

    private boolean isRecordingActive() {
        return mode == Mode.RECORDING || mode == Mode.PARKING_RECORDING;
    }

    private boolean isParkingGuardActive() {
        return mode == Mode.PARKING_STANDBY || mode == Mode.PARKING_RECORDING;
    }

    private void scheduleRecordingStartupTimeout() {
        mainHandler.removeCallbacks(recordingStartupTimeoutRunnable);
        mainHandler.postDelayed(
                recordingStartupTimeoutRunnable,
                FIRST_RECORDING_FRAME_TIMEOUT_MILLIS);
    }

    private void cancelRecordingStartupTimeout() {
        mainHandler.removeCallbacks(recordingStartupTimeoutRunnable);
    }

    private synchronized void onRecordingStartupTimeout() {
        if (!isRecordingActive() || lastRecordedFrameNanos != 0L) {
            return;
        }
        stopRecordingAfterFailure(new IOException(
                "Camera did not deliver recording frames within 20 seconds"));
    }

    private void enterForeground() {
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void sendImpactNotification(float gForce) {
        createNotificationChannel();
        try {
            Intent activityIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    1,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder builder = createParkingNotificationBuilder();
            Notification notification = builder
                    .setSmallIcon(R.drawable.ic_record)
                    .setContentTitle("이벤트 녹화")
                    .setContentText(
                            String.format("%.1f", gForce)
                                    + "G 충격 감지")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_EVENT)
                    .build();
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(PARKING_NOTIFICATION_ID, notification);
            }
        } catch (Exception exception) {
            Log.w(TAG, "Impact notification failed", exception);
        }
    }

    private Notification.Builder createParkingNotificationBuilder() {
        if (Build.VERSION.SDK_INT < 26) {
            return new Notification.Builder(this);
        }
        try {
            Constructor<Notification.Builder> constructor =
                    Notification.Builder.class.getConstructor(
                            Context.class,
                            String.class);
            return constructor.newInstance(this, PARKING_CHANNEL_ID);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create parking notification", exception);
        }
    }

    private void publishState(final String message) {
        Log.i(TAG, message);
        lastStateMessage = message;
        uiStateVersion++;
        mainHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        UiListener listener = uiListener;
                        if (listener != null) {
                            try {
                                listener.onServiceState(mode, message);
                            } catch (RuntimeException exception) {
                                Log.e(
                                        TAG,
                                        "Car state update failed",
                                        exception);
                            }
                        }
                    }
                });
    }

    private void publishSettingsChanged() {
        mainHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        UiListener listener = uiListener;
                        if (listener != null) {
                            try {
                                listener.onRecorderSettingsChanged();
                            } catch (RuntimeException exception) {
                                Log.e(TAG, "Car settings refresh failed", exception);
                            }
                        }
                    }
                });
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private synchronized void shutdown() {
        pendingRecordingSettings = null;
        cancelRecordingStartupTimeout();
        if (segmentRecorder != null) {
            segmentRecorder.stop();
        }
        mode = Mode.NOT_RECORDING;
        if (frameSource != null) {
            frameSource.stop();
        }
        cameraSourceActive = false;
        closePhoneAccessServer();
        releaseWakeLock();
        stopForeground(true);
    }

    private synchronized void stopRecordingAfterFailure(
            Throwable throwable) {
        RecorderSettings settings =
                RecorderSettings.load(this)
                        .withContinuousRecordingEnabled(false);
        settings.save(this);
        pendingRecordingSettings = null;
        cancelRecordingStartupTimeout();
        if (segmentRecorder != null) {
            try {
                segmentRecorder.stop();
            } catch (RuntimeException stopException) {
                Log.e(
                        TAG,
                        "Recording cleanup after failure also failed",
                        stopException);
            }
        }
        mode = Mode.NOT_RECORDING;
        releaseWakeLock();
        applyPhoneAccessSetting(settings);
        String detail = throwable.getMessage();
        publishState(
                "Recording stopped safely: "
                        + (detail == null || detail.isEmpty()
                                ? throwable.getClass().getSimpleName()
                                : detail));
    }

    private String segmentJson(
            StorageRepository.SegmentInfo segment,
            DisplayDateFormat dateFormat) {
        boolean cacheable = !segment.active && !segment.incomplete;
        String key = segment.directory.getAbsolutePath();
        if (cacheable) {
            synchronized (phoneSegmentJsonCache) {
                CachedSegmentJson cached = phoneSegmentJsonCache.get(key);
                if (cached != null
                        && cached.modifiedAtMillis == segment.modifiedAtMillis
                        && cached.locked == segment.locked
                        && cached.dateFormatId.equals(dateFormat.id)) {
                    return cached.json;
                }
            }
        }
        StringBuilder builder = new StringBuilder();
        appendSegmentJson(builder, segment, dateFormat);
        String result = builder.toString();
        if (cacheable) {
            synchronized (phoneSegmentJsonCache) {
                phoneSegmentJsonCache.put(
                        key,
                        new CachedSegmentJson(
                                segment.modifiedAtMillis,
                                segment.locked,
                                dateFormat.id,
                                result));
            }
        }
        return result;
    }

    private void appendSegmentJson(
            StringBuilder json,
            StorageRepository.SegmentInfo segment,
            DisplayDateFormat dateFormat) {
        json.append("{\"id\":")
                .append(PhoneJson.quote(segment.directory.getName()))
                .append(",\"displayName\":")
                .append(PhoneJson.quote(
                        RecorderDateTime.formatSegmentName(
                                segment.directory.getName(),
                                dateFormat)))
                .append(",\"sizeBytes\":")
                .append(segment.sizeBytes)
                .append(",\"locked\":")
                .append(segment.locked)
                .append(",\"active\":")
                .append(segment.active)
                .append(",\"incomplete\":")
                .append(segment.incomplete)
                .append(",\"finalizing\":")
                .append(segment.incomplete
                        && SegmentStitcher.hasParts(segment.directory))
                .append(",\"finalizingPercent\":")
                .append(segment.incomplete
                        ? SegmentStitcher.progressPercent(segment.directory)
                        : -1)
                .append(",\"files\":[");
        boolean firstFile = true;
        if (!segment.active && !segment.incomplete) {
            for (File file :
                    RecordingFiles.listVideos(
                            segment.directory)) {
                if (!firstFile) {
                    json.append(',');
                }
                firstFile = false;
                json.append("{\"name\":")
                        .append(PhoneJson.quote(file.getName()))
                        .append(",\"sizeBytes\":")
                        .append(file.length())
                        .append('}');
            }
        }
        // segment.json에서 이벤트 메타데이터 읽기 (충격/모션 감지 정보)
        String eventType = null;
        float gForce = 0f;
        boolean isPreBuffer = false;
        if (!segment.active) {
            File metaFile = new File(segment.directory, "segment.json");
            if (metaFile.exists()) {
                try {
                    StringBuilder sb = new StringBuilder();
                    java.io.BufferedReader reader =
                            new java.io.BufferedReader(
                                    new java.io.FileReader(metaFile));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    String meta = sb.toString();
                    String rawEventType = PhoneJson.stringValue(meta, "eventType", null);
                    if (rawEventType != null && !rawEventType.equals("null")) {
                        eventType = rawEventType;
                    }
                    gForce = (float) PhoneJson.doubleValue(meta, "gForce", 0.0);
                    isPreBuffer = PhoneJson.booleanValue(meta, "isPreBuffer", false);
                } catch (java.io.IOException ignored) {
                }
            }
        }
        json.append(",\"eventType\":")
                .append(eventType != null ? PhoneJson.quote(eventType) : "null")
                .append(",\"gForce\":")
                .append(Float.isNaN(gForce) || Float.isInfinite(gForce) ? 0.0f : gForce)
                .append(",\"isPreBuffer\":")
                .append(isPreBuffer);
        json.append("]}");
    }

    private void closePhoneAccessServer() {
        if (phoneAccessServer != null) {
            phoneAccessServer.close();
            phoneAccessServer = null;
        }
    }

    private StorageRepository.SegmentInfo findSegment(
            RecorderSettings settings,
            String segmentId) throws IOException {
        for (StorageRepository.SegmentInfo segment :
                storageRepository.listSegments(
                        settings,
                        segmentRecorder.getActiveDirectory())) {
            if (segment.directory.getName().equals(segmentId)) {
                return segment;
            }
        }
        throw new IOException("Recording not found");
    }

    private boolean isPhonePreviewRequested() {
        if (System.currentTimeMillis() - lastPhoneClientMillis
                <= PHONE_PREVIEW_GRACE_MILLIS) {
            return true;
        }
        synchronized (phonePreviewLock) {
            return subscribedMask(phoneCameraSubscriberCounts) != 0
                    || subscribedMask(phoneUncroppedSubscriberCounts) != 0
                    || phoneOneShotCameraMask != 0
                    || phoneOneShotUncroppedMask != 0;
        }
    }

    private void queueLatestPreview(Bitmap[] frames) {
        Bitmap[] replacedFrames;
        boolean shouldPost;
        synchronized (previewDeliveryLock) {
            replacedFrames = pendingPreviewFrames;
            pendingPreviewFrames = frames;
            shouldPost = !previewDeliveryPosted;
            if (shouldPost) {
                previewDeliveryPosted = true;
            }
        }
        releasePreviewFrames(replacedFrames);
        if (shouldPost) {
            mainHandler.post(previewDeliveryRunnable);
        }
    }

    private final Runnable previewDeliveryRunnable =
            new Runnable() {
                @Override
                public void run() {
                    Bitmap[] frames;
                    synchronized (previewDeliveryLock) {
                        frames = pendingPreviewFrames;
                        pendingPreviewFrames = null;
                    }
                    UiListener listener = uiListener;
                    if (frames != null && listener != null) {
                        try {
                            listener.onPreviewFrames(frames);
                        } catch (RuntimeException exception) {
                            Log.e(
                                    TAG,
                                    "Car preview delivery failed; next frame will retry",
                                    exception);
                        }
                    } else {
                        releasePreviewFrames(frames);
                    }
                    synchronized (previewDeliveryLock) {
                        if (pendingPreviewFrames != null) {
                            mainHandler.post(this);
                        } else {
                            previewDeliveryPosted = false;
                        }
                    }
                }
            };

    private void queueLatestPhoneRawFrame(CameraFrame rawFrame) {
        boolean shouldPost;
        synchronized (phonePreviewLock) {
            byte[] target;
            if (phoneRawPending != null
                    && phoneRawPending.length >= rawFrame.dataSize) {
                target = phoneRawPending;
            } else if (phoneRawSpare != null
                    && phoneRawSpare.length >= rawFrame.dataSize) {
                target = phoneRawSpare;
                phoneRawSpare = null;
            } else {
                target = new byte[rawFrame.dataSize];
            }
            System.arraycopy(
                    rawFrame.data,
                    0,
                    target,
                    0,
                    rawFrame.dataSize);
            phoneRawPending = target;
            shouldPost = !phonePreviewWorkPosted;
            if (shouldPost) {
                phonePreviewWorkPosted = true;
            }
        }
        if (shouldPost) {
            phonePreviewExecutor.execute(phonePreviewRunnable);
        }
    }

    private static int subscribedMask(int[] subscriberCounts) {
        int mask = 0;
        for (int index = 0; index < subscriberCounts.length; index++) {
            if (subscriberCounts[index] > 0) {
                mask |= 1 << index;
            }
        }
        return mask;
    }

    private final Runnable phonePreviewRunnable =
            new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        byte[] raw;
                        int cameraMask;
                        int uncroppedMask;
                        synchronized (phonePreviewLock) {
                            if (phoneRawPending == null) {
                                phonePreviewWorkPosted = false;
                                return;
                            }
                            raw = phoneRawPending;
                            phoneRawPending = null;
                            cameraMask =
                                    subscribedMask(phoneCameraSubscriberCounts)
                                            | phoneOneShotCameraMask;
                            uncroppedMask =
                                    subscribedMask(phoneUncroppedSubscriberCounts)
                                            | phoneOneShotUncroppedMask;
                            phoneOneShotCameraMask = 0;
                            phoneOneShotUncroppedMask = 0;
                        }
                        try {
                            encodePhonePreviews(raw, cameraMask, uncroppedMask);
                        } catch (Throwable throwable) {
                            Log.w(
                                    TAG,
                                    "Phone preview JPEG update skipped",
                                    throwable);
                        } finally {
                            synchronized (phonePreviewLock) {
                                if (phoneRawSpare == null
                                        || phoneRawSpare.length < raw.length) {
                                    phoneRawSpare = raw;
                                }
                            }
                        }
                    }
                }
            };

    private void encodePhonePreviews(
            byte[] raw,
            int cameraMask,
            int uncroppedMask) {
        if (cameraMask == 0 && uncroppedMask == 0) {
            return;
        }
        long startedNanos = System.nanoTime();
        Integer cropOverride = previewCropPercentOverride;
        int cropPercent =
                cropOverride != null
                        ? cropOverride
                        : frameProcessor.getFisheyeCropPercent();
        YuvImage image = new YuvImage(
                raw,
                android.graphics.ImageFormat.NV21,
                FrameProcessor.SOURCE_WIDTH,
                FrameProcessor.SOURCE_HEIGHT,
                null);
        int camerasEncoded = 0;
        byte[][] croppedJpegs = null;
        byte[][] uncroppedJpegs = null;
        for (int cameraIndex = 0;
                cameraIndex < FrameProcessor.CAMERA_COUNT;
                cameraIndex++) {
            if ((cameraMask & (1 << cameraIndex)) != 0) {
                byte[] jpeg = compressCameraJpeg(image, cameraIndex, cropPercent);
                if (jpeg != null) {
                    if (croppedJpegs == null) {
                        croppedJpegs = phonePreviewJpegs.clone();
                    }
                    croppedJpegs[cameraIndex] = jpeg;
                    camerasEncoded++;
                }
            }
            if ((uncroppedMask & (1 << cameraIndex)) != 0) {
                byte[] jpeg = compressCameraJpeg(image, cameraIndex, 0);
                if (jpeg != null) {
                    if (uncroppedJpegs == null) {
                        uncroppedJpegs = phoneUncroppedPreviewJpegs.clone();
                    }
                    uncroppedJpegs[cameraIndex] = jpeg;
                    camerasEncoded++;
                }
            }
        }
        synchronized (phonePreviewLock) {
            if (croppedJpegs != null) {
                phonePreviewJpegs = croppedJpegs;
                phonePreviewVersion++;
            }
            if (uncroppedJpegs != null) {
                phoneUncroppedPreviewJpegs = uncroppedJpegs;
                phoneUncroppedPreviewVersion++;
            }
            phonePreviewLock.notifyAll();
        }
        recordPhoneEncodePerformance(
                startedNanos,
                System.nanoTime(),
                camerasEncoded);
    }

    private byte[] compressCameraJpeg(
            YuvImage image,
            int cameraIndex,
            int cropPercent) {
        int cropX = FrameProcessor.cropOffsetX(cropPercent);
        int cropY = FrameProcessor.cropOffsetY(cropPercent);
        int left = cameraIndex * FrameProcessor.SOURCE_CAMERA_WIDTH + cropX;
        Rect region = new Rect(
                left,
                cropY,
                left + FrameProcessor.SOURCE_CAMERA_WIDTH - cropX * 2,
                FrameProcessor.SOURCE_CAMERA_HEIGHT - cropY * 2);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            if (image.compressToJpeg(
                    region,
                    PHONE_PREVIEW_JPEG_QUALITY,
                    output)) {
                return output.toByteArray();
            }
        } catch (RuntimeException exception) {
            Log.w(
                    TAG,
                    "Phone preview camera "
                            + (cameraIndex + 1)
                            + " JPEG skipped",
                    exception);
        }
        return null;
    }

    private void recordPhoneEncodePerformance(
            long startedNanos,
            long completedNanos,
            int camerasEncoded) {
        if (phoneEncodeWindowStartedNanos == 0L) {
            phoneEncodeWindowStartedNanos = startedNanos;
        }
        long elapsed = Math.max(0L, completedNanos - startedNanos);
        phoneEncodeTotalNanos += elapsed;
        phoneEncodeMaximumNanos =
                Math.max(phoneEncodeMaximumNanos, elapsed);
        phoneEncodeFrameCount++;
        phoneEncodeCameraCount += camerasEncoded;
        long windowNanos =
                completedNanos - phoneEncodeWindowStartedNanos;
        if (windowNanos < PERFORMANCE_LOG_INTERVAL_NANOS) {
            return;
        }
        Log.i(
                TAG,
                String.format(
                        java.util.Locale.US,
                        "Phone stream performance: %.1f frame sets/s, "
                                + "%d camera JPEGs, %.1f ms average, "
                                + "%.1f ms maximum per set",
                        phoneEncodeFrameCount
                                * 1_000_000_000.0
                                / windowNanos,
                        phoneEncodeCameraCount,
                        phoneEncodeTotalNanos
                                / (double) phoneEncodeFrameCount
                                / 1_000_000.0,
                        phoneEncodeMaximumNanos / 1_000_000.0));
        phoneEncodeWindowStartedNanos = completedNanos;
        phoneEncodeTotalNanos = 0L;
        phoneEncodeMaximumNanos = 0L;
        phoneEncodeFrameCount = 0;
        phoneEncodeCameraCount = 0;
    }

    private void recordRecordingPerformance(
            long startedNanos,
            long completedNanos) {
        if (recordingWindowStartedNanos == 0L) {
            recordingWindowStartedNanos = startedNanos;
        }
        long elapsed = Math.max(0L, completedNanos - startedNanos);
        recordingTotalNanos += elapsed;
        recordingMaximumNanos =
                Math.max(recordingMaximumNanos, elapsed);
        recordingFrameCount++;
        long windowNanos =
                completedNanos - recordingWindowStartedNanos;
        if (windowNanos < PERFORMANCE_LOG_INTERVAL_NANOS) {
            return;
        }
        Log.i(
                TAG,
                String.format(
                        java.util.Locale.US,
                        "Recording performance: %.1f fps encoded, "
                                + "%.1f ms average, %.1f ms maximum "
                                + "process+encode",
                        recordingFrameCount
                                * 1_000_000_000.0
                                / windowNanos,
                        recordingTotalNanos
                                / (double) recordingFrameCount
                                / 1_000_000.0,
                        recordingMaximumNanos / 1_000_000.0));
        recordingWindowStartedNanos = completedNanos;
        recordingTotalNanos = 0L;
        recordingMaximumNanos = 0L;
        recordingFrameCount = 0;
    }

    private void closePhonePreviewWorker() {
        synchronized (phonePreviewLock) {
            phoneRawPending = null;
            phoneRawSpare = null;
            phoneOneShotCameraMask = 0;
            phoneOneShotUncroppedMask = 0;
            phonePreviewWorkPosted = false;
            phonePreviewLock.notifyAll();
        }
        phonePreviewExecutor.shutdownNow();
    }

    private void recordPreviewPerformance(
            long capturedNanos,
            long completedNanos) {
        if (previewPerformanceWindowStartedNanos == 0L) {
            previewPerformanceWindowStartedNanos = capturedNanos;
        }
        long latencyNanos = Math.max(0L, completedNanos - capturedNanos);
        previewLatencyTotalNanos += latencyNanos;
        previewLatencyMaximumNanos =
                Math.max(previewLatencyMaximumNanos, latencyNanos);
        previewPerformanceFrameCount++;
        long windowNanos =
                completedNanos - previewPerformanceWindowStartedNanos;
        if (windowNanos < 5_000_000_000L) {
            return;
        }
        double seconds = windowNanos / 1_000_000_000.0;
        double framesPerSecond =
                previewPerformanceFrameCount / seconds;
        double averageLatencyMillis =
                previewLatencyTotalNanos
                        / (double) previewPerformanceFrameCount
                        / 1_000_000.0;
        Log.i(
                TAG,
                String.format(
                        java.util.Locale.US,
                        "Preview performance: %.1f fps, %.1f ms average, "
                                + "%.1f ms maximum processing latency",
                        framesPerSecond,
                        averageLatencyMillis,
                        previewLatencyMaximumNanos / 1_000_000.0));
        previewPerformanceWindowStartedNanos = completedNanos;
        previewLatencyTotalNanos = 0L;
        previewLatencyMaximumNanos = 0L;
        previewPerformanceFrameCount = 0;
    }
}
