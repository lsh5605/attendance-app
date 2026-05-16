# 출석 앱 프로젝트 (Attendance App)

## 📌 프로젝트 개요

대학교 출석 앱. **BLE + UWB 2단계 검증**으로 부정 출석 방지.

### 스택
- **Android**: Java (Android Studio)
- **Server**: Node.js + Express + **socket.io** (실시간 조율)
- **DB**: Firebase Firestore
- **통신**:
  - **HTTP (Retrofit2)**: 1회성 요청 — 세션 생성/조회, check-in
  - **WebSocket (socket.io)**: 실시간 조율 — UWB ranging 신호, 결과 전달

### 동작 시나리오
```
1단계 (BLE/PIN - 출석 등록, HTTP)
  교수 폰이 4자리 PIN(sessionCode)을 BLE 광고로 송출 (5분간) + 화면에 상시 표시
    ↓
  학생 폰이 BLE 스캔으로 PIN 수신
    (BLE 못 잡거나 5분 후 지각이면 → 학생이 PIN을 직접 입력)
    ↓
  학생이 서버에 "나 이 세션 들었음" 출석 등록 (HTTP)
    ↓
  학생/교수 둘 다 WebSocket 연결 (수업 내내 유지)

2단계 (UWB - 재실 검증, 5분 주기, WebSocket 기반 on-demand)
  교수가 5분마다 학생 명단 조회 (HTTP) → CHECKED_IN만
    ↓
  학생 1명씩 순회하며:
    교수 ──[PREPARE attendanceId]──▶ 서버 ──▶ 학생
    학생: RangingSession open (~300ms)
    학생 ──[READY]──▶ 서버 ──▶ 교수
    교수: UWB ranging 시작 ◄═════════════════ 학생 (UWB 직접 측정)
    교수 ──[RESULT distance]──▶ 서버 (ranging_logs 저장 + uwbFailCount 갱신)
    서버 ──[DONE]──▶ 학생: RangingSession close
    ↓
  서버 판정: 거리 ≤ 20m & 연결 성공 → 성공 / 그 외 → 실패
    ↓
  연속 3회 실패 시 → status = ABSENT (결석 처리)
```

---

## 📁 프로젝트 구조

```
C:\Users\miran\attendance-app\
├── CLAUDE.md                    ← 이 파일
├── HANDOFF.md                   ← 사이클 간 핸드오프 노트
├── Attendance\                  (Android 앱)
│   ├── gradle\libs.versions.toml          (의존성 카탈로그)
│   └── app\
│       ├── build.gradle.kts                (앱 모듈 빌드 스크립트)
│       └── src\main\java\com\example\attendance\
│           ├── MainActivity.java                (교수 화면, UI만)
│           ├── MainActivity2.java               (학생 화면, UI만)
│           ├── attendance\
│           │   ├── AttendanceController.java          (학생 업무 로직)
│           │   └── ProfessorAttendanceController.java (교수 업무 로직)
│           ├── service\
│           │   ├── AttendanceEvents.java              (Service↔Activity broadcast 키)
│           │   ├── ProfessorAttendanceService.java    (교수 Foreground Service)
│           │   └── StudentAttendanceService.java      (학생 Foreground Service)
│           ├── ble\
│           │   ├── BleAdvertiseManager.java
│           │   └── BleScanManager.java
│           ├── uwb\                                  (Kotlin)
│           │   ├── ProfessorUwbRangingManager        (Controller 역할, multicast 지원 예정)
│           │   └── StudentUwbRangingManager          (Controllee 역할)
│           │   (※ UwbAddressUtil 은 Phase 4 refactor 때 삭제. Manager가 직접 scope 열고 주소 노출)
│           └── network\
│               ├── ApiResponse.java
│               ├── AttendanceApi.java
│               ├── NetworkConfig.java                (HTTP/Socket BASE_URL)
│               ├── RetrofitClient.java
│               ├── StartAttendanceData.java
│               ├── StartAttendanceRequest.java
│               ├── CheckInRequest.java
│               ├── CheckInData.java
│               ├── StudentInfo.java
│               ├── UwbParamsAdapter.java             ← Phase 4 신규 (DTO → Manager UwbParams 변환)
│               └── socket\
│                   ├── AttendanceSocketClient.java   (socket.io-client 래퍼)
│                   └── SocketEvent.java              (PREPARE/READY/RESULT/DONE 등)
└── attendance-server\           (Node.js 서버)
    ├── server.js                        (엔트리포인트, port 3000 — HTTP+WS)
    ├── routes\attendance.js
    ├── controllers\attendanceController.js
    ├── services\attendanceService.js    (start/checkIn/getStudents/processRangingResult)
    ├── firebase\firebaseAdmin.js
    ├── utils\
    │   ├── codeGenerator.js             (4자리 숫자 PIN 생성)
    │   └── uwbParamsGenerator.js        (UWB 공유 파라미터 생성)
    └── socket\                          ← Phase 2 신규
        ├── socketServer.js              (socket.io 초기화 + 인증 미들웨어)
        ├── socketRouter.js              (PREPARE/READY/RESULT/DONE 라우팅)
        └── connectionRegistry.js        (userId ↔ socket 매핑)
```

---

## 🏗 아키텍처 원칙

### BLE는 Manager 클래스로 분리
- `BleAdvertiseManager` (교수용), `BleScanManager` (학생용)
- Activity는 UI/권한/네트워크만 담당, BLE는 Manager에 위임
- **이유**: Service 전환(백그라운드 동작) 대비 + 재사용성 + 테스트 용이성

