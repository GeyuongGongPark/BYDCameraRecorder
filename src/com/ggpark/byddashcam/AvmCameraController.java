package com.ggpark.byddashcam;

import android.hardware.AVMCamera;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;

import java.util.Locale;

public final class AvmCameraController implements FrameSource {
    private static final long FRAME_METADATA_LOG_INTERVAL_NANOS =
            5_000_000_000L;
    private static final long SOURCE_DIAGNOSTICS_INTERVAL_NANOS =
            5_000_000_000L;
    private static final long RENDER_KICK_MIN_INTERVAL_NANOS =
            30_000_000_000L;
    private static final int MAXIMUM_REQUESTED_FPS = 30;
    private static final String TAG = "BYDCamera";

    // 차량 모델별 설정: 앱 시작 시 VehicleProfileRegistry에서 결정됩니다.
    private final int cameraId;
    private final int viewIndex;
    private final int previewWidth;
    private final int previewHeight;

    private final FrameSource.Listener listener;
    private final SurfaceTexture[] previewTextures =
            new SurfaceTexture[FrameProcessor.CAMERA_COUNT];
    private final Surface[] previewSurfaces =
            new Surface[FrameProcessor.CAMERA_COUNT];
    private final Object pendingLock = new Object();
    private volatile AVMCamera camera;
    private volatile boolean frameSeen;
    private volatile boolean running;
    private long lastFrameMetadataLogNanos;
    private byte[] availableFrameBuffer;
    private CameraFrame pendingFrame;
    private Thread cameraThread;
    private Thread processingThread;
    private long lastRenderKickNanos;
    private long sourceWindowStartedNanos;
    private long sourceCallbackCount;
    private long sourceDroppedCount;

    private final AVMCamera.IEventCallback eventCallback =
            new AVMCamera.IEventCallback() {
                @Override
                public void onEvent(AVMCamera source, int event, int arg1, int arg2) {
                    Log.i(TAG, String.format(
                            Locale.US,
                            "AVM camera event %d (%d, %d)",
                            event,
                            arg1,
                            arg2));
                }
            };

    private final AVMCamera.IPreviewCallback previewCallback =
            new AVMCamera.IPreviewCallback() {
                @Override
                public void onPreview(
                        AVMCamera source,
                        byte[] data,
                        int width,
                        int height,
                        int format,
                        int dataSize,
                        int viewIndex,
                        long timestamp) {
                    if (!running || data == null) {
                        return;
                    }
                    int meaningfulBytes =
                            Math.min(data.length, Math.max(0, dataSize));
                    int requiredBytes =
                            FrameProcessor.SOURCE_WIDTH
                                    * FrameProcessor.SOURCE_HEIGHT
                                    * 3
                                    / 2;
                    if (meaningfulBytes < requiredBytes) {
                        logFrameMetadataIssue(
                                "Dropping undersized AVM frame: dataSize="
                                        + meaningfulBytes
                                        + " required="
                                        + requiredBytes);
                        return;
                    }
                    if (!frameSeen) {
                        Log.i(
                                TAG,
                                "First AVM callback: requested="
                                        + previewWidth
                                        + "x"
                                        + previewHeight
                                        + " deliveredMetadata="
                                        + width
                                        + "x"
                                        + height
                                        + " normalizedPacked="
                                        + FrameProcessor.SOURCE_WIDTH
                                        + "x"
                                        + FrameProcessor.SOURCE_HEIGHT
                                        + " format="
                                        + format
                                        + " dataSize="
                                        + dataSize
                                        + " bufferLength="
                                        + data.length
                                        + " view="
                                        + viewIndex
                                        + " timestamp="
                                        + timestamp);
                    }
                    int normalizedWidth = width;
                    int normalizedHeight = height;
                    int normalizedFormat = format;
                    if (width != FrameProcessor.SOURCE_WIDTH
                            || height != FrameProcessor.SOURCE_HEIGHT
                            || format != FrameProcessor.VENDOR_NV21_FORMAT) {
                        logFrameMetadataIssue(
                                "Normalizing full AVM frame metadata: width="
                                        + width
                                        + " height="
                                        + height
                                        + " format="
                                        + format
                                        + " dataSize="
                                        + meaningfulBytes);
                        normalizedWidth = FrameProcessor.SOURCE_WIDTH;
                        normalizedHeight = FrameProcessor.SOURCE_HEIGHT;
                        normalizedFormat = FrameProcessor.VENDOR_NV21_FORMAT;
                    }
                    if (!frameSeen) {
                        frameSeen = true;
                        publishState("Direct camera frame stream active");
                    }
                    recordSourceDiagnostics();
                    synchronized (pendingLock) {
                        byte[] frameCopy;
                        if (pendingFrame != null) {
                            sourceDroppedCount++;
                        }
                        if (pendingFrame != null
                                && pendingFrame.data.length >= meaningfulBytes) {
                            frameCopy = pendingFrame.data;
                        } else if (availableFrameBuffer != null
                                && availableFrameBuffer.length >= meaningfulBytes) {
                            frameCopy = availableFrameBuffer;
                            availableFrameBuffer = null;
                        } else {
                            frameCopy = new byte[meaningfulBytes];
                        }
                        System.arraycopy(
                                data,
                                0,
                                frameCopy,
                                0,
                                meaningfulBytes);
                        pendingFrame = new CameraFrame(
                                frameCopy,
                                normalizedWidth,
                                normalizedHeight,
                                normalizedFormat,
                                meaningfulBytes,
                                viewIndex,
                                timestamp,
                                System.nanoTime());
                        pendingLock.notifyAll();
                    }
                }
            };

