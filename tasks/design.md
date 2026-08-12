# 설계 상세 스펙

## 참조 앱 핵심 구조 요약

```
AVMCamera.open(cameraId=0)
  └─ enablePreviewCallback(viewIndex=0)
      └─ IPreviewCallback.onPreview()
          └─ 데이터: NV21, width=5120, height=960 (4카메라 가로 연결)
              └─ FrameProcessor: 5120→4×1280 분리
                  └─ AvcMp4Encoder: 카메라별 H.264 MP4 + 합성 MP4
```

- `FrameSourceFactory`: 에뮬레이터 → FixtureFrameSource, 실기기 → AvmCameraController
- `CameraRecorderService`: 포그라운드 서비스, WakeLock, SegmentRecorder 관리
- `RecorderSettings`: SharedPreferences 기반 설정 저장소

---

## Phase 1: 모델 지원 확대

### 문제
`AvmCameraController`에 고정값 하드코딩:
- `CAMERA_ID = 0` → `AVMCamera.open(0)`
- `VIEW_INDEX = 0` → `enablePreviewCallback(0)`
- `PREVIEW_WIDTH = 1280`, `PREVIEW_HEIGHT = 960`
- `FrameProcessor.SOURCE_CAMERA_WIDTH = 1280`, `SOURCE_CAMERA_HEIGHT = 960`

### VehicleProfile interface

```java
public interface VehicleProfile {
    String modelId();               // "atto3", "seal", "han", "tang" 등
    String displayName();           // "BYD Atto 3"
    int avmCameraId();              // AVMCamera.open()에 넘길 ID
    int avmViewIndex();             // enablePreviewCallback() viewIndex
    int cameraCount();              // 카메라 개수 (현재 모두 4)
    int sourceCameraWidth();        // 단일 카메라 너비
    int sourceCameraHeight();       // 단일 카메라 높이
    String[] defaultCameraNames();  // ["Front", "Rear", "Left", "Right"]
}
```

### 구현체

| 클래스 | 설명 |
|--------|------|
| `Atto3Profile` | CAMERA_ID=0, VIEW_INDEX=0, 1280×960 |
| `GenericAvmProfile` | Atto3와 동일 기본값, 수동 설정 허용 |

### VehicleProfileRegistry - 자동 감지 로직

```
감지 우선순위:
1. 사용자 저장 설정 (RecorderSettings.vehicleModelId)
2. Build.DEVICE / Build.MODEL 키워드 매칭
   - "atto3", "atto 3" → Atto3Profile
   - (향후 커뮤니티 기여로 확장)
3. AVMCamera.open() 탐색 (0→1→2 순서 시도, 성공한 ID 사용)
4. 최종 fallback: GenericAvmProfile (Atto3 기본값)
```

### FrameProcessor 수정

- `CAMERA_COUNT`, `SOURCE_CAMERA_WIDTH`, `SOURCE_CAMERA_HEIGHT` 상수 제거
- `VehicleProfile`에서 런타임 주입
- `SOURCE_WIDTH = profile.sourceCameraWidth() * profile.cameraCount()`

### 설정 추가

- `KEY_VEHICLE_MODEL_ID`: 선택된 모델 ID 저장
- 설정 화면: 모델 선택 드롭다운 (알려진 모델 목록 + "자동 감지")
- 모델 감지 실패 시: ConfirmationDialog로 수동 선택 유도

---

## Phase 2: GPS/속도 오버레이

### 데이터 흐름

```
LocationManager (GPS_PROVIDER)
  └─ GpsDataProvider (1초 업데이트, 마지막 fix 캐싱)
      └─ GpsOverlay (속도, lat/lon, 타임스탬프 → Bitmap)
          └─ FrameProcessor.processFrame()에서 합성
              └─ GpxTrackWriter (세그먼트별 .gpx 파일)
```

### GpsDataProvider

```java
// LocationListener 구현
// minTime=1000ms, minDistance=0m
// 마지막 GpsFix 객체 캐싱 (신호 없어도 마지막 값 사용)
// fix 나이 표시: 5초 초과 시 "오래된 GPS" 표시

class GpsFix {
    double speedKmh;    // m/s → km/h 변환
    double latitude;
    double longitude;
    double altitude;
    long fixTimeMs;
    boolean fresh;      // 5초 이내 fix 여부
}
```

### GpsOverlayRenderer

```java
// 오버레이 Bitmap을 캐싱 (속도/위치 변화 시에만 재렌더링)
// 변화 감지: 속도 1km/h 단위, 위치 소수점 4자리
// Paint 설정: MONOSPACE, 흰색 텍스트 + 검정 그림자 (가시성)
// 크기: 각 카메라 프레임의 우하단 영역 (~200×60px)

// 표시 형식 (설정에 따라 조합):
// "  85 km/h"               ← 항상 표시
// "37.1234 N  127.5678 E"  ← 선택적
// "2025-08-12 14:30:22"    ← 선택적
```

### FrameProcessor 수정

```java
// processFrame() 내부에서 카메라별 크롭 후 오버레이 합성
// NV21 → Bitmap → Canvas.drawBitmap(overlay) → NV21 변환
// 오버레이 비활성화 시 변환 과정 전체 스킵 (성능)
```

### GpxTrackWriter

