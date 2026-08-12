package com.ggpark.byddashcam;

import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;

public final class FixtureFrameSource implements FrameSource {
    private static final long FRAME_INTERVAL_MILLIS = 100L;
    private static final int MARKER_WIDTH = 16;
    private static final String TAG = "BYDCamera";
    private static final int[][] RGB_BARS = {
            {235, 235, 235},
            {235, 235, 16},
            {16, 235, 235},
            {16, 235, 16},
            {235, 16, 235},
            {235, 16, 16},
            {16, 16, 235},
            {16, 16, 16}
    };

    private final byte[] baseFrame;
    private final Listener listener;
    private volatile boolean running;
    private Thread worker;

    public FixtureFrameSource(Listener listener) {
        this.listener = listener;
        baseFrame = createColorBars();
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
        running = true;
        worker = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        emitFrames();
                    }
                },
                "byd-fixture-source");
        worker.start();
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        Thread activeWorker = worker;
        worker = null;
        if (activeWorker != null && activeWorker != Thread.currentThread()) {
            activeWorker.interrupt();
            try {
                activeWorker.join(2000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        publishState("Local camera fixture stopped");
    }

    private byte[] createColorBars() {
        int width = FrameProcessor.SOURCE_WIDTH;
        int height = FrameProcessor.SOURCE_HEIGHT;
        int ySize = width * height;
        byte[] frame = new byte[ySize * 3 / 2];

        for (int cameraIndex = 0;
                cameraIndex < FrameProcessor.CAMERA_COUNT;
                cameraIndex++) {
            int cameraStartX = cameraIndex * FrameProcessor.SOURCE_CAMERA_WIDTH;
            for (int localX = 0;
                    localX < FrameProcessor.SOURCE_CAMERA_WIDTH;
                    localX++) {
                int barIndex =
                        localX * RGB_BARS.length / FrameProcessor.SOURCE_CAMERA_WIDTH;
                int[] rgb = RGB_BARS[(barIndex + cameraIndex) % RGB_BARS.length];
                byte y = (byte) rgbToY(rgb[0], rgb[1], rgb[2]);
                int sourceX = cameraStartX + localX;
                for (int row = 0; row < height; row++) {
                    frame[row * width + sourceX] = y;
                }
            }

            for (int row = 0; row < height / 2; row++) {
                int rowOffset = ySize + row * width + cameraStartX;
                for (int localX = 0;
                        localX < FrameProcessor.SOURCE_CAMERA_WIDTH;
                        localX += 2) {
                    int barIndex =
                            localX * RGB_BARS.length
                                    / FrameProcessor.SOURCE_CAMERA_WIDTH;
                    int[] rgb = RGB_BARS[
                            (barIndex + cameraIndex) % RGB_BARS.length];
                    frame[rowOffset + localX] =
                            (byte) rgbToV(rgb[0], rgb[1], rgb[2]);
                    frame[rowOffset + localX + 1] =
                            (byte) rgbToU(rgb[0], rgb[1], rgb[2]);
                }
            }
        }
        return frame;
    }

    private void drawMovingMarkers(byte[] frame, long frameIndex) {
        int width = FrameProcessor.SOURCE_WIDTH;
        int height = FrameProcessor.SOURCE_HEIGHT;
        int ySize = width * height;
        int markerX =
                (int) ((frameIndex * MARKER_WIDTH)
                        % FrameProcessor.SOURCE_CAMERA_WIDTH);
        for (int cameraIndex = 0;
                cameraIndex < FrameProcessor.CAMERA_COUNT;
                cameraIndex++) {
            int startX =
                    cameraIndex * FrameProcessor.SOURCE_CAMERA_WIDTH + markerX;
            int endX = Math.min(
                    startX + MARKER_WIDTH,
                    (cameraIndex + 1) * FrameProcessor.SOURCE_CAMERA_WIDTH);
            for (int row = 0; row < height; row++) {
                Arrays.fill(
                        frame,
                        row * width + startX,
                        row * width + endX,
                        (byte) 235);
            }
            int evenStartX = startX & ~1;
            int evenEndX = Math.min((endX + 1) & ~1, width);
            for (int row = 0; row < height / 2; row++) {
                int rowOffset = ySize + row * width;
                for (int x = evenStartX; x < evenEndX; x += 2) {
                    frame[rowOffset + x] = (byte) 128;
                    frame[rowOffset + x + 1] = (byte) 128;
                }
            }
        }
    }

    private void emitFrames() {
        Log.i(TAG, "Local generated NV21 camera fixture active");
        listener.onCameraState("Preview active");
        long frameIndex = 0L;
        while (running) {
            long monotonicNanos = SystemClock.elapsedRealtimeNanos();
            byte[] frame = Arrays.copyOf(baseFrame, baseFrame.length);
            drawMovingMarkers(frame, frameIndex);
            listener.onCameraFrame(new CameraFrame(
                    frame,
                    FrameProcessor.SOURCE_WIDTH,
                    FrameProcessor.SOURCE_HEIGHT,
                    FrameProcessor.VENDOR_NV21_FORMAT,
                    frame.length,
                    0,
                    frameIndex,
                    monotonicNanos));
            frameIndex++;
            try {
                Thread.sleep(FRAME_INTERVAL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable throwable) {
                Log.e(TAG, "Fixture frame delivery failed", throwable);
                publishState("Local camera fixture failed: " + throwable.getMessage());
                running = false;
            }
        }
    }

    private int rgbToU(int red, int green, int blue) {
        return clamp(((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128);
    }

    private int rgbToV(int red, int green, int blue) {
        return clamp(((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128);
    }

    private int rgbToY(int red, int green, int blue) {
        return clamp(((66 * red + 129 * green + 25 * blue + 128) >> 8) + 16);
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void publishState(String state) {
        Log.i(TAG, state);
        listener.onCameraState(state);
    }
}
