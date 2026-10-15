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
- [x] **속도 기반 자동 전환**: 정지 30초 → 주차 감시 자동 진입, 주행 감지 → 녹화 복귀

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

## Phase 7: 텔레메트리 디버그 + 프리뷰 오버레이
- [x] `LogBuffer.java` — 최근 500개 로그 순환 저장, JSON 직렬화
- [x] `VehicleDataProvider.java` — raw API 값 변경 시 LogBuffer 기록 (BYDRaw 태그)
- [x] `TelemetryOverlayView.java` — native TextureView 위 오버레이 custom View (방향지시등 깜빡임 포함)
- [x] `CameraRecorderService.java` — LogBuffer 필드, UiListener에 onTelemetryUpdated + onGpsFixUpdated 추가
- [x] `PhoneAccessServer.java` — GET /api/debug/logs (JSON), GET /api/debug/logs.txt (파일 다운로드) 엔드포인트
- [x] `FrameProcessor.java` — applyToBitmap 제거 (TelemetryOverlayView로 대체)
- [x] `MainActivity.java` — TelemetryOverlayView를 메인 그리드 + fullscreen에 추가, UiListener 구현

## 버그 수정
- [x] GPS 속도=0일 때 텔레메트리 속도 무시 → 주행 중 주차 감시 자동 전환 오작동
  - `lastSpeedFromGps` 플래그 제거, `lastGpsSpeedKmh`/`lastTelemetrySpeedKmh` 독립 추적
  - `Math.max(gps, telemetry)` 기준으로 자동 전환 판단
- [x] BYD 기어 API 메서드명 오류: `getCurrentGear()` → `getGearboxAutoModeType()`
- [x] 방향지시등 매핑 오류: 0/1=off, 2/3=left, 4/5=right (kinex 앱 분석으로 확인)
- [x] 조명 API no-arg 호출 오류: `getLightStatus()` → `getLightStatus(int type)` (type=2 하향등, type=3 상향등)
- [x] 신호 대기 30초 시 주행→주차 자동 전환 오작동
  - 원인: speed/powerLevel API 차단(signature 권한) → 시동/기어 모두 판단 불가
  - 수정: `tryAutoPark()`에 `rawGear != MIN && !isGearP()` 추가 안전장치
  - 수정: 기어 raw 매핑(555=P, 629=D) + args[1] 유효성 체크(1-6이면 API 값으로 사용)
- [x] **기어 콜백 파라미터 확정** — 로그 분석으로 확인
  - args[0]=featureId(Integer), args[1]=BYDAutoEventValue 객체
  - BYDAutoEventValue.intValue 필드 reflection으로 추출
  - fid=692060194: 기어 위치 아님, 완전 제외
  - fid=562036753: getCurrentGear (3=P, 1=R, 0=N, 2=D)
  - fid=555745336: getGearboxAutoModeType (1=P, 2=R, 3=N, 4=D)
- [x] **D3 featureId 기반 매핑 분리** — gearFromPolling + featureId 분기 구현
- [ ] **속도 리스너 실차 확인** — `Speed onDataEventChanged()` args 구조 동일 여부 확인
- [ ] **bodywork 리스너 확인** — bodywork=X 초기화 실패 원인 파악
- [x] **BYDGearErr getCause() 로깅 추가** — InvocationTargetException 실제 원인 확인용
- [ ] **실차 기어 오버레이 검증** — v1.0.9.3 설치 후 BYDGearErr 원인 확인 + P/R/N/D 오버레이 정확도 확인

## Phase 8: ACC OFF 생존 안정성 강화

- [x] `AndroidManifest.xml` — ACC/IGN broadcast 인텐트 필터 추가 (ACC_ON, ACC_OFF, IGN_ON, ACC_MODE_CHANGED)
- [x] `RecorderStartup.java` — `isStartupAction()`에 ACC 액션 4개 추가
- [x] `CameraRecorderService.java` — `onCreate()` 끝에 `registerAccWhitelist()` 추가 (별도 스레드, graceful degradation)
- [ ] 차량 설치 후 `AccWhitelist:` 로그 확인
- [ ] ACC OFF 후 브로드캐스트 수신 로그 확인
- [ ] 주차 감시 모드 유지 여부 실차 검증

## 검증
- [ ] 에뮬레이터에서 동작 확인
- [ ] 실제 차량(Atto 3) 탑재 테스트 — 기어 raw 값 확인 후 P/R/N/D 매핑 검증
- [ ] 회귀 테스트: 기존 녹화/세그먼트/잠금 기능 정상 동작 확인
- [ ] 신호 대기 중 주차 전환 버그 수정 검증 (D 기어 + 정지 30초 이상)

## Phase 10: 로그 적재 개선

- [x] `CameraRecorderService.publishState()` → `logBuffer.append("Svc", ...)` 연동
- [x] `wakeUpMcu()` 결과 → logBuffer 기록
- [x] `VehicleDataProvider.initDevices()` 결과 요약 → logBuffer (`BYDInit` 태그)
- [x] 기어/속도/bodywork listener 최초 콜백 수신 → logBuffer (`BYDGear`, `BYDSpeed`, `BYDBodywork` 태그)
- [x] 빌드 통과

