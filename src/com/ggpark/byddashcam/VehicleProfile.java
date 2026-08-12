package com.ggpark.byddashcam;

/**
 * 차량 모델별 AVMCamera 설정 프로파일.
 * 모델마다 다를 수 있는 카메라 ID, 뷰 인덱스, 해상도 등을 추상화합니다.
 */
public interface VehicleProfile {
    /** 설정 저장에 사용하는 고유 식별자 (예: "atto3") */
    String modelId();

    /** UI에 표시할 모델명 (예: "BYD Atto 3") */
    String displayName();

    /** AVMCamera.open()에 전달할 카메라 ID */
    int avmCameraId();

    /** enablePreviewCallback()에 전달할 뷰 인덱스 */
    int avmViewIndex();

    /** 카메라 개수 */
    int cameraCount();

    /** 단일 카메라의 소스 너비 (픽셀) */
    int sourceCameraWidth();

    /** 단일 카메라의 소스 높이 (픽셀) */
    int sourceCameraHeight();

    /** 카메라별 기본 이름 배열 (cameraCount() 길이와 일치해야 함) */
    String[] defaultCameraNames();
}