    public AvmCameraController(FrameSource.Listener listener) {
        this.listener = listener;
        VehicleProfile profile = VehicleProfileRegistry.getActive();
        this.cameraId = profile.avmCameraId();
        this.viewIndex = profile.avmViewIndex();
        this.previewWidth = profile.sourceCameraWidth();
        this.previewHeight = profile.sourceCameraHeight();
    }

    public synchronized void attachPreviewTexture(
            SurfaceTexture texture,
            int cameraIndex) {
        if (texture == null
                || cameraIndex < 0
                || cameraIndex >= previewTextures.length) {
            return;
        }
        if (previewTextures[cameraIndex] == texture
                && previewSurfaces[cameraIndex] != null) {
            return;
        }
        releasePreviewSurface(cameraIndex);
        previewTextures[cameraIndex] = texture;
        previewSurfaces[cameraIndex] = new Surface(texture);
        AVMCamera activeCamera = camera;
        if (activeCamera != null) {
            applyPreviewSurface(activeCamera, cameraIndex);
        }
    }

    public synchronized void detachPreviewTexture(
            SurfaceTexture texture,
            int cameraIndex) {
        if (cameraIndex < 0
                || cameraIndex >= previewTextures.length
                || previewTextures[cameraIndex] != texture) {
            return;
        }
        releasePreviewSurface(cameraIndex);
        previewTextures[cameraIndex] = null;
    }