### Phase 10 검토
- `/api/debug/logs.txt`에서 이제 확인 가능한 항목:
  - `[Svc]` — 모든 서비스 상태 변경 (주차 모드 진입/해제, 녹화 시작/중지, ACC 이벤트 등)
  - `[WakeUpMcu]` — MCU 웨이크업 성공/실패
  - `[BYDInit]` — 앱 시작 시 device 초기화 결과 요약 (speed/gear/light/stat/energy/bodywork ok or X)
  - `[BYDGear]` — 기어 listener 최초 콜백 raw 내용
  - `[BYDSpeed]` — 속도 listener 최초 콜백 raw 내용
  - `[BYDBodywork]` — bodywork Proxy/direct listener 최초 콜백 수신 여부
  - `[BYDRaw]` — 텔레메트리 값 변경 시마다 (기존)

## Phase 9: SentryEv 분석 후속 개선 3종

- [x] `CameraRecorderService.java` — `wakeUpMcu()` 추가, `enterParkingMode()`에서 호출
- [x] `ParkingGuardController.java` — `methodGetAllRadarDistance` + `getRadarStates()` 헬퍼 추출
- [x] `VehicleDataProvider.java` — `BodyworkPowerLevelListener` inner class + 병행 등록
- [x] 빌드 통과 확인

### Phase 9 검토
- 빌드 성공 (build/byd-dashcam-debug.apk)
- 차량 설치 후 확인할 logcat 태그:
  - `WakeUpMcu:` — 주차 모드 진입 시 MCU 웨이크업 성공/실패
  - `BYD radar getAllRadarDistance available` — 메서드 존재 여부
  - `Bodywork direct: onPowerLevelChanged` — 직접 상속 콜백 수신 여부

## 보안 검토 결과 (완료)
- [x] `nextPinAttempts` 만료 항목 정리 추가 (`removeIf(e -> e.getValue() <= now)`)
- [x] `api/debug/logs` 인증 없음 — **의도된 설계** (URL 토큰이 1차 보호), 되돌림
- [~] PIN 6자리 + 5초 lockout, 세션 쿠키 HttpOnly/SameSite=Strict, path traversal 방어, constantTimeEquals — 모두 양호

## QA 검증 계획 (화이트박스)
- [x] 검증 대상/진입점/외부 의존성 및 기존 변경사항 확인
- [x] Java 본체 단위·통합 성격 테스트 및 전체 APK 빌드 실행
- [~] Flutter 모바일 정적 분석·테스트 통과, Android APK 빌드는 Gradle 무출력 장기 대기로 중단
- [x] 핵심 기능별 코드 경로/예외 경로/보안 경계 점검
- [x] 결과·재현 명령·미검증 영역을 아래 검토 섹션에 기록

## QA 검토 결과 (2026-10-15)

### 통과
- `bash build.sh`: Java 66개 소스 컴파일, DEX 생성, APK 패키징 및 apksigner v1/v2/v3 검증 통과
- `flutter analyze`: No issues found
- `flutter test`: 위젯 스모크 테스트 1건 통과
- `npm --prefix phone-ui test`: 테스트 파일 1개/테스트 2건 통과
- `npm --prefix phone-ui run build`: Vite production build 통과
- 통합 APK `build/byd-dashcam-debug.apk`의 manifest/package/version/권한 및 phone UI asset 포함 확인
- 코드 경로 검토: PIN lockout·constant-time 비교·세션 쿠키·토큰 prefix·path traversal·HTTP range·SSE/WebSocket·주차 이벤트 상태 전이

### 경고 및 미해결
- `aapt`: 기본 영어 리소스에 MQTT/Telegram 관련 한국어 번역 키 14개 누락 경고
- `javac`: Java 8 `-source/-target` obsolete 경고
- D8: vendor stub에 포함되지 않은 `AVMCamera` callback/`AbsBYDAutoBodyworkListener` 타입 경고
- Flutter Android APK build: `assembleDebug`가 장시간 무출력 상태여서 중단했으며 APK 산출물 미확인
- ADB/에뮬레이터: daemon socket 권한 오류로 기동 실패, 설치·런타임·FixtureFrameSource 검증 불가
- 실차: BYD 기어/속도/bodywork listener, ACC OFF 생존, radar/MCU wake-up, 카메라·GPS 하드웨어 동작 미검증

### 재현 명령
```bash
bash build.sh
flutter analyze && flutter test
flutter build apk --debug
npm --prefix phone-ui test
npm --prefix phone-ui run build
```

## QA 후속: aapt 고아 번역 키 정리

aapt 경고 14개 — `values-ko/strings.xml`에만 있고 `values/strings.xml`에 없는 키 제거

- [x] 14개 키가 Java 코드에서 실제 참조 여부 확인 — 전부 미사용
- [x] 미사용 키 14개 `values-ko/strings.xml`에서 제거 (Telegram 4개, MQTT 9개, hint_optional 1개)
- [x] 빌드 후 aapt 번역 경고 0개 확인

### QA 후속 검토
- aapt 번역 경고 14개 → 0개로 해소
