# 핸드오프 노트 — Phase 4 실측 부분 통과 / Phase 5 진입 직전

> 전체 정책·아키텍처·스키마: [`CLAUDE.md`](./CLAUDE.md)
> 이 파일은 **다음 세션에서 바로 이어가기 위한 진입점**. 작업 이력은 CLAUDE.md "진행 상황" 섹션 참고.

---

## 🎯 한눈 요약 (2026-05-15 기준)

- **Phase 4 코드 100% + 실측 부분 통과** (시나리오 1, 2, 4 통과 / 3은 재시도 필요)
- **Phase 5 B Service-ready 잔재 8개 코드 완료** (실측은 BT 토글 + #5+#8 묶어서 남음)
- **오늘 직전에 dead code cleanup 완료** — 호출처 없는 메서드/getter/DTO 필드 다수 제거
- **다음 메인 작업**: ① Phase 4 시나리오 3 재실측 → ② **Phase 5 A 보안(로그인 + 수강 검증)** + **B/C (자동 종료 + 시간표 동적화)** 가 핵심

---

## ✅ 지금까지 한 작업 (큰 줄기)

### Phase 1 — 서버 데이터 평면 (완료)
- `/start`, `/check-in` UWB 확장, `/students` 신규
- Firestore `attendance_sessions.uwbParams`, `attendance_records.studentUwbAddress` 추가

### Phase 2 — WebSocket 인프라 (완료)
- 서버: `socket/socketServer.js`, `connectionRegistry.js`, `socketRouter.js`
- 서버 service: `processRangingResult` (판정 + ranging_logs + 3회 누적 ABSENT 트랜잭션)
- Android: `socket.io-client:2.1.2`, `SocketEvent`, `AttendanceSocketClient`, `NetworkConfig`

### Phase 3 — UWB Ranging Manager (완료)
- Kotlin + coroutines + `androidx.core.uwb:1.0.0-alpha11`
- `ProfessorUwbRangingManager`, `StudentUwbRangingManager`

### Phase 4 — Controller 통합 + Option A + fail-recovery (코드 완료)
- 5분 주기 루프, 사이클당 새 scope, sub-cycle fail-recovery (close+300ms+open)
- 주소 일관성 refactor (`UwbAddressUtil` 삭제, scope의 주소를 ranging에 그대로 사용)
- `UWB_RANGING` 런타임 권한 (Samsung 특이사항)
- ABSENT 통보 흐름 (Toast → Service 종료)

### Phase 4 실측 (부분 통과)
- ✅ 시나리오 1 (기본 1사이클) — 통과
- ✅ 시나리오 2 (사이클 전환, 새 P_n 발급) — 통과
- ✅ 시나리오 4 (ABSENT 전이) — 통과
- ⚠️ 시나리오 3 (fail-recovery) — **재시도 필요**. BT off로 유도했지만 **S23 Ultra에서 UWB ranging이 BT와 무관**하다는 게 실측에서 드러남 → 다른 유도법으로 다시 검증해야 함

### Phase 5 F — 진단 로그 정리 (완료)
- `flow event:` Log.v, `multicast prepareSession ...` Log.d, `.catch { Log.e }` 블록, `UWB_RANGING granted=` Log.d 삭제
- `RangingResultFailure` Log.w, 정상 흐름 D 로그, V 로그(collect 시작/취소/종료)는 유지

### Phase 5 B — Service-ready 잔재 8개 (코드 완료)
- 8개 모두 적용:
  1. `@SuppressLint("MissingPermission")` 제거 (기존 적용)
  2. Manager 내부 권한 체크 `hasAdvertisePermission` / `hasScanPermission` (기존 적용)
  3. `adapter.isEnabled()` 체크 (기존 적용)
  4. `SecurityException` try-catch (기존 적용)
  5. BT BroadcastReceiver — Service에 등록, Controller에 `onBluetoothStateChanged()` 위임 (이번 사이클)
  6. `volatile` 상태 (기존 적용)
  7. 콜백 main thread dispatch — BLE Manager `handler.post`, SocketClient `mainHandler.post` (이번 사이클)
  8. `reinit()` adapter 재획득 — BleAdvertiseManager/BleScanManager 양쪽에 추가 (이번 사이클)
- **검증 대기**: BT 토글 시나리오 5-a (광고 중), 5-b (스캔 중), 5-d (Q4 영구 차단). 5-c (ranging 중)는 BT 무관이라 의미 없음

### Dead code cleanup (오늘 직전 마지막 작업)
- **AttendanceSocketClient**: `isConnected()`, 2-arg `emitPrepare`/`emitReady`, 2-arg listener default + dispatch 분기, `jsonOf` 헬퍼 삭제
- **BLE Manager**: `isAdvertising()`, `isScanning()` 삭제 (호출처 없음)
- **Controller**: `AttendanceController.isRunning()`, `ProfessorAttendanceController.getSessionCode()`/`getLectureSessionId()`, 2-arg listener override 삭제
- **DTO 슬림화**:
  - `StartAttendanceData`: `courseId`/`professorId`/`status`/`startTime`/`endTime`/`UwbParams.controllerAddress` 필드+getter 삭제
  - `CheckInData`: `sessionCode`/`checkInTime` 필드+getter 삭제
  - `StudentInfo`: `studentUwbAddress` 필드+getter 삭제
  - `StartAttendanceRequest`/`CheckInRequest`: 모든 getter 삭제 (Gson은 필드로 직렬화), 필드 `final` 변경
- 주석 정정: `UwbParamsAdapter` javadoc, `SocketEvent` 키 주석

---

## 📋 남은 작업 (우선순위 순)

### 🔴 0순위 — Phase 4 시나리오 3 재실측 (fail-recovery)
오늘 실측에서 BT off로 유도 → UWB가 BT와 무관해서 fail이 안 발생함. **다른 유도법으로 다시**:
- **옵션 A (권장)**: 학생 폰을 들고 20m 이상 멀리 이동 → `ranging_logs.failureReason="OUT_OF_RANGE"` 확인
- **옵션 B**: 학생 폰 **Wi-Fi off** (BT 아님) → socket 끊겨서 PREPARE 못 받음 → READY timeout → `connectionFailed=true` → sub-cycle 분할 확인
- **확인 포인트**: `awaitMeasurement 타임아웃` 로그 → `closeScope+sleep(300ms)+openScope` → 다음 학생 새 sub-cycle 첫 학생으로 정상 측정
- 예상 시간: 30분

### 🔴 1순위 — Phase 5 A 보안 (가장 큰 작업, 부정 출석 방지의 핵심)

| 작업 | 설명 | 예상 |
|---|---|---|
| **로그인 시스템** | `users` Firestore 컬렉션. 토큰 발급/검증 미들웨어. 비밀번호 해시 (bcrypt) | 4~6h |
| **수강 명단 검증** | `courses/{courseId}/enrolledStudents`. `/check-in`에서 토큰 검증 + 수강 권한 확인. 못 들으면 403 | 2~3h |
| **하드코딩 ID 제거** | `STU001`/`PROF001`/`CS101` → 로그인 토큰에서 추출. **로그인 도입과 자연스럽게 같이 해결** | 0h (위에 포함) |
| **자기 신원 검증** | 로그인 토큰이 곧 이것 | 0h (위에 포함) |
| **대리 출석 방어 (선택)** | 학생 주소 ephemeral이라 주소 일치 검증 불가 → 서명 기반 (device key로 READY 페이로드 서명, 서버 검증). Phase 5 1순위로 갈지 6순위로 갈지 정책 결정 필요 | 3~5h |

**총 ~6~14h** (대리 출석 방어 포함 여부에 따라).

### 🟡 2순위 — Phase 5 B/C 묶음 (수업 시간표 + 자동 종료 + 휴식 동적화)
로그인 다음 자연스러운 단계. `courses` 컬렉션을 이미 위에서 만들기 때문에 schedule 필드 얹기만 하면 됨.

| 작업 | 설명 | 예상 |
|---|---|---|
| **`courses.schedule` 데이터 모델** | `daysOfWeek` + `startTime` + `durationMinutes` (또는 더 복잡한 weekly schedule) | 1~2h |
| **`/start`에서 schedule lookup** | 현재 시각 기준 expectedEndTime 계산, 응답에 동봉 | 1h |
| **클라이언트 자동 종료 스케줄링** | `ProfessorAttendanceController`가 응답의 `expectedEndTime` 받아 `scheduler.schedule(stopSession, ...)` | 30분 |
| **휴식 시간 동적화** | 현재 하드코딩 `BREAK_START_MINUTE=50` → schedule의 `breakAt` 배열 또는 수업별 패턴 | 1~2h |
| **서버 안전망 (선택)** | 폰이 죽었을 때 cron job이 expired ACTIVE 세션을 CLOSED로 전이 | 2~3h |

**총 ~3~8h**.

**구현 전 결정해야 할 정책**:
1. 지각 시작 시 종료 시각: 시간표대로 vs 시작+duration (권장: 시간표대로)
2. 시간표 외 시간에 시작 누르면: 차단 vs 허용 (권장: 허용)
3. 휴식 시간: 모든 수업 동일 vs 수업별 (권장: schedule에 `breakAt` 배열)

### 🟡 3순위 — Phase 5 D 실측

| 작업 | 설명 |
|---|---|
| **Phase 4 시나리오 3** | 위 0순위 참조 |
| **BT 토글 시나리오** (#5+#8 검증) | 5-a (광고 중 BT off→on), 5-b (스캔 중), 5-d (Q4 reinit 실패) |
| **배터리 실측** | 2~3시간 수업 시뮬레이션, 사이클 N회 반복 hw resource leak 확인 |
| **다기기 호환성** | Pixel + Samsung, Pixel + Pixel (alpha API 차이) |
| **ABSENT 시나리오 UX** | 3 사이클 연속 fail → Toast/Service 종료 전체 흐름 |

**총 ~3~5h**.

### 🟢 4순위 — Phase 5 안정성/UX (시간 남으면)

| 작업 | 설명 | 예상 |
|---|---|---|
| **세션·소켓 복구 로직 검증** | 2~3시간 장기 안정성, Wi-Fi 끊김 재연결 후 ranging 정상 | 2~3h |
| **OEM 백그라운드 킬러 대응** | Samsung/Xiaomi가 FG Service 끊을 수 있음. 배터리 최적화 예외 안내 | 1~2h |
| **교수 실시간 출석 현황** | 학생 명단 + uwbFailCount + 결석 강조 | 3~4h |
| **알림 영역 STOP/PAUSE 버튼** | FG Service notification 액션 | 1h |
| **ABSENT 모달** | 학생 폰 Toast → 모달 다이얼로그 | 1h |

**총 ~8~11h**.

### 🟢 5순위 — Phase 5 C 유연성 잡일

| 작업 | 설명 | 예상 |
|---|---|---|
| **`BuildConfig.SERVER_HOST` 단일 진실원천** | `NetworkConfig.HOST` ↔ `network_security_config.xml` 이중 관리 해소 | 1h |
| **stale placeholder 필드 제거** | `/start` Request `professorUwbAddress`, `/check-in` Request `studentUwbAddress` (현재는 살아있지만 의미 없음). 보안 작업 시 같이 제거 | 30분 |

---

## ⚠️ 진행 시 주의/의문점

### 검증된 사실 (CLAUDE.md 노트와 다름)
- **UWB ranging은 BT와 무관** (S23 Ultra + alpha11): CLAUDE.md의 "Samsung은 BLE 보조 채널 사용, 둘 중 하나 OFF 시 silently 작동 실패" 노트는 부정확한 추측이었음. 실측에서 BT off 상태로도 UWB ranging 정상 작동 확인됨
- → fail-recovery 유도엔 거리 또는 socket 단절 사용 (BT 토글 X)
- → BT BroadcastReceiver (#5+#8)는 BLE 단계 보호용으로만 의미

### 대리 출석 방어가 약함 (Phase 5 A에서 결정 필요)
- 학생 주소가 매 측정마다 동적 → 사전 등록 주소와 일치 검증 불가
- 다른 사람의 학생 폰을 가져가서 출석 가능 (로그인 도입해도 이건 별개)
- 해결책: 로그인 토큰 + READY 페이로드를 device key로 서명, 서버가 검증
- **결정 필요**: 이걸 Phase 5 1순위에 포함시킬지, 후순위로 넘길지

### `#5+#8` reinit의 한계
- BLE 단계 내 BT 토글 시 광고/스캔 **자동 재시작은 안 함**. 사용자가 다시 시작 누르거나 새 세션 시작해야 함
- 현재 코드: reinit으로 advertiser/scanner 참조만 갱신. "혹시 모를 BT 재시작 후에도 manager가 정상 상태로 남게" 정도 보장
- 자동 재시도까지 가려면 추가 정책 결정 필요 (재시작 시점, 횟수 제한 등)

### 사이클 도중 새 학생 출석 (지각생)
- 현재: 사이클 시작 시 명단 스냅샷 → 지각생은 다음 사이클(5분 후)에 처리
- 5분 대기. UX 개선 여지 있음 (시간표 동적화 후 같이 보면 좋음)

### timeout 값 fine-tuning 미완
- 현재: READY 2s / 측정 4s / scope cleanup 300ms / scope op 5s
- 실측에서 부족하면 늘리기. **정상 학생이 4초 안에 측정 못 받는 케이스** 있는지 시나리오 3에서 같이 확인

---

## 🚀 새 세션에서 빠르게 컨텍스트 잡는 path

1. **이 파일** — 현재 상태 + 다음 할 일
2. **[`CLAUDE.md`](./CLAUDE.md)** — 전체 정책·스키마·아키텍처. 특히:
   - "핵심 설계 결정사항" 표
   - "Firestore 스키마"
   - "현재 API 명세"
   - "WebSocket 이벤트 프로토콜"
3. **코드 진입점** (production 흐름 이해):
   - `attendance/ProfessorAttendanceController.java` — 사이클 흐름 + fail-recovery (가장 중요)
   - `attendance/AttendanceController.java` — 학생 측 매 PREPARE 흐름
   - `uwb/ProfessorUwbRangingManager.kt` — Option A multicast API
   - `network/socket/AttendanceSocketClient.java` — 페이로드 형태
   - `attendance-server/socket/socketRouter.js` — 서버 페이로드 forwarding
   - `attendance-server/services/attendanceService.js` — `processRangingResult` (판정 + 3회 ABSENT 트랜잭션)

---

## ⏱️ 다음 세션 권장 순서

| 순서 | 작업 | 시간 |
|---|---|---|
| 1 | Phase 4 시나리오 3 재실측 (옵션 A 또는 B) | 30분 |
| 2 | Phase 5 A 보안 (로그인 + 수강 검증) — 대리 출석 방어 포함 여부 결정 | 6~14h |
| 3 | Phase 5 B/C 묶음 (시간표 + 자동 종료 + 휴식 동적화) | 3~8h |
| 4 | Phase 5 D 실측 (BT 토글 + 배터리 + 다기기) | 3~5h |
| 5 | Phase 5 E UX (실시간 출석 현황 등) | 3~4h |
| 6 | Phase 5 안정성/잡일 | 2~3h |

**Phase 5 전체 ~17~34h**. 학사 일정에 따라 우선순위 조정.

---

## 🔑 핵심 정책 (현재 코드 기준)

- **사이클 = controller scope 1개** (5분 주기, 테스트엔 1분)
- **fail 1번 = sub-cycle 분할** (closeScope + 300ms sleep + 새 openScope)
- **학생 = 매 측정 새 controlee scope** (alpha API single-use 제약)
- **양쪽 주소 ephemeral**: 매 PREPARE/READY로 동적 교환
- **API placeholder (Phase 5에서 제거 예정)**:
  - `/start` Request의 `professorUwbAddress` = `"0x0001"`
  - `/check-in` Request의 `studentUwbAddress` = `"0xAAAA"`
- **timeout**: READY 2s / 측정 4s / scope cleanup 300ms / scope op 5s
- **휴식**: 매시간 XX:50~XX:00 하드코딩 (Phase 5 B/C에서 동적화)
- **콜백 스레드**: BLE Manager / socket.io 모두 main thread dispatch
- **BT 재시작**: BroadcastReceiver로 감지, Manager `reinit()` 호출. 단 자동 재광고/재스캔은 안 함