### 업무 로직은 Controller 클래스로 분리
- `AttendanceController` (학생용), `ProfessorAttendanceController` (교수용)
- Activity는 UI/권한만, Manager는 기술만, **Controller가 업무 순서/정책 담당**
- 예: "서버 세션 생성 → sessionCode로 BLE 시작" 흐름은 Controller 내부에서 원자적으로 실행
- **이유**: Activity 생명주기와 업무 생명주기 분리 + Service 전환 시 Controller 그대로 재사용

### 계층 책임 요약
| 계층 | 관심사 | 예시 |
|---|---|---|
| Activity | UI, 권한, 버튼 | 권한 다이얼로그, Toast |
| Controller | 업무 순서/정책 | 서버↔BLE↔UWB↔Socket 조율, 5분 스케줄링 |
| Manager | 기술 하나 | BLE Advertise, BLE Scan, UWB Ranging |
| Retrofit API | HTTP 하나 (1회성 요청) | /start, /check-in, /students |
| SocketClient | WebSocket 1개 (실시간 채널) | PREPARE/READY/RESULT/DONE 이벤트 |

### Manager 설계 규칙
- `ApplicationContext`만 보관 (Activity 참조 X → 메모리 누수 방지)
- Listener 패턴으로 결과 전달 (Activity/Service 어디서든 구독 가능)
- 자기 생명주기는 자기가 관리 (start/stop + 타이머)
- UI 직접 조작 금지

### 권한 체크 위치
- **현재**: Activity에서만 체크 (Manager는 `@SuppressLint("MissingPermission")`)
- **Service 전환 시**: Manager 내부에도 체크 추가

---

## ⚙️ 핵심 설계 결정사항

| 항목 | 결정 | 이유 |
|------|------|------|
| BLE 광고 시간 | 기본 5분 (설정 가능) | 지각 마감 + 배터리 |
| BLE 스캔 타임아웃 | 기본 30초 | 빠른 출석 체크 |
| BLE 스캔 중단 시점 | 서버 verify 성공 시 Controller가 결정 | 다른 수업 신호 무시 위해 |
| 광고 모드 | `ADVERTISE_MODE_LOW_POWER` | 강의실 거리엔 충분 |
| 연결 방식 | `setConnectable(false)` | 1:N 방송만 필요 |
| BLE UUID | `12345678-1234-1234-1234-1234567890ab` | 앱 고유 식별자 |
| 스캔 모드 | `SCAN_MODE_LOW_LATENCY` | 수업 시작 시 즉시 감지 |
| sessionCode (PIN) | **4자리 숫자**, 서버 생성, ACTIVE 간 중복 방지 | BLE 송출 + 학생 수동 입력 겸용. 사람이 입력 가능하게 짧게. 추측 공간(10⁴)은 작지만 진짜 게이트는 UWB라 수용 가능. CLOSED엔 같은 코드 존재 가능 |
| 출석 등록 경로 | BLE 스캔 자동 + 학생 PIN 수동 입력 (2경로, 동일 `/check-in`) | BLE 못 잡거나 5분 후 지각 학생 구제. BLE는 코드 전달 수단일 뿐, 수동 입력도 같은 API |
| 중복 check-in | 멱등 처리 (기존 레코드 반환) | BLE 중복 감지/재시도 + BLE·PIN 경로 동시 발생 대비 |
| UWB 파라미터 공유 | 한 수업의 **모든 학생이 동일한** `sessionId`/`sessionKey`/`channel`/`preambleIndex` 사용. `configType`은 클라이언트 하드코딩 `MULTICAST_DS_TWR` | 구현 단순성. 대리 출석 방어는 **현재 약함** — 학생 주소가 매 측정마다 동적이라 사전 등록 주소와 일치 검증 불가. Phase 5에서 보강 (서명/토큰 기반) |
| UWB 파라미터 수명 | 수업 시작 시 1회 생성, 수업 내내 재사용 (5분 주기 재협상 X) | 학생 폰이 `/check-in` 응답으로 받은 값을 그대로 계속 사용. 키 로테이션 채널 불필요 |
| UWB ranging 순회 | 교수가 Controller 역할로 학생 명단을 1명씩 순차 ranging | UWB는 1:1 방식이라 broadcast 불가. 학생은 Controllee로 대기 |
| UWB ranging 실패 기준 | 연결 실패와 거리 초과를 동일한 "실패"로 통합 | 정책 단순화. 학생 관점에선 둘 다 "재실 실패" 결과 동일 |
| 거리 임계값 | **20m** (서버 상수) | 일반 강의실 범위 커버 (추후 수업별 설정 확장 가능) |
| 결석 판정 | UWB ranging **3회 연속** 실패 시 `ABSENT` | 일시적 신호 불안정 배제 + 잠깐 자리 비움 허용 (최대 15분 부재 허용) |
| 결석 판정 주체 | 서버 (`/ranging-result` 엔드포인트에서 카운터 관리) | 정책 중앙화. 교수 폰은 raw 결과만 보고, 서버가 임계값·카운터·상태 전이 담당 |
| ranging 결과 저장 | 최상위 컬렉션 `ranging_logs`에 매 측정 1문서씩 누적 | 측정 이력 보존 (분석·이의제기 대응). `attendance_records`는 현재 상태만 |
| UWB ranging 재시도 | 교수 폰 내부 재시도 **없음** (awaitMeasurement timeout 12s). 학생 1명 측정 fail 시 **사이클 fail-recovery**: closeScope+openScope+다음 학생을 새 sub-cycle 첫 학생으로 (`startMulticastSession`) | `RangingResultFailure` 후 활성 peer 없으면 session "add 거부" 상태로 진입 (`addControlee`가 `IllegalStateException`). 회복은 새 scope만 가능. 한 사이클 안에서도 fail 학생 수만큼 sub-cycle로 분할될 수 있음. 명단 끝까지 진행 |
| 교수↔학생 실시간 조율 | **WebSocket (socket.io)** 기반. 서버가 메시지 브로커 | UWB ranging은 정확한 타이밍 조율 필요 (교수가 호출하는 순간 학생이 RangingSession 열려있어야 함). HTTP polling은 지연 크고 비효율 |
| WebSocket 메시지 종류 | `PREPARE` / `READY` / `RESULT` / `DONE` (4개 이벤트) | PREPARE: 교수→학생 "준비해" / READY: 학생→교수 "열렸어" / RESULT: 교수→서버 "결과 저장해" / DONE: 서버→학생 "끊어도 돼" |
| 메시지 라우팅 | 교수↔학생 **직접 통신 아님**. 항상 서버 경유 | NAT/방화벽으로 폰끼리 직접 TCP 연결 불가 + 서버가 인증·DB 저장 통합 |
| 학생 UWB scope 생명주기 | **측정 1회당 새 scope** (PREPARE 받으면 open → DONE 받으면 close). 매 측정마다 새 `localAddress` 받아 READY 페이로드에 담아 송신 | alpha API의 `controleeSessionScope`는 사실상 single-use. 한 scope에서 두 번째 `prepareSession` 호출 시 즉시 `RangingResultFailure`. 매 측정마다 fresh scope 사용 |
| 교수 UWB scope 생명주기 | **사이클(5분)당 새 scope** open (close at end). 사이클 내부에선 **multicast(`CONFIG_MULTICAST_DS_TWR`)** 사용 → 첫 학생 시 `prepareSession`, 이후 학생들은 `addControlee`/`removeControlee`로 swap | alpha API의 `controllerSessionScope`도 single-use. 사이클 사이엔 close+open으로 reset. 사이클 내부에선 prepareSession 1번 + add/remove로 학생 N명을 한 세션에서 처리 → 사이클당 prepareSession 호출 1번만 |
| UWB_RANGING 런타임 권한 | manifest 선언만으론 부족. Activity에서 명시적 `requestPermissions` 호출 필수 (Samsung) | 공식적으론 normal permission이라 manifest만으로 자동 부여돼야 하지만, **Samsung이 사실상 runtime처럼 다룸**. 권한 없으면 `prepareSession`이 silently no-op (어떤 에러도 안 뜸 — 디버깅 매우 어려움) |
| UWB short address | 매 scope open마다 새로 할당. 영구 ID 없음 | FiRa 단축 주소(2 byte)는 ephemeral. 서버에 보고한 주소와 ranging 시 사용 주소가 다르면 peer 매칭 실패 → **같은 scope에서 추출한 주소를 그대로 ranging에 사용**해야 함. 사이클/측정마다 새 주소를 socket으로 전달 |
| `UwbControllerSessionScope` 단일 사용 제약 | 한 scope 인스턴스에서 `prepareSession`은 단 1번만 작동 (재호출 시 `RangingResultFailure` 즉시 emit). 5~30분 wait 무효 | alpha 라이브러리(1.0.0-alpha11) + Samsung S23 Ultra 환경에서 확인됨. scope close 후 새로 열어야 다음 `prepareSession` 가능. 다른 폰 페어/안정 버전에선 다를 수도 |
| HTTP vs WebSocket 분담 | **HTTP**: 1회성 요청 (`/start`, `/check-in`, `/students`). **WebSocket**: 실시간 조율 (ranging 신호, 결과) | 1회성 요청은 REST가 단순. 실시간성 필요한 부분만 WS로. ranging 결과는 WS의 RESULT 이벤트로 통합 (별도 HTTP `/ranging-result` 없음) |

