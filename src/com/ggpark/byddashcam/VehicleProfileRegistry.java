package com.ggpark.byddashcam;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 차량 모델 프로파일 레지스트리.
 *
 * <p>우선순위:
 * <ol>
 *   <li>RecorderSettings에 저장된 사용자 수동 선택</li>
 *   <li>Build.DEVICE / Build.MODEL 키워드 자동 매핑</li>
 *   <li>폴백: Atto 3 (가장 많이 검증된 모델)</li>
 * </ol>
 */
public final class VehicleProfileRegistry {
    private static final String TAG = "BYDCamera";

    /** 앱 전체에서 사용할 활성 프로파일 (앱 시작 시 한 번 결정) */
    private static volatile VehicleProfile activeProfile;

    /** 지원하는 모든 프로파일 목록 (UI 선택 드롭다운용) */
    public static final List<VehicleProfile> ALL_PROFILES = Arrays.asList(
            new Atto3Profile(),
            new GenericAvmProfile()
    );

    private VehicleProfileRegistry() {
    }

    /**
     * 컨텍스트와 저장된 설정을 기반으로 프로파일을 결정하고 활성화합니다.
     * CameraRecorderService.onCreate() 또는 FrameSourceFactory.create() 초기화 시점에 호출.
     */
    public static VehicleProfile detectAndActivate(Context context) {
        RecorderSettings settings = RecorderSettings.load(context);
        VehicleProfile profile = resolve(settings.vehicleModelId);
        activate(profile);
        return profile;
    }

    /** 현재 활성 프로파일을 반환. detectAndActivate() 전에 호출하면 Atto3 기본값 반환. */
    public static VehicleProfile getActive() {
        if (activeProfile == null) {
            return new Atto3Profile();
        }
        return activeProfile;
    }

    /** 프로파일을 활성화하고 FrameProcessor 상수를 초기화합니다. */
    public static void activate(VehicleProfile profile) {
        activeProfile = profile;
        FrameProcessor.init(profile);
        Log.i(TAG, "Vehicle profile activated: " + profile.displayName()
                + " (cameraId=" + profile.avmCameraId()
                + " viewIndex=" + profile.avmViewIndex()
                + " resolution=" + profile.sourceCameraWidth()
                + "x" + profile.sourceCameraHeight()
                + " cameras=" + profile.cameraCount() + ")");
    }

    /**
     * modelId 문자열로 프로파일을 찾습니다.
     * 알 수 없는 ID이면 자동 감지를 시도하고, 실패하면 Atto3 폴백.
     */
    public static VehicleProfile resolve(String modelId) {
        if (modelId != null && !modelId.isEmpty()) {
            for (VehicleProfile profile : ALL_PROFILES) {
                if (profile.modelId().equals(modelId)) {
                    Log.i(TAG, "Vehicle profile from settings: " + profile.displayName());
                    return profile;
                }
            }
        }
        return detectFromBuild();
    }

    /**
     * Build.DEVICE / Build.MODEL 키워드로 모델을 자동 감지합니다.
     */
    private static VehicleProfile detectFromBuild() {
        String device = lower(Build.DEVICE);
        String model = lower(Build.MODEL);
        String product = lower(Build.PRODUCT);

        Log.i(TAG, "Vehicle auto-detect: DEVICE=" + Build.DEVICE
                + " MODEL=" + Build.MODEL
                + " PRODUCT=" + Build.PRODUCT);

        if (matchesAny(device, model, product,
                "atto3", "atto_3", "atto 3", "byd_atto")) {
            Log.i(TAG, "Auto-detected: BYD Atto 3");
            return new Atto3Profile();
        }

        // 향후 모델 추가 예시:
        // if (matchesAny(device, model, product, "seal", "byd_seal")) {
        //     return new SealProfile();
        // }

        Log.w(TAG, "Vehicle model not recognized, using Atto 3 defaults as fallback");
        return new Atto3Profile();
    }

    private static boolean matchesAny(
            String device, String model, String product,
            String... keywords) {
        for (String keyword : keywords) {
            if (device.contains(keyword)
                    || model.contains(keyword)
                    || product.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }
}