```
파일 위치: [세그먼트 디렉토리]/gps.gpx
형식:
  <gpx>
    <trk><trkseg>
      <trkpt lat="..." lon="...">
        <ele>...</ele>
        <time>...</time>
        <extensions><speed>...</speed></extensions>
      </trkpt>
      ...
    </trkseg></trk>
  </gpx>

- 세그먼트 시작 시 헤더 작성
- 1초마다 <trkpt> append (BufferedWriter flush)
- 세그먼트 종료 시 닫기 태그 작성
- GPS fix 없는 구간은 기록 건너뜀
```

### 설정 추가

| 키 | 타입 | 기본값 | 설명 |
|----|------|--------|------|
| `gps_overlay_enabled` | boolean | true | 오버레이 ON/OFF |
| `gps_overlay_position` | String | "bottom_right" | 위치 (4코너) |
| `gps_speed_unit` | String | "kmh" | km/h 또는 mph |
| `gps_show_coordinates` | boolean | false | 좌표 표시 여부 |
| `gps_show_timestamp` | boolean | false | 시간 표시 여부 |
| `gps_track_enabled` | boolean | true | GPX 파일 저장 |

---

## Phase 3: 주차 감시 모드

### 상태 머신

```
[IDLE / 일반 녹화]
    ↓ 사용자가 주차 모드 활성화
[PARKING_GUARD_STANDBY]
    - 카메라 프리뷰 Surface 없음 (프레임 수신만)
    - ImpactDetector 활성화
    - PARTIAL_WAKE_LOCK 유지
    ↓ 충격 감지 (G-force > threshold)
[PARKING_GUARD_RECORDING]
    - 즉시 녹화 시작
    - 세그먼트 자동 잠금
    - 충격 감지 알림 발송
    ↓ 녹화 시간 초과 (기본 120초)
[PARKING_GUARD_STANDBY]  ← 다시 대기
    ↓ 사용자가 주차 모드 해제 또는 차량 시동
[IDLE]
```

### ImpactDetector

```java
// SensorManager.getDefaultSensor(TYPE_ACCELEROMETER)
// 샘플링: SENSOR_DELAY_NORMAL (약 5Hz, 저전력)
// G-force 계산: magnitude = sqrt(x²+y²+z²)
//               gForce = magnitude / SensorManager.GRAVITY_EARTH
// 데바운스: 마지막 트리거 후 MIN_RETRIGGER_SECONDS(30) 이내 재트리거 방지
// 임계값: 기본 2.5G (설정: 1.5G ~ 5.0G, 0.5G 단위)

interface Listener {
    void onImpactDetected(float gForce);
}
```

### ParkingGuardController

```java
// CameraRecorderService 내부에서 생성/관리
// 상태 전환 책임 (STANDBY ↔ RECORDING)
// 충격 감지 시:
//   1. SegmentRecorder.startNewSegment() (현재 세그먼트 종료 + 새 세그먼트 시작)
//   2. 새 세그먼트 즉시 잠금
//   3. Notification 발송: "충격 감지됨 - 녹화 시작"
//   4. Handler.postDelayed(stopRecording, recordingDurationMs)
```

### 저전력 전략

- **STANDBY 상태**: 카메라 프리뷰 Bitmap 생성 안 함 (`carBitmapPreviewRequired = false`)
- **SensorManager 샘플링**: `SENSOR_DELAY_NORMAL` (5Hz) → 배터리 부담 최소
- **WakeLock**: `PARTIAL_WAKE_LOCK` (화면 OFF 가능, CPU만 유지)
- **카메라 유지 여부**: `AVMCamera` 연결 유지 vs 해제 후 충격 감지 시 재연결
  - 결정: **유지** (재연결 시간 ~3초로 충격 직후 녹화 불가 → 대기 중에도 카메라 열어둠)

### 알림 설계

```
채널: "parking_guard" (기존 recording 채널과 분리)
아이콘: 방패 아이콘
STANDBY 알림: "주차 감시 중 - 충격 감지 대기"
RECORDING 알림: "충격 감지! 녹화 중 (X초 후 종료)"
```

### 설정 추가

| 키 | 타입 | 기본값 | 설명 |
|----|------|--------|------|
| `parking_impact_threshold_g` | float | 2.5 | 충격 임계값 (G) |
| `parking_recording_seconds` | int | 120 | 충격 후 녹화 시간 (초) |
| `parking_auto_lock` | boolean | true | 충격 세그먼트 자동 잠금 |

---

## 신규 클래스 목록

### Phase 1 (모델 지원)
- `VehicleProfile.java` - interface
- `Atto3Profile.java` - 구현체
- `GenericAvmProfile.java` - fallback 구현체
- `VehicleProfileRegistry.java` - 감지 + 등록

### Phase 2 (GPS 오버레이)
- `GpsFix.java` - GPS 데이터 모델
- `GpsDataProvider.java` - LocationManager 래퍼
- `GpsOverlayRenderer.java` - 오버레이 Bitmap 생성
- `GpxTrackWriter.java` - GPX 파일 저장

### Phase 3 (주차 감시)
- `ImpactDetector.java` - 가속도계 충격 감지
- `ParkingGuardController.java` - 상태 머신
- `ParkingGuardSettings.java` - 주차 감시 설정 모델

### 수정 대상 (기존 파일)
- `AvmCameraController.java` - VehicleProfile 주입
- `FrameProcessor.java` - 상수 제거, profile 기반, 오버레이 훅
- `FrameSourceFactory.java` - profile 전달
- `CameraRecorderService.java` - 주차 모드 상태, GPS 연동
- `RecorderSettings.java` - 신규 설정 키 추가
- `AndroidManifest.xml` - 위치 권한, 주차 알림 채널
