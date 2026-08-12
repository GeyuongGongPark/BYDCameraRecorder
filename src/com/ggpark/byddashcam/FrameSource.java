package com.ggpark.byddashcam;

public interface FrameSource {
    interface Listener {
        void onCameraFrame(CameraFrame frame);
        void onCameraState(String state);
    }

    boolean isRunning();
    void start();
    void stop();
}
