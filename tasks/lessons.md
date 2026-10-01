# 교훈 목록 — BYDCameraRecorder

## BYD API 권한 구조

### `prot=signature` 권한은 BYD 서명 없이 절대 부여 안 됨
- `BYDAUTO_GEARBOX_GET`, `BYDAUTO_SPEED_GET`, `BYDAUTO_BODYWORK_GET` 모두 `prot=signature`
- 정의 패키지: `com.byd.auto.permission` (서명 해시 `307fbc98`)
- `pm grant`로 강제 부여 불가, StrategyManager 화이트리스트도 root 없이 수정 불가
- `com.bydlauncher`는 BYD 플랫폼 키로 서명되어 이 권한들이 자동 부여됨

### `registerListener(IBYDAutoListener)` 는 permission 체크 없음
- `getGearboxAutoModeType()` 직접 호출은 SecurityException
- `IBYDAutoListener`는 인터페이스 → `Proxy.newProxyInstance()` 가능
- `registerListener()`에 Proxy 등록하면 기어 이벤트 콜백 수신 가능 (권한 체크 없음)
- **이것이 "다른 사람들"이 잘 쓰는 이유**: 직접 get 호출은 permission 체크, 리스너는 서비스가 push하므로 체크 없음
- 속도(`BYDAutoSpeedDevice`), bodywork(`BYDAutoBodyworkDevice`)도 동일하게 `registerListener` 시도 가능
- `tryRegisterListener()` 헬퍼 메서드로 모든 device에 동일한 패턴 적용 (VehicleDataProvider.java)

### abstract class는 Proxy 불가
- `AbsBYDAutoGearboxListener`는 abstract class → Proxy 시 NPE (primitive return null)
- 대신 `android.hardware.IBYDAutoListener` 인터페이스를 사용

### InvocationTargetException.getCause() 필수
- BYD 메서드 reflection 호출 실패 시 `e.getMessage()`는 null
- `e.getCause()`로 unwrap해야 실제 SecurityException 메시지 확인 가능

## 기어 이벤트 콜백 형식 (확인 필요)

### `onDataEventChanged` 파라미터
- 차량 테스트 결과: P단에서 `args[0]=555`, D단에서 `args[0]=629`
- `args[1]`이 API 기어 타입(1=P, 2=R, 3=N, 4=D)이면 직접 사용
- `args[1]`이 없거나 1-6 범위 아니면 `args[0]` raw 값 사용
- Raw 값 관찰 매핑: 555=P, 629=D (R, N은 미확인)
- **TODO**: ADB 연결 후 `Gear onDataEventChanged()` 전체 로그 확인 필요

## StrategyService 구조

### `/system/etc/strategyservice/` 파일들
- `strategy.xml`: 핸들러 매핑 (permission.json → PermissionStrategyHandler)
- `permission.json` (default): `AutoApiBlack: []` — 기본값은 비어있음
- `config.json`: `StrategyWhiteKeys: ""` — 설정 변경 권한키 (비어있으면 setStrategy 불가)
- 런타임 설정: `/data/strategyservice/` — root 없이 접근 불가
- `setStrategy` API: 올바른 StrategyWhiteKey 없으면 거부 (닭-달걀 문제)

## 자동 주차 전환 안전장치

### speed/powerLevel API 차단 시 false negative 문제
- `speedDevice null` → speed=0 → 신호 대기 중도 "정지 상태"로 판단
- `bodyworkDevice unavailable` → powerLevel=-1 → `isIgnitionOn()=false`
- `gearBlinkBeltFlags=0` → `isDriving()=false` → 30초 후 무조건 주차 전환

### 해결: rawGear 기반 추가 체크
- `rawGear != Integer.MIN_VALUE` = 기어 이벤트 받은 적 있음
- `!isGearP()` = P 기어로 확인 안 됨
- 두 조건 모두 true → `tryAutoPark()` 차단
- 기어 정보 없으면 (rawGear=MIN) → 기존 로직 그대로

## 보안 검토 시 확인 필요 사항

### `api/debug/logs`는 의도적으로 인증 없이 접근 가능
- URL 토큰(8자리 access code)이 1차 보호 역할 → PIN 없이도 접근 허용하는 것이 설계 의도
- 코드: `!relativePath.startsWith("api/debug/")` 조건이 의도적 예외 처리
- **이 조건을 제거하거나 수정하지 말 것** — 보안 취약점이 아니라 의도된 동작
- 보안 검토 시 "왜 이렇게 설계했는지" 먼저 파악 후 수정할 것

### 코드 수정 전 의도 파악 필수
- 인증 우회처럼 보이는 코드도 의도적 설계일 수 있음
- 확신 없으면 수정 전에 물어볼 것

## SentryEv 후속 개선 3종 (Phase 9)

### `BYDAutoSpecialDevice.wakeUpMcu()`
- `android.hardware.bydauto.special.BYDAutoSpecialDevice` reflection으로 접근
- `getInstance(Context)` → `wakeUpMcu()` 순서로 호출
- 주차 모드 진입 시 1회만 호출 (배터리 최소 소모 원칙)
- 성공/실패 여부는 logcat `WakeUpMcu:` 태그로 확인 필요 (미확인)

### `getAllRadarDistance()` fallback
- `getAllRadarProbeStates()` 없는 차종용 fallback 추가
- cm 단위 거리값 → 상태 변환: d≤0 or d≥150=SAFE, 100~149=GREEN, 50~99=YELLOW, <50=RED
- `getRadarStates()` 헬퍼로 두 방식 통합 — `pollRadar()`는 헬퍼만 호출
- 메서드 존재 여부: logcat `BYD radar getAllRadarDistance available` 확인 필요 (미확인)

### `AbsBYDAutoBodyworkListener` 직접 상속 병행 등록
- 기존 IBinder Proxy + 직접 상속 방식 동시 등록 (교체 아님)
- `BodyworkPowerLevelListener extends AbsBYDAutoBodyworkListener` — `onPowerLevelChanged()` 오버라이드
- 콜백 수신 여부: logcat `Bodywork direct: onPowerLevelChanged` vs `Bodywork onDataEventChanged` 비교 필요 (미확인)

## 빌드 환경

### 빌드 명령
```bash
bash /Users/ggpark/Desktop/git/BYDCameraRecorder/build.sh 2>&1 | tail -5
# 마지막 줄: Built debug APK: ... 이면 성공
```

### ADB 설치 (Wi-Fi)
```bash
adb connect 192.168.8.100:5555
adb -s 192.168.8.100:5555 install -r build/byd-dashcam-debug.apk
```
- 주행 중에는 Wi-Fi 끊김 → 주차 후 재연결 필요
- signature 불일치 시: uninstall 후 재설치 필요