    /**
     * Restarts the vendor preview once, rate limited, when attached preview
     * surfaces stop producing rendered frames. The factory application always
     * registers surfaces before startPreview, so a restart re-runs that
     * ordering without closing the camera. The frame callback pauses briefly
     * during the restart.
     */
    public synchronized boolean kickPreviewRender() {
        AVMCamera activeCamera = camera;
        long nowNanos = System.nanoTime();
        if (activeCamera == null
                || !running
                || (lastRenderKickNanos != 0L
                        && nowNanos - lastRenderKickNanos
                                < RENDER_KICK_MIN_INTERVAL_NANOS)) {
            return false;
        }
        lastRenderKickNanos = nowNanos;
        try {
            boolean stopped = activeCamera.stopPreview();
            boolean started = activeCamera.startPreview();
            Log.w(
                    TAG,
                    "Native preview render kick: stopPreview="
                            + stopped
                            + " startPreview="
                            + started);
            return started;
        } catch (Throwable throwable) {
            Log.e(TAG, "Native preview render kick failed", throwable);
            return false;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        frameSeen = false;
        running = true;
        processingThread = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        processFrames();
                    }
                },
                "byd-frame-delivery");
        processingThread.start();
        cameraThread = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        openCamera();
                    }
                },
                "byd-camera-open");
        cameraThread.start();
    }

    @Override
    public synchronized void stop() {
        if (!running && camera == null) {
            return;
        }
        running = false;
        synchronized (pendingLock) {
            pendingFrame = null;
            pendingLock.notifyAll();
        }
        closeCamera();
        joinQuietly(cameraThread);
        joinQuietly(processingThread);
        cameraThread = null;
        processingThread = null;
        publishState("Camera stopped");
    }

    private void closeCamera() {
        AVMCamera activeCamera = camera;
        camera = null;
        if (activeCamera == null) {
            return;
        }
        try {
            for (int cameraIndex = 0;
                    cameraIndex < previewSurfaces.length;
                    cameraIndex++) {
                Surface surface = previewSurfaces[cameraIndex];
                if (surface != null) {
                    try {
                        activeCamera.rmPreviewSurface(
                                surface,
                                cameraIndex + 1);
                    } catch (RuntimeException exception) {
                        Log.w(
                                TAG,
                                "Preview surface removal failed for camera "
                                        + (cameraIndex + 1),
                                exception);
                    }
                }
            }
            activeCamera.disablePreviewCallback(viewIndex);
            activeCamera.setPreviewCallback(null);
            activeCamera.setEventCallback(null);
            activeCamera.stopPreview();
            activeCamera.close();
        } catch (Throwable throwable) {
            Log.e(TAG, "Camera close failed", throwable);
            publishState("Camera close failed: " + throwable.getMessage());
        }
    }

    private void joinQuietly(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(2000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void logFrameMetadataIssue(String message) {
        long nowNanos = System.nanoTime();
        if (lastFrameMetadataLogNanos == 0L
                || nowNanos - lastFrameMetadataLogNanos
                        >= FRAME_METADATA_LOG_INTERVAL_NANOS) {
            lastFrameMetadataLogNanos = nowNanos;
            Log.w(TAG, message);
        }
    }

    private void recordSourceDiagnostics() {
        long nowNanos = System.nanoTime();
        if (sourceWindowStartedNanos == 0L) {
            sourceWindowStartedNanos = nowNanos;
            sourceCallbackCount = 0L;
            sourceDroppedCount = 0L;
        }
        sourceCallbackCount++;
        long windowNanos = nowNanos - sourceWindowStartedNanos;
        if (windowNanos < SOURCE_DIAGNOSTICS_INTERVAL_NANOS) {
            return;
        }
        Log.i(
                TAG,
                String.format(
                        Locale.US,
                        "AVM source: %.1f callback fps, "
                                + "%d frames replaced before processing",
                        sourceCallbackCount
                                * 1_000_000_000.0
                                / windowNanos,
                        sourceDroppedCount));
        sourceWindowStartedNanos = nowNanos;
        sourceCallbackCount = 0L;
        sourceDroppedCount = 0L;
    }

    private void openCamera() {
        publishState("Opening direct AVM camera");
        try {
            AVMCamera openedCamera = AVMCamera.open(cameraId);
            if (!running) {
                if (openedCamera != null) {
                    openedCamera.close();
                }
                return;
            }
            if (openedCamera == null) {
                publishState("Camera is unavailable; another vehicle app may own it");
                running = false;
                wakeProcessor();
                return;
            }
            camera = openedCamera;
            openedCamera.setEventCallback(eventCallback);
            openedCamera.setPreviewCallback(previewCallback);
            boolean sizeSet = openedCamera.setPreviewSize(previewWidth, previewHeight);
            boolean fpsSet = openedCamera.setCameraFps(MAXIMUM_REQUESTED_FPS);
            int reportedWidth = openedCamera.getPreviewWidth();
            int reportedHeight = openedCamera.getPreviewHeight();
            boolean callbackEnabled = openedCamera.enablePreviewCallback(viewIndex);
            applyPreviewSurfaces(openedCamera);
            boolean previewStarted = openedCamera.startPreview();
            Log.i(
                    TAG,
                    "AVM configuration: requested="
                            + previewWidth
                            + "x"
                            + previewHeight
                            + "@"
                            + MAXIMUM_REQUESTED_FPS
                            + " reported="
                            + reportedWidth
                            + "x"
                            + reportedHeight
                            + " sizeAccepted="
                            + sizeSet
                            + " fpsAccepted="
                            + fpsSet
                            + " callbackEnabled="
                            + callbackEnabled
                            + " previewStarted="
                            + previewStarted);
            publishState(String.format(
                    Locale.US,
                    "Camera commands issued: size=%s fps=%s callback=%s preview=%s; "
                            + "waiting for frame",
                    sizeSet,
                    fpsSet,
                    callbackEnabled,
                    previewStarted));
        } catch (Throwable throwable) {
            Log.e(TAG, "Camera open failed", throwable);
            publishState("Camera open failed: " + throwable.getMessage());
            running = false;
            closeCamera();
            wakeProcessor();
        }
    }

    private synchronized void applyPreviewSurfaces(AVMCamera openedCamera) {
        for (int cameraIndex = 0;
                cameraIndex < previewSurfaces.length;
                cameraIndex++) {
            if (previewSurfaces[cameraIndex] != null) {
                applyPreviewSurface(openedCamera, cameraIndex);
            }
        }
    }

    private void applyPreviewSurface(
            AVMCamera openedCamera,
            int cameraIndex) {
        Surface surface = previewSurfaces[cameraIndex];
        int viewIndex = cameraIndex + 1;
        boolean added = false;
        boolean set = false;
        try {
            // Mirror the factory application's registration order:
            // addPreviewSurface followed by setPreviewSurface per view.
            added = openedCamera.addPreviewSurface(surface, viewIndex);
            set = openedCamera.setPreviewSurface(surface, viewIndex);
        } catch (Throwable throwable) {
            Log.w(
                    TAG,
                    "Preview surface attachment failed for camera "
                            + viewIndex,
                    throwable);
        }
        Log.i(
                TAG,
                "Preview surface: camera="
                        + viewIndex
                        + " added="
                        + added
                        + " set="
                        + set);
    }

    private void releasePreviewSurface(int cameraIndex) {
        Surface surface = previewSurfaces[cameraIndex];
        if (surface == null) {
            return;
        }
        AVMCamera activeCamera = camera;
        if (activeCamera != null) {
            try {
                activeCamera.rmPreviewSurface(surface, cameraIndex + 1);
            } catch (RuntimeException exception) {
                Log.w(
                        TAG,
                        "Preview surface removal failed for camera "
                                + (cameraIndex + 1),
                        exception);
            }
        }
        previewSurfaces[cameraIndex] = null;
        surface.release();
    }

    private void processFrames() {
        while (running) {
            CameraFrame frame;
            synchronized (pendingLock) {
                while (running && pendingFrame == null) {
                    try {
                        pendingLock.wait();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                frame = pendingFrame;
                pendingFrame = null;
            }
            if (frame != null && running) {
                try {
                    listener.onCameraFrame(frame);
                } catch (Throwable throwable) {
                    Log.e(TAG, "Frame listener failed", throwable);
                    publishState("Frame processing failed: " + throwable.getMessage());
                } finally {
                    synchronized (pendingLock) {
                        if (availableFrameBuffer == null
                                || availableFrameBuffer.length
                                        < frame.data.length) {
                            availableFrameBuffer = frame.data;
                        }
                    }
                }
            }
        }
    }

    private void publishState(String state) {
        Log.i(TAG, state);
        listener.onCameraState(state);
    }

    private void wakeProcessor() {
        synchronized (pendingLock) {
            pendingLock.notifyAll();
        }
    }
}
