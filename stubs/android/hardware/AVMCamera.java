package android.hardware;

import android.graphics.SurfaceTexture;
import android.view.Surface;

public final class AVMCamera {
    public static final int VIEW_DEFAULT = 0;
    public static final int VIEW_CHANNEL_1 = 1;
    public static final int VIEW_CHANNEL_2 = 2;
    public static final int VIEW_CHANNEL_3 = 3;
    public static final int VIEW_CHANNEL_4 = 4;
    public static final int VIEW_DECUSSATION = 5;
    public static final int VIEW_DECUSSATION_HFLIP = 6;
    public static final int VIEW_DECUSSATION_VFLIP = 7;

    public interface IEventCallback {
        void onEvent(AVMCamera camera, int event, int arg1, int arg2);
    }

    public interface IPreviewCallback {
        void onPreview(
                AVMCamera camera,
                byte[] data,
                int width,
                int height,
                int format,
                int dataSize,
                int viewIndex,
                long timestamp);
    }

    public static AVMCamera open(int cameraId) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public void close() {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public boolean disablePreviewCallback(int viewIndex) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public boolean enablePreviewCallback(int viewIndex) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public int getPreviewHeight() {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public int getPreviewWidth() {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public boolean setCameraFps(int framesPerSecond) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public boolean rmTexture(SurfaceTexture texture, int viewIndex) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public void setEventCallback(IEventCallback callback) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public void setPreviewCallback(IPreviewCallback callback) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public boolean setTexture(SurfaceTexture texture, int viewIndex) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public boolean addPreviewSurface(Surface surface, int viewIndex) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public boolean setPreviewSurface(Surface surface, int viewIndex) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public boolean rmPreviewSurface(Surface surface, int viewIndex) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public boolean setPreviewSize(int width, int height) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public boolean startPreview() {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public boolean stopPreview() {
        throw new UnsupportedOperationException("compile-time stub");
    }
}
