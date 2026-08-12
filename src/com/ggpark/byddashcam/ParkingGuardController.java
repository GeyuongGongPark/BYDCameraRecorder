package com.ggpark.byddashcam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 주차 감시 상태 머신.
 * STANDBY: ImpactDetector 활성화, 충격 대기.
 * RECORDING: 충격 감지 후 recordingSeconds 동안 녹화.
 *
 * 알림과 세그먼트 잠금은 Callback을 통해 CameraRecorderService가 처리합니다.
 */
public final class ParkingGuardController {
    public interface Callback {
        /** 충격 감지 후 녹화가 시작되어야 할 때 호출됩니다. */
        void onImpactRecordingStarted(float gForce);
        /** recordingSeconds 경과 후 녹화를 멈추고 STANDBY로 복귀할 때 호출됩니다. */
        void onImpactRecordingStopped();
    }

    private enum State { STANDBY, RECORDING }

    private static final String TAG = "BYDCamera";

    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImpactDetector impactDetector = new ImpactDetector();

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
        impactDetector.start(context, settings.impactThresholdG, new ImpactDetector.Listener() {
            @Override
            public void onImpactDetected(float gForce) {
                onImpact(gForce);
            }
        });
        Log.i(TAG, "ParkingGuardController started (threshold="
                + settings.impactThresholdG + "G, duration=" + settings.recordingSeconds + "s)");
    }

    public void stop() {
        handler.removeCallbacks(stopRecordingRunnable);
        impactDetector.stop();
        state = State.STANDBY;
        Log.i(TAG, "ParkingGuardController stopped");
    }

    public void updateSettings(ParkingGuardSettings newSettings) {
        this.settings = newSettings;
        impactDetector.setThreshold(newSettings.impactThresholdG);
    }

    public boolean isRecording() {
        return state == State.RECORDING;
    }

    private void onImpact(float gForce) {
        if (state == State.RECORDING) {
            return;
        }
        state = State.RECORDING;
        callback.onImpactRecordingStarted(gForce);
        handler.postDelayed(stopRecordingRunnable, settings.recordingSeconds * 1000L);
    }

    private void onRecordingTimeout() {
        if (state != State.RECORDING) {
            return;
        }
        state = State.STANDBY;
        Log.i(TAG, "Parking guard recording timeout, returning to standby");
        callback.onImpactRecordingStopped();
    }
}