---

## 🗄 Firestore 스키마

### `attendance_sessions`
```
{lectureSessionId}/
  lectureSessionId, sessionCode, courseId, professorId,
  status: "ACTIVE" | "CLOSED",
  startTime, endTime,
  uwbParams: {                          ← UWB 단계에서 /start 시점에 채움
    sessionId,          // 수업 공통 (모든 학생과 동일 값 사용)
    sessionKey,         // 수업 공통, **정확히 8 byte** (CONFIG_UNICAST_DS_TWR의 static STS 제약)
                        //   → JSON으로는 int[] 길이 8로 직렬화
    channel,            // 수업 공통 (예: 9)
    preambleIndex,      // 수업 공통 (예: 10)
    configType,         // 수업 공통 (예: "UNICAST_DS_TWR")
    controllerAddress   // 교수 폰 UWB 주소 (학생이 Responder 설정 시 사용)
  }
```
- 문서 ID = `lectureSessionId` (필드로도 중복 저장)
- `sessionCode`는 **4자리 숫자 PIN** (BLE 송출 + 학생 수동 입력 겸용). 중복 방지는 **ACTIVE 세션들 간에만** (CLOSED엔 같은 코드 존재 가능)
- `uwbParams`는 한 번 생성되면 수업 끝날 때까지 불변

### `attendance_records`
```
{attendanceId}/
  attendanceId, lectureSessionId, sessionCode,
  studentId, checkInTime,
  studentUwbAddress,                    ← UWB 단계에서 /check-in 요청에 포함
  status: "CHECKED_IN" | "ABSENT",      ← 3회 연속 ranging 실패 시 ABSENT로 전이
  uwbFailCount: number,                 ← 연속 실패 카운터 (성공 시 0으로 리셋)
  lastRangingAt: timestamp | null       ← 마지막 ranging 시도 시각
```
- 조회 키: `lectureSessionId` (세션 내 전체 출석자), `studentId` (학생별 이력)
- `sessionCode`도 같이 저장해뒀지만 조회 기준으론 `lectureSessionId` 사용 권장 (고유성 보장)
- `studentUwbAddress`는 ranging 중 대리 출석 방어용 (교수가 ranging 응답 온 주소와 대조)
- "현재 상태"만 보관 (측정 이력은 `ranging_logs`에)

