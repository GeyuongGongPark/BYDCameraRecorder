package com.ggpark.byddashcam;

/**
 * 범용 AVMCamera 프로파일 (폴백).
 * 자동 감지에 실패했거나 사용자가 "알 수 없는 모델"을 선택했을 때 사용.
 * Atto 3와 동일한 기본값이지만 별도 ID로 관리해 수동 선택을 표시할 수 있음.
 */
public final class GenericAvmProfile implements VehicleProfile {
    public static final String MODEL_ID = "generic";

    private final int cameraId;
    private final int viewIndex;
    private final int cameraCount;
    private final int cameraWidth;
    private final int cameraHeight;

    /** 기본 생성자: Atto 3 검증값을 기본으로 사용 */
    public GenericAvmProfile() {
        this(0, 0, 4, 1280, 960);
    }

    public GenericAvmProfile(
            int cameraId,
            int viewIndex,
            int cameraCount,
            int cameraWidth,
            int cameraHeight) {
        this.cameraId = cameraId;
        this.viewIndex = viewIndex;
        this.cameraCount = cameraCount;
        this.cameraWidth = cameraWidth;
        this.cameraHeight = cameraHeight;
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public String displayName() {
        return "알 수 없는 모델 (범용)";
    }

    @Override
    public int avmCameraId() {
        return cameraId;
    }

    @Override
    public int avmViewIndex() {
        return viewIndex;
    }

    @Override
    public int cameraCount() {
        return cameraCount;
    }

    @Override
    public int sourceCameraWidth() {
        return cameraWidth;
    }

    @Override
    public int sourceCameraHeight() {
        return cameraHeight;
    }

    @Override
    public String[] defaultCameraNames() {
        String[] names = new String[cameraCount];
        for (int i = 0; i < cameraCount; i++) {
            names[i] = "Camera " + (i + 1);
        }
        return names;
    }
}
