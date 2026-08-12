# BYD Camera Recorder - 작업 계획

## Phase 0: 프로젝트 기반 구축
- [x] 참조 앱(GHDanielG) 소스 전체 복사 + 패키지명/앱명 변경
- [x] 빌드 환경 구성 (macOS Android SDK 자동 감지, d8 지원, bash 3.2 호환)
- [x] 기존 기능 회귀 없이 빌드 통과 확인 (build/byd-dashcam-debug.apk 312K)
- [ ] 에뮬레이터에서 FixtureFrameSource 정상 동작 확인

## Phase 1: 모델 지원 확대
- [x] `VehicleProfile` interface 작성
- [x] `Atto3Profile` 구현 (기존 하드코딩 값 이전)
- [x] `GenericAvmProfile` 구현 (fallback)
- [x] `VehicleProfileRegistry` 작성 (Build.MODEL 기반 자동 감지)
- [x] `AvmCameraController` 리팩터링 (VehicleProfile 주입)
- [x] `FrameProcessor` 리팩터링 (상수 하드코딩 제거, profile 기반)
- [x] `RecorderSettings`에 vehicleModelId 저장
- [ ] 설정 화면에 모델 수동 선택 UI 추가
- [ ] 모델 감지 실패 시 선택 다이얼로그 표시

## Phase 2: GPS/속도 오버레이
- [ ] `AndroidManifest.xml`에 `ACCESS_FINE_LOCATION` 권한 추가
- [ ] `GpsDataProvider` 작성 (LocationManager 래퍼, 1초 업데이트)
- [ ] `GpsOverlayRenderer` 작성 (Paint 캐싱, 오버레이 Bitmap 생성)
- [ ] `FrameProcessor`에 오버레이 합성 훅 추가
- [ ] `GpxTrackWriter` 작성 (세그먼트별 .gpx 파일)
- [ ] `SegmentRecorder`에 GpxTrackWriter 연동
- [ ] 설정: 오버레이 ON/OFF, 위치(4코너), 속도 단위(km/h·mph), 표시 항목 선택

## Phase 3: 주차 감시 모드
- [x] `ImpactDetector` 작성 (TYPE_ACCELEROMETER, G-force 계산, 데바운스)
- [x] `ParkingGuardController` 작성 (감시 상태 머신)
- [x] `CameraRecorderService`에 주차 모드 진입/해제 로직 추가
- [x] 충격 감지 → 녹화 시작 → 세그먼트 자동 잠금 파이프라인
- [x] 주차 모드 UI (메인 화면에 토글 버튼)
- [x] `RecorderSettings`에 충격 임계값/녹화 시간/자동잠금 설정 키 추가
- [ ] 충격 감지 알림 채널 분리 (현재 기존 채널 공유)
- [ ] 설정 화면에 주차 감시 설정 UI (임계값 슬라이더, 녹화 시간)

## 검증
- [ ] 각 Phase 완료 후 에뮬레이터에서 동작 확인
- [ ] 실제 차량(Atto 3) 탑재 테스트
- [ ] 회귀 테스트: 기존 녹화/세그먼트/잠금 기능 정상 동작 확인