### `ranging_logs` (UWB 단계에서 신설, 최상위 컬렉션)
```
{rangingLogId}/
  rangingLogId,
  attendanceId,        // 어느 출석 레코드의 측정인지 (FK)
  lectureSessionId,    // 수업 전체 조회용
  studentId,           // 학생별 조회용
  timestamp,
  distance: number | null,     // 미터. 연결 실패 시 null
  failureReason: string | null, // "CONNECTION_FAILED" | "OUT_OF_RANGE" | null(성공)
  success: boolean              // distance ≤ 20m 이고 failureReason 이 null이면 true
```
- 측정 1회당 문서 1개 누적 (불변 이벤트 로그)
- 필요한 복합 인덱스: `(lectureSessionId, timestamp)`, `(attendanceId, timestamp)`
- 주요 조회 패턴:
  - 수업 전체 ranging 이력: `where(lectureSessionId).orderBy(timestamp)`
  - 학생별 이력: `where(studentId).orderBy(timestamp)`
  - 실패만: `where(lectureSessionId).where(success, false).orderBy(timestamp)`

---

## ✅ 진행 상황

### 완료
- [x] 서버 세션 생성 API (`POST /api/attendance/start`)
- [x] Firestore `attendance_sessions` 저장
- [x] 교수 BLE 광고 (5분 자동 중단 포함)
- [x] 학생 BLE 스캔 (30초 타임아웃, 자동중단 제거)
- [x] Manager 클래스로 BLE 로직 분리
- [x] Retrofit 기반 서버 통신
- [x] **학생 출석 등록 API** (`POST /api/attendance/check-in`)
  - Firestore `attendance_records` 저장, 멱등 처리
  - ACTIVE 세션만 허용 (status 필터로 CLOSED 중복 코드 방지)
- [x] **Controller 계층 도입**
  - `AttendanceController` (학생): BLE 감지 → 서버 verify → 맞으면 stopScan
  - `ProfessorAttendanceController` (교수): 서버 start → BLE 광고 시작
  - Activity는 UI만, Service 전환 준비 완료
- [x] **Service 전환** (Foreground Service로 백그라운드 동작)
  - `ProfessorAttendanceService` / `StudentAttendanceService` 도입
  - Activity↔Service 통신은 `AttendanceEvents`의 broadcast 키 패턴으로
- [x] **UWB Phase 1 — 서버 데이터 평면**
  - `/start` 확장: Request에 `professorUwbAddress` 추가, 서버가 `uwbParams` 1회 생성 후 `attendance_sessions`에 저장
  - `/check-in` 확장: Request에 `studentUwbAddress` 추가, Response에 `uwbParams` 동봉 (멱등 분기 포함)
  - `GET /api/attendance/:lectureSessionId/students` 신규 (status=CHECKED_IN만)
  - placeholder 주소 운용: 교수 `"0x0001"` / 학생 `"0xAAAA"` (Phase 3에서 실제 추출로 교체)
- [x] **UWB Phase 2 — WebSocket 인프라**
  - 서버: `socket/socketServer.js` (auth 미들웨어), `connectionRegistry.js` (userId↔socket Map + 교수 보조 인덱스), `socketRouter.js` (PREPARE/READY/RESULT 라우팅)
  - 서비스: `attendanceService.processRangingResult` (판정 + `ranging_logs` 추가 + `attendance_records` 갱신 트랜잭션, 3회 누적 시 ABSENT 비가역 전이)
  - Android: `socket.io-client:2.1.2` 의존성, `network/socket/SocketEvent.java`, `network/socket/AttendanceSocketClient.java` (Role enum + Listener default 빈 구현 + 자동 재연결 1s→5s)
  - `network/NetworkConfig.java` 도입 — HTTP/Socket BASE_URL 단일 진실원천

- [x] **UWB Phase 3 — UWB Ranging Manager 구현**
  - 빌드 환경: Kotlin (AGP 9 빌트인) + coroutines + `androidx.core.uwb:1.0.0-alpha11`
  - `ProfessorUwbRangingManager`, `StudentUwbRangingManager` (Kotlin)
  - Manifest에 UWB feature + `UWB_RANGING` 권한
- [x] **UWB Phase 4 — Controller 통합 (1라운드까지)**
  - 4.1 `UwbParamsAdapter`: 서버 DTO → Manager UwbParams 변환 (int[]→byte[], complexChannel 평탄화)
  - 4.2 `ProfessorAttendanceController` 확장: Manager/Socket 결합, 5분 주기 루프, 자동 휴식, pause/resume, stopSession 멱등
  - 4.3 `AttendanceController` 확장 (학생): Manager/Socket 결합, PREPARE→armSession→READY, DONE→disarmSession
  - 4.4 Service 종료 정책 변경 + STOP 버튼 UI (`stopSelf` 자동 호출 제거)
  - 4.5 ABSENT 통보 흐름 (DONE 페이로드 분기 + Toast)
- [x] **주소 일관성 refactor** (1라운드 검증 위해 필수)
  - `UwbAddressUtil` 삭제 — 별도 scope 열어 주소만 추출하면 실제 ranging 주소와 달라짐
  - Manager의 `start(cb)` callback으로 `localAddress` 노출, 그 scope를 ranging에도 사용
