package com.ggpark.byddashcam;

/**
 * BYD Atto 3 카메라 프로파일.
 * 참조 앱(GHDanielG)에서 검증된 값 기반.
 */
public final class Atto3Profile implements VehicleProfile {
    public static final String MODEL_ID = "atto3";

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public String displayName() {
        return "BYD Atto 3";
    }

    @Override
    public int avmCameraId() {
        return 0;
    }

    @Override
    public int avmViewIndex() {
        return 0;
    }

    @Override
    public int cameraCount() {
        return 4;
    }

    @Override
    public int sourceCameraWidth() {
        return 1280;
    }

    @Override
    public int sourceCameraHeight() {
        return 960;
    }

    @Override
    public String[] defaultCameraNames() {
        return new String[]{"Front", "Rear", "Left", "Right"};
    }
}
