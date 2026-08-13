# BYD Camera Recorder

BYD 차량의 내장 AVM 카메라를 활용한 안드로이드 블랙박스 앱입니다.

## 기능

- **4채널 360° 녹화** — 전/후/좌/우 카메라를 동시에 H.264로 인코딩
- **GPS/속도 오버레이** — 영상에 속도·좌표·시각 실시간 합성, GPX 트랙 저장
- **주차 감시 모드** — 가속도 센서로 충격 감지 시 자동 녹화·세그먼트 잠금
- **스마트폰 원격 접속** — 차량 Wi-Fi로 연결 후 브라우저 또는 Flutter 앱에서 영상 확인 및 다운로드
- **세그먼트 자동 관리** — 용량 초과 시 오래된 세그먼트 자동 삭제 (잠금 영상 보호)

## 호환 차종

| 차종 | 상태 |
|------|------|
| BYD 아토 3 (Atto 3) | 검증됨 |
| BYD 씰 (Seal) | 호환 예상 · 미검증 |
| BYD 돌핀 (Dolphin) | 호환 예상 · 미검증 |
| BYD 씨라이언 7 (Sealion 7) | 호환 예상 · 미검증 |

BYD `AVMCamera` API를 사용하는 차량이라면 동작합니다. 새 차종 추가는 `VehicleProfile`을 구현해서 PR을 보내주세요.

## 요구사항

- Android SDK (Build-tools 포함, API 23+)
- JDK 8 이상
- ADB (설치·테스트용)

macOS에서는 Android Studio가 설치되어 있으면 SDK가 자동 감지됩니다 (`~/Library/Android/sdk`).

## 빌드

```bash
bash build.sh
# → build/byd-dashcam-debug.apk
```

### 릴리즈 빌드

```bash
BYD_CAMERA_SIGNING_MODE=release \
BYD_CAMERA_RELEASE_SIGNING_DIR=/path/to/signing \
bash build.sh
```

### Phone UI 포함 빌드

스마트폰 원격 접속 UI를 수정한 경우 먼저 빌드해야 합니다.

```bash
npm --prefix phone-ui install
npm --prefix phone-ui run build
bash build.sh
```

## 설치

```bash
# 에뮬레이터 또는 연결된 기기에 설치
adb install -r build/byd-dashcam-debug.apk

# 권한 사전 부여 (에뮬레이터 테스트 시 편의용)
adb shell pm grant com.ggpark.byddashcam android.permission.CAMERA
adb shell pm grant com.ggpark.byddashcam android.permission.ACCESS_FINE_LOCATION
adb shell dumpsys deviceidle whitelist +com.ggpark.byddashcam
```

## 에뮬레이터 테스트

실제 AVM 카메라가 없는 환경에서는 컬러바 `FixtureFrameSource`가 자동으로 사용됩니다.
앱은 가로(landscape) 모드 전용이므로 에뮬레이터도 가로로 설정해야 합니다.

## 프로젝트 구조

```
src/                  Java 소스
  AvmCameraController   AVM 카메라 HAL 래퍼
  CameraRecorderService 포그라운드 서비스 (녹화 제어)
  FrameProcessor        프레임 처리 및 오버레이 합성
  GpsDataProvider       GPS 데이터 수집
  ImpactDetector        충격 감지 (가속도 센서)
  ParkingGuardController 주차 감시 상태 머신
  PhoneAccessServer     스마트폰 Wi-Fi 접속 서버
  VehicleProfileRegistry 차량 모델 자동 감지
phone-ui/             스마트폰 원격 접속 웹 UI (Vite)
mobile/               스마트폰 앱 (Flutter)
res/                  Android 리소스
assets/               정적 에셋 (phone UI 번들 포함)
stubs/                AVMCamera API 스텁 (컴파일용)
vendor/               bmmcamera.jar (런타임 DEX)
docs/                 랜딩 페이지
build.sh              빌드 스크립트
```

## 라이선스

MIT