- [x] **`UWB_RANGING` 런타임 권한 추가** — Samsung은 normal permission도 runtime처럼 다룸. manifest만으론 부족
- [x] **1라운드 UWB ranging 실측 검증** — Samsung S23 Ultra × 2, dist=0.17m, peer 매칭=true

- [x] **UWB Phase 4 마무리 — Option A + fail-recovery (코드 완료, 실측 대기)**
  - PoC 4가지 가정 검증 완료 (Samsung S23 Ultra × 2):
    1. `CONFIG_MULTICAST_DS_TWR` 작동
    2. `addControlee`로 동적 추가 작동
    3. `removeControlee` 후 다시 `addControlee` 반복 작동
    4. `closeScope` → 새 `openScope` + `prepareSession` 성공
  - 검증된 추가 사실:
    - multicast가 **부분 fail tolerant**: controlee 여러 명 중 일부가 응답 안 해도 다른 peer 측정 계속됨
    - **활성 peer 없으면 session "add 거부" 상태 진입**: 응답 없는 peer만 남으면 ~10초 후 `RangingResultFailure` → 그 후 `addControlee`가 `IllegalStateException "ranging is active"`로 거부. 회복은 `closeScope+openScope`만 가능
    - 즉 학생 1명 측정 fail이 사이클 진행을 막을 수 있음 → fail-recovery 필수
  - 풀구현:
    - **사이클 시작**: `closeScope+openScope` → 새 `controllerAddress` (PREPARE 페이로드에 동봉)
    - **첫 학생**: `startMulticastSession(stuAddr)` (`prepareSession`)
    - **둘째 이후**: `awaitMeasurement` 등록 → `addControleeAsync(stuAddr)` → 측정 받으면 prev `removeControleeAsync` (Add-before-Remove)
    - **학생 1명 측정 fail 감지** (READY timeout / addControlee fail / awaitMeasurement timeout): 그 학생 결석 카운트 +1 → `closeScope+openScope` → 다음 학생을 새 sub-cycle의 첫 학생으로 (`startMulticastSession`)
    - **사이클 끝**: `closeScope`. 다음 사이클까지 대기 (5분 주기 유지)
    - **학생 측**: 매 PREPARE에 `openScope` → `setControllerHex(ctrlAddr)` → `armSessionMulticast` → `onArmed` 시 `emitReady(myAddr)`. DONE 받으면 `closeScope`
    - **수업 시작 시 첫 openScope는 안 함**: 사이클 시작 시 자동 open. `/start`의 `professorUwbAddress`는 placeholder
    - **API 페이로드의 UWB 주소 필드는 placeholder**: `/check-in`의 `studentUwbAddress`, `/start`의 `professorUwbAddress`. 실제 ranging 주소는 socket PREPARE/READY로 매 사이클 동적 교환
  - PoC Activity 제거 (`UwbPocProfessorActivity`/`UwbPocStudentActivity`)
  - Manager 양쪽 unused 기존 API 제거 (`start(cb)`/`range`/`armSession` UNICAST 기반)
  - 진단 로그 verbose 강등 (capability 9개 필드, flow event 일반은 V, Failure만 W)
- [x] **Phase 4 디버깅 로그 정리** (Phase 5 F 완료)
  - `flow event:` Log.v (Professor/Student Manager): 삭제
  - `multicast prepareSession initial/peer=...` Log.d: 삭제
  - `.catch { Log.e ... }` Flow 블록 (Professor/Student Manager): 삭제 — 둘 다 surrounding try/catch이 처리
  - `UWB_RANGING granted=` Log.d (MainActivity/MainActivity2): 삭제, 주석은 "Samsung normal permission runtime 처리 우회" WHY로 교체
  - `capability 9개 필드`는 이미 Phase 4 verbose 강등 시 제거되어 있어 추가 작업 없음
  - 유지: 모든 Log.w/Log.e, 정상 흐름 D 로그(openScope/closeScope/addControlee 등), 디버깅용 V 로그(collect 시작/취소/종료)
