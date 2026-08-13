# BYD Camera Recorder - 작업 계획

## Phase 0: 프로젝트 기반 구축
- [x] 참조 앱(GHDanielG) 소스 전체 복사 + 패키지명/앱명 변경
- [x] 빌드 환경 구성 (macOS Android SDK 자동 감지, d8 지원, bash 3.2 호환)
- [x] 기존 기능 회귀 없이 빌드 통과 확인 (build/byd-dashcam-debug.apk)
- [ ] 에뮬레이터에서 FixtureFrameSource 정상 동작 확인

## Phase 1: 모델 지원 확대
- [x] `VehicleProfile` interface + `Atto3Profile` + `GenericAvmProfile` + `VehicleProfileRegistry`
- [x] `AvmCameraController` / `FrameProcessor` 리팩터링 (VehicleProfile 주입)
- [x] `RecorderSettings`에 vehicleModelId 저장
- [x] 설정 화면에 모델 수동 선택 UI (VehicleProfileAdapter Spinner)
- [ ] 모델 감지 실패 시 선택 다이얼로그 표시 (선택적 — 현재 Atto3 폴백으로 동작)

## Phase 2: GPS/속도 오버레이
- [x] `AndroidManifest.xml`에 `ACCESS_FINE_LOCATION` 권한 추가
- [x] `GpsDataProvider` 작성 (LocationManager 래퍼, 1초 업데이트)
- [x] `GpsOverlayRenderer` 작성 (Paint 캐싱, 오버레이 Bitmap 생성)
- [x] `FrameProcessor`에 오버레이 합성 훅 추가
- [x] `GpxTrackWriter` 작성 (세그먼트별 .gpx 파일)
- [x] `SegmentRecorder`에 GpxTrackWriter 연동
- [x] 설정 UI: GPS 오버레이 ON/OFF, 속도 단위(km/h·mph), 좌표 표시, GPX 저장 토글

## Phase 3: 주차 감시 모드
- [x] `ImpactDetector` 작성 (TYPE_ACCELEROMETER, G-force, 데바운스)
- [x] `ParkingGuardController` 작성 (상태 머신 + 모션 감지 콜백)
- [x] `CameraRecorderService` 주차 모드 진입/해제 + 충격/모션 이벤트 파이프라인
- [x] 충격/모션 감지 → 녹화 시작 → 세그먼트 자동 잠금
- [x] 주차 모드 UI (메인 화면 토글 버튼)
- [x] `RecorderSettings` 충격 임계값/녹화 시간/자동잠금/모션 감도 설정 키
- [x] **이벤트 전 프리버퍼**: 파일 기반 슬라이딩 윈도우 (12초 단위, 충격/모션 시 직전 잠금)
- [x] **주차 UX 정리**: parkingAutoLock 체크박스 UI, 알림 문자열 리소스화
- [x] **카메라 모션 감지**: `CameraMotionDetector` (16×16 블록 다운샘플링, sensitivity 1~5)
- [x] **segment.json 이벤트 메타**: `eventType`, `gForce`, `isPreBuffer` 기록·파싱
- [x] **충격 알림 채널 분리**: `PARKING_CHANNEL_ID` 분리 (`byd_parking_guard`)
- [x] **주차 감시 설정 UI**: 임계값 스텝퍼 (1.5~5.0G), 녹화 시간 스텝퍼 (30~300s)

## Phase 4: 인앱 언어 선택
- [x] `LocaleHelper.java` 작성 (SharedPreferences 기반, ContextWrapper 방식)
- [x] `MainActivity.java` `attachBaseContext()` 추가
- [x] `res/values/strings.xml` 영어 문자열 추가 (~100개)
- [x] `res/values-ko/strings.xml` 한국어 번역 추가
- [x] 설정 UI 최상단에 Language / 언어 토글 (English / 한국어)

## Phase 5: 폰앱 이벤트 뷰
- [x] FilesScreen 전체/이벤트 탭
- [x] 이벤트 카드 시각적 강조 (오렌지·파란 테두리, ⚡/🏃 아이콘, G-force 표시)

## Phase 6: 외부 연동
- [x] Telegram 충격/모션 이벤트 알림
- [x] MQTT (Home Assistant Discovery)
- [x] Cloudflare 터널 (폰앱 외부 접근)
- [x] 폰앱 (Flutter iOS/Android) — 라이브 프리뷰, 파일 관리, 설정 동기화

## 검증
- [ ] 에뮬레이터에서 동작 확인
- [ ] 실제 차량(Atto 3) 탑재 테스트
- [ ] 회귀 테스트: 기존 녹화/세그먼트/잠금 기능 정상 동작 확인
