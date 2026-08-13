package com.ggpark.byddashcam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 주차 감시 상태 머신.
 * STANDBY: ImpactDetector + CameraMotionDetector 활성화, 이벤트 대기.
 * RECORDING: 충격/모션 감지 후 recordingSeconds 동안 녹화.
 *
 * 알림과 세그먼트 잠금은 Callback을 통해 CameraRecorderService가 처리합니다.
 */
public final class ParkingGuardController {
    public interface Callback {
        /** 충격 감지 후 녹화가 시작되어야 할 때 호출됩니다. */
        void onImpactRecordingStarted(float gForce);
        /** 카메라 모션 감지 후 녹화가 시작되어야 할 때 호출됩니다. */
        void onMotionRecordingStarted();
        /** recordingSeconds 경과 후 녹화를 멈추고 STANDBY로 복귀할 때 호출됩니다. */
        void onImpactRecordingStopped();
    }

    private enum State { STANDBY, RECORDING }

    private static final String TAG = "BYDCamera";

    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImpactDetector impactDetector = new ImpactDetector();
    private final CameraMotionDetector motionDetector = new CameraMotionDetector();

    private volatile State state = State.STANDBY;
    private ParkingGuardSettings settings;

    private final Runnable stopRecordingRunnable = new Runnable() {
        @Override
        public void run() {
            onRecordingTimeout();
        }
    };

    public ParkingGuardController(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    public void start(ParkingGuardSettings settings) {
        this.settings = settings;
        state = State.STANDBY;
        motionDetector.reset();
        motionDetector.setSensitivity(settings.cameraMotionSensitivity);
        impactDetector.start(context, settings.impactThresholdG, new ImpactDetector.Listener() {
            @Override
            public void onImpactDetected(float gForce) {
                onImpact(gForce);
            }
        });
        Log.i(TAG, "ParkingGuardController started (threshold="
                + settings.impactThresholdG + "G, duration=" + settings.recordingSeconds
                + "s, motionDetection=" + settings.cameraMotionEnabled + ")");
    }

    public void stop() {
        handler.removeCallbacks(stopRecordingRunnable);
        impactDetector.stop();
        motionDetector.reset();
        state = State.STANDBY;
        Log.i(TAG, "ParkingGuardController stopped");
    }

    public void updateSettings(ParkingGuardSettings newSettings) {
        this.settings = newSettings;
        impactDetector.setThreshold(newSettings.impactThresholdG);
        motionDetector.setSensitivity(newSettings.cameraMotionSensitivity);
    }

    public boolean isRecording() {
        return state == State.RECORDING;
    }

    /**
     * PARKING_STANDBY 상태에서 카메라 프레임을 제출합니다.
     * 모션 감지가 활성화된 경우에만 동작합니다.
     * 이 메서드는 카메라 프레임 스레드에서 호출될 수 있으므로 빠르게 반환해야 합니다.
     */
    public void offerCameraFrame(byte[] data, int width, int height) {
        if (!settings.cameraMotionEnabled || state == State.RECORDING) {
            return;
        }
        if (motionDetector.detect(data, width, height)) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    onMotionDetected();
                }
            });
        }
    }

    private void onImpact(float gForce) {
        if (state == State.RECORDING) {
            return;
        }
        state = State.RECORDING;
        Log.i(TAG, "Parking impact detected: " + gForce + "G");
        callback.onImpactRecordingStarted(gForce);
        handler.postDelayed(stopRecordingRunnable, settings.recordingSeconds * 1000L);
    }

    private void onMotionDetected() {
        if (state == State.RECORDING) {
            return;
        }
        state = State.RECORDING;
        Log.i(TAG, "Parking camera motion detected - starting recording");
        callback.onMotionRecordingStarted();
        handler.postDelayed(stopRecordingRunnable, settings.recordingSeconds * 1000L);
    }

    private void onRecordingTimeout() {
        if (state != State.RECORDING) {
            return;
        }
        state = State.STANDBY;
        motionDetector.reset();
        Log.i(TAG, "Parking guard recording timeout, returning to standby");
        callback.onImpactRecordingStopped();
    }
}