- [x] **Service-ready 잔재 #7 콜백 main thread dispatch** (Phase 5 B 부분 완료)
  - 기존 5개 항목(#1 SuppressLint 제거 / #2 Manager 내부 권한 체크 / #3 adapter.isEnabled() / #4 SecurityException try-catch / #6 volatile)은 이미 적용되어 있었음을 확인
  - 신규 적용: `BleAdvertiseManager`/`BleScanManager`의 `notifyXxx()` 메서드 listener 호출을 `handler.post`로 dispatch
  - 신규 적용: `AttendanceSocketClient`에 `mainHandler` 추가, lifecycle (CONNECT/DISCONNECT/CONNECT_ERROR) + PREPARE/READY/DONE 콜백 모두 main thread dispatch. 클래스 javadoc 갱신
- [x] **Service-ready 잔재 #5 + #8 BT BroadcastReceiver + adapter 재획득** (Phase 5 B 완료, 실측 대기)
  - `BleAdvertiseManager.reinit()` / `BleScanManager.reinit()` 추가 — `init()` 재실행 후 advertiser/scanner non-null 여부 반환
  - `ProfessorAttendanceController.onBluetoothStateChanged(boolean)` 추가 — `enabled=true`에서 advertiseManager.reinit() 호출, 실패 시 `notifyFailed("Bluetooth 어댑터 재획득 실패")` (Q4 정책)
  - `AttendanceController.onBluetoothStateChanged(boolean)` 추가 — scanManager 기준 동일 패턴
  - `ProfessorAttendanceService` / `StudentAttendanceService`에 `BluetoothAdapter.ACTION_STATE_CHANGED` BroadcastReceiver 등록 (onCreate) / 해제 (onDestroy). Android 13+엔 `RECEIVER_NOT_EXPORTED` 플래그
  - 진행 중 사이클은 별도 abort 코드 X — 기존 ranging timeout 경로가 connectionFailed로 자연 fail 처리 (Q1)
  - 5분 카운터는 BT off 동안에도 계속 — 3회 누적 시 ABSENT 정책 유지 (Q2)
  - **Phase 5 B "Service-ready 잔재 정리" 항목 완전 해소**. 검증은 실측 단계에서 BT 토글 시나리오로
- [x] **수동 PIN 입력 출석 경로 추가**
  - `sessionCode`를 **4자리 숫자 PIN**으로 교체 (`codeGenerator.js` → `crypto.randomInt`). BLE 송출 + 학생 수동 입력 겸용. 필드명 `sessionCode`는 유지, 포맷만 변경 — 서버 retry loop가 ACTIVE 중복 방지 그대로 담당
  - 교수 앱: 세션 시작 시 PIN을 화면에 상시 표시 (`activity_main.xml`의 `pinText` TextView)
  - 학생 앱: PIN 입력 UI 상시 노출 (`activity_main2.xml`). BLE 못 잡거나 5분 후 지각 학생이 직접 입력 → `MainActivity2.submitPin` → `StudentAttendanceService.ACTION_SUBMIT_PIN` → `AttendanceController.checkInWithCode` (기존 `verifyAndCheckIn` 재사용)
  - scan 타임아웃 시 `notifyFailed` 제거 → Service 유지 (PIN 수동 입력 대기). `confirmed` 플래그 + 서버 멱등으로 BLE·PIN 동시 발생 안전
  - 등록되면 `CHECKED_IN` → 다음 UWB 사이클 `GET /students`에 자동 합류 (지각생은 다음 사이클부터)

### 다음 할 일 — 실측 + Phase 5 진입

### Phase 5: 안정성 + 보안 + 실측

#### A. 보안 (메인)
- [ ] **로그인 시스템** — `users` 컬렉션, 토큰 발급/검증
- [ ] **수강 명단 검증** — `courses/{courseId}/enrolledStudents`. `/check-in`에서 토큰 검증 + 수강 검증
- [ ] **하드코딩 ID 제거** (`STU001`, `PROF001`, `CS101`)
- [ ] **자기 신원 검증**: 로그인 토큰이 진짜 그 학생인지 + 수강 권한 검증

#### B. 안정성
- [ ] **세션·소켓 복구 로직 검증** (장기 안정성)
- [ ] **Service-ready 잔재 실측** — #5/#8 코드는 완료, BT 토글 시나리오 실기기 검증만 남음
- [ ] **OEM 백그라운드 킬러 대응** (Samsung/Xiaomi)
- [ ] **수업 시간 만료 자동 종료** — 서버 `durationMinutes` 추가 → Controller schedule

#### C. 유연성
- [ ] **`BuildConfig.SERVER_HOST`** 단일 진실원천 (`NetworkConfig` ↔ `network_security_config.xml`)
- [ ] **휴식 시간 동적화** — 수업별 시간표 (현재 매시간 50분~정각 하드코딩)

#### D. 실측
- [ ] **배터리 실측** (Pixel + Samsung에서 2~3시간 수업 시뮬레이션)
- [ ] **다기기 호환성** (Pixel + Samsung, Pixel + Pixel)
- [ ] **ABSENT 시나리오** 실제 동작 검증

#### E. UX
- [ ] **알림 영역에 STOP/PAUSE 액션 버튼**
- [ ] **교수 화면에 실시간 출석 현황** (학생 명단 + uwbFailCount + 결석 강조)
- [ ] **ABSENT 통보 강화** (Toast → 모달 다이얼로그)

#### F. 진단 로그 정리 ✅ 완료 (위 완료 섹션 참조)

---

## 🔄 Service-ready 잔재 (Phase 5에서 마무리)

Service 전환은 완료됨. 다만 다음 항목들이 잔재로 남음:

1. `@SuppressLint("MissingPermission")` 제거
2. Manager 내부 권한 체크 추가 (`hasAdvertisePermission` / `hasScanPermission`)
3. `adapter.isEnabled()` 체크 (AdvertiseManager에 추가)
4. `SecurityException` try-catch 보강
5. BT 상태 변화 `BroadcastReceiver` 추가 (사용자가 BT 껐다 켤 때)
6. `isAdvertising`/`isScanning` → `volatile` 또는 synchronized
7. 콜백을 main thread로 dispatch (`Handler`) — Phase 4에서 socket.io 콜백도 함께 정리
8. adapter 재획득 로직 (BT 재시작 시 advertiser 갱신)

---

## 📡 현재 API 명세

### `POST /api/attendance/start` (완료, Phase 1에서 확장)
**Request**
```json
{
  "courseId": "CS101",
  "professorId": "PROF001",
  "professorUwbAddress": "0x0001"
}
```

**Response**
```json
{
  "success": true,
  "data": {
    "lectureSessionId": "xxx",
    "sessionCode": "1234",
    "courseId": "CS101",
    "professorId": "PROF001",
    "status": "ACTIVE",
    "startTime": "...",
    "endTime": null,
    "uwbParams": {
      "sessionId": 1234567890,
      "sessionKey": [12, 34, ...],
      "complexChannel": { "channel": 9, "preambleIndex": 10 },
      "controllerAddress": "0x0001"
    }
  }
}
```
> `configType`은 응답에 미포함. 모든 학생이 `"UNICAST_DS_TWR"` 하드코딩 전제.

### `POST /api/attendance/check-in` (완료, Phase 1에서 확장)
**Request**
```json
{
  "sessionCode": "1234",
  "studentId": "STU001",
  "studentUwbAddress": "0xAAAA"
}
```

**Response (200 성공 / 기존 레코드도 동일 형태로 반환 = 멱등)**
```json
{
  "success": true,
  "data": {
    "attendanceId": "...",
    "lectureSessionId": "xxx",
    "sessionCode": "1234",
    "studentId": "STU001",
    "checkInTime": "2026-04-24T10:30:00.000Z",
    "uwbParams": { "sessionId": ..., "sessionKey": [...], "complexChannel": {...}, "controllerAddress": "..." }
  }
}
```
> `uwbParams`는 멱등 분기에서도 동일하게 포함 (클라이언트 분기 단순화).

**Response (404 ACTIVE 세션 없음)**
```json
{ "success": false, "message": "해당 sessionCode의 활성 세션이 없습니다" }
```

### `GET /api/attendance/:lectureSessionId/students` (완료, Phase 1)
UWB 검증용 학생 목록 + 학생 UWB 주소 조회. `status="CHECKED_IN"`만 반환 (`ABSENT` 제외).

**Response**
```json
{
  "success": true,
  "data": [
    { "studentId": "STU001", "attendanceId": "...", "studentUwbAddress": "0xAAAA" },
    ...
  ]
}
```
- 미존재 lectureSessionId → 404
- `status != ACTIVE` (이미 종료된 세션) → 400

---

## 🔌 WebSocket 이벤트 프로토콜 (서버 구현 완료, Phase 2)

`socket.io` 기반. 모든 이벤트는 서버 경유 (교수↔학생 직접 통신 없음).

### 연결 (Handshake)
클라이언트가 연결 시 auth payload로 신원/소속 수업 신고:
```json
{
  "userId": "STU001",            // or "PROF001"
  "role": "STUDENT",             // or "PROFESSOR"
  "lectureSessionId": "xxx"
}
```
서버는 필드 존재/role 화이트리스트만 검증한 뒤 `connectionRegistry`에 `userId → socket` 매핑 저장. 같은 `lectureSessionId`로 묶음.

> **Phase 2 결정**: 인증은 단순 신뢰 (토큰 발급 X). 로그인 도입 시 토큰 검증 추가.
> 같은 `userId`로 재연결 시 기존 socket을 disconnect하고 교체 (Wi-Fi 끊김 → 재접속 흔한 케이스).

### 이벤트 흐름 (한 학생 검증 1회분)
```
[교수] ──emit("PREPARE")──▶ [서버] ──emit("PREPARE")──▶ [학생]
                                                          ↓ RangingSession open
[교수] ◀──emit("READY")── [서버] ◀──emit("READY")────── [학생]
   ↓ UWB ranging 시작
   ↓ (동시) UWB 신호 ◄──── 학생 폰
   ↓ ranging 종료
[교수] ──emit("RESULT")──▶ [서버] (DB 저장)
                              ↓
                          emit("DONE") ──▶ [학생]
                                              ↓ RangingSession close
```

### 이벤트 페이로드

#### `PREPARE` (교수 → 서버 → 학생)
```json
{ "attendanceId": "...", "studentId": "STU001" }
```
서버가 `studentId`로 학생 socket 찾아 forward. 학생이 받으면 `RangingSession` open.

#### `READY` (학생 → 서버 → 교수)
```json
{ "attendanceId": "...", "studentId": "STU001" }
```
서버가 같은 `lectureSessionId`의 교수 socket으로 forward. 교수는 그제서야 ranging 시작.

#### `RESULT` (교수 → 서버, 응답 없음)
```json
{
  "attendanceId": "...",
  "studentId": "STU001",
  "distance": 3.2,            // null 허용 (연결 실패 시)
  "connectionFailed": false   // true면 UWB 세션 자체 실패 (재시도 모두 실패)
}
```
서버 판정 로직:
- `connectionFailed == true` → `failureReason = "CONNECTION_FAILED"`, `success = false`
- `distance > 20m` → `failureReason = "OUT_OF_RANGE"`, `success = false`
- 그 외 → `failureReason = null`, `success = true`

서버 부수 효과 (트랜잭션 1개로):
- `ranging_logs`에 문서 1개 추가 (불변 로그)
- `attendance_records` 업데이트:
  - 성공: `uwbFailCount = 0`, `lastRangingAt = now`
  - 실패: `uwbFailCount += 1`, 3 도달 시 `status = "ABSENT"`, `lastRangingAt = now`

#### `DONE` (서버 → 학생)
```json
{ "attendanceId": "...", "status": "CHECKED_IN" }
```
RESULT 처리 직후 서버가 학생에게 emit. 학생은 `RangingSession` close. 학생 자신이 ABSENT 처리됐는지도 여기서 알 수 있음.

### 에러/예외 처리
- 학생이 `PREPARE` 받았는데 `READY` 못 보낸 채 2초 경과 → 교수가 자체 timeout, `connectionFailed: true`로 RESULT 전송
- 학생 socket 끊김 (Wi-Fi 단절 등) → 서버 forward 실패 → 교수도 timeout으로 동일 처리
- WebSocket 자동 재연결 시 별도 재인증 (auth payload 재전송), 진행 중이던 ranging은 그냥 실패로 계상

---

## 🚧 알려진 제약

### 하드웨어 / API
- **UWB 하드웨어 제약**: Pixel 6 Pro+, Galaxy S21+ 등 일부 기기만 지원
- **`androidx.core.uwb:1.0.0-alpha11` scope single-use**: 한 `UwbControllerSessionScope` / `UwbControleeSessionScope`에서 `prepareSession`은 1번만 작동. 두 번째 호출 시 즉시 `RangingResultFailure` emit. 5~30분 wait 무효. **매 측정/사이클마다 새 scope 필요** → Option A (multicast + 사이클당 새 scope)로 우회
- **UWB short address ephemeral**: 매 scope open마다 새 2-byte 주소 할당. 영구 ID 없음 → 매 측정/사이클마다 새 주소를 socket으로 전달 필요
- **`UWB_RANGING` 권한 (Samsung 변종)**: 공식적으론 normal permission인데 Samsung은 사실상 runtime처럼 처리. manifest 선언만으론 `prepareSession`이 silently no-op. **Activity에서 명시적 `requestPermissions` 호출 필수**
- **Samsung S23 Ultra 지원 범위 (확인됨)**:
  - `supportedChannels=[6, 8, 9, 10, 12, 13, 14]` (채널 5는 지원 안 함)
  - `supportedConfigIds=[1, 2, 4, 5]` (1=UNICAST, 2=MULTICAST, 3=PROVISIONED_UNICAST 미지원)
  - `supportedRangingUpdateRates=[1, 2, 3]`
  - `minRangingInterval=100ms`
- **`UwbControllerSessionScope.prepareSession` sessionKey 길이**: `CONFIG_UNICAST_DS_TWR`의 static STS는 **정확히 8 byte**만 허용. 16 byte 사용 시 `IllegalArgumentException`

### 외부 환경
- **BLE 광고 시간 제한**: 기기별로 최대 시간 제약 있음 (대부분 30분 내)
- **Android 14+ Foreground Service 엄격**: Service 전환 시 타입/권한 신경 써야 함
- **UWB는 unicast 시 1:1, multicast 시 1:N (DS-TWR 측정은 그래도 순차)**
- **WebSocket은 인터넷 의존**: 강의실 Wi-Fi/LTE 끊기면 ranging 조율 불가. 학생 폰이 잠깐 망 나가도 그 회차 결석 카운트 1 증가 (3회 누적 전에 복구되면 영향 없음)
- **OEM 백그라운드 킬러**: Samsung/Xiaomi/Huawei는 Foreground Service여도 socket 연결 끊을 수 있음. 자동 재연결 로직 + 사용자에게 배터리 최적화 예외 안내 필요
- **socket.io-client-java 한계**: Java용 클라이언트는 공식 JS 버전보다 기능 적음. Engine.IO 핑/퐁 동작 차이 있을 수 있음 (실측 필요)
- **UWB ranging은 BT와 무관** (S23 Ultra + alpha11 실측 확인): 이전엔 "Samsung은 BLE 보조 채널 사용, BT OFF 시 silently fail"로 추측했으나 실측에서 BT off 상태에서도 ranging 정상 작동. fail-recovery 유도엔 거리(>20m) 또는 socket 단절(Wi-Fi off)을 사용

---

## 🔑 하드코딩 중인 값 (나중에 동적으로)

- `courseId = "CS101"` (MainActivity)
- `professorId = "PROF001"` (MainActivity)
- `studentId = "STU001"` (MainActivity2)
- `NetworkConfig.HOST` (`http://172.30.1.22:3000`) — 개발용 IP. 환경 분리 시 `BuildConfig`로 이동 권장 (`network_security_config.xml`과 dual-source 문제도 같이 해결)
- 휴식 시간 패턴: 매시간 XX:50~XX:00 (`ProfessorAttendanceController.BREAK_START_MINUTE / BREAK_END_MINUTE`) — Phase 5에서 수업별 시간표로 동적화
- ranging 주기: `RANGING_PERIOD_MINUTES = 5` (테스트 중 1로 임시 변경 가능, 풀구현/배포 전 5로 복귀)
- UWB 주소는 더 이상 하드코딩 X — Manager가 scope open 시 동적 추출 (Phase 4 refactor 완료)
- 라인 번호는 리팩토링으로 이동 가능성 있어 생략

---

## 📝 코딩 컨벤션

- **Context**: Manager는 반드시 `getApplicationContext()` 사용
- **로그 태그**: `"BLE"` / `"Attendance"` (Activity), `"BleAdvertise"` / `"BleScan"` (BLE Manager), `"ProfUwbMgr"` / `"StuUwbMgr"` (UWB Manager), `"ProfessorController"` / `"AttendanceController"` (Controller), `"AttendanceSocket"` (WebSocket 클라이언트), `"ProfService"` / `"StuService"` (Foreground Service), `"UWB"` (권한 진단)
- **콜백 스레드**: BLE Manager / socket.io 콜백 모두 main thread로 dispatch (Handler.post). listener 구현체는 main thread 가정 가능.
- **에러 처리**: HTTP 응답 3단계 검증 (isSuccessful → body null → body.isSuccess)
- **서비스 에러 전파**: Node.js 서비스 레이어는 `err.statusCode`를 붙여 throw → 컨트롤러가 그대로 HTTP status로 변환

---

## 💡 참고 오픈소스

- `NordicSemiconductor/Android-nRF-Beacon-for-Eddystone` — Manager 구조 참고
- `AltBeacon/android-beacon-library` — Service 전환 시 백그라운드 스캔 패턴 참고
- `android/connectivity-samples` — 공식 BLE 예제
- `google/uwb-android` — UWB 구현 참고 (나중)
- `socketio/socket.io` — 서버 측 WebSocket 라이브러리
- `socketio/socket.io-client-java` — Android용 socket.io 클라이언트
- `square/okhttp` — socket.io-client-java가 transport로 사용
