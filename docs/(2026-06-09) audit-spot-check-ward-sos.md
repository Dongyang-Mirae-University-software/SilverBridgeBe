# 피보호자 SOS 긴급 알림 기능 — 스팟 점검

- **점검 일자**: 2026-06-09
- **대상 커밋**: `d66343f feat(sos): 피보호자 SOS 긴급 알림 기능 추가` (PR #199, 머지 `a97322c`)
- **점검 성격**: 생명 관련 긴급 기능 — **"알림이 확실히 가는가"** 최우선
- **빌드/테스트**: `./gradlew build -x test` 성공 / SOS 단위 테스트 3클래스 `BUILD SUCCESSFUL`
- **결론**: ✅ **PASS** — 🔴 Critical / 🟠 High **0건**. 🟡 Medium 2 · 🟢 Low 2 (모두 권고/설계 결정, 머지 차단 사유 아님)

---

## 1. 점검한 파일

| 구분 | 파일 |
|---|---|
| 컨트롤러 | `domain/sos/controller/WardSosController.java` |
| 서비스 | `domain/sos/service/SosService.java` |
| 리스너 | `domain/sos/listener/SosNotificationListener.java` |
| 엔티티 | `domain/sos/entity/SosEvent.java` |
| 이벤트 | `domain/sos/event/SosTriggeredEvent.java` |
| 리포지토리 | `domain/sos/repository/SosEventRepository.java` |
| DTO | `domain/sos/dto/SosResponse.java` |
| 마이그레이션 | `db/migration/V26__add_sos_events.sql` |
| 연동 변경 | `notification/dispatch/NotificationType.java`(WARD_SOS 추가) · `NotificationDispatcher.java`(필수 분기) · `connection/service/ConnectionService.java`(`getActiveGuardianIds`) |
| 검증 참조 | `global/websocket/WebSocketEventPublisher.java` · `StompSubscriptionAuthorizationInterceptor.java` · `global/config/AsyncConfig.java` |
| 테스트 | `SosServiceTest` · `SosNotificationListenerTest` · `WardSosControllerSecurityTest` · `NotificationDispatcherTest`(+필수분기) |

---

## 2. PHASE A. 알림 확실성 (최우선) ★★★

| 항목 | 판정 | 근거 |
|---|---|---|
| **A1. 필수 알림 보장** ★ | ✅ PASS | `NotificationType.WARD_SOS(true)` = mandatory. `NotificationDispatcher.dispatch`가 `type.isMandatory()` 시 사용자 설정(`settingService.enabledChannels`)을 **건너뛰고** `MANDATORY_CHANNELS`(FCM)로 강제 발송. 보호자가 FCM/알림을 꺼도 SOS는 발송됨. "선택 알림" 오분류 없음(테스트 `isMandatory()==true` 고정). |
| **A2. 보호자 전원 발송** | ✅ PASS | `getActiveGuardianIds`가 `findByWardIdAndStatus(wardId, ACTIVE)`로 조회 → 리스너가 **for 루프로 전원** 발송. `PENDING`/`CANCELLED`/`REFUSED`/`DISCONNECTED`는 쿼리에서 제외. 한 명만 보내고 끝나지 않음(테스트: 2명 각각 WS+dispatch 검증). |
| **A3. 실패 격리** ★ | ✅ PASS | ① **보호자별**: 각 보호자 발송을 `try/catch`로 감싸 1명 실패가 나머지를 막지 않음(테스트 `실패격리`). ② **채널별**: `WebSocketEventPublisher.sendToUser`가 **자체 try/catch로 예외를 삼켜** 정상 반환 → WS 실패가 뒤따르는 FCM dispatch를 막지 않음. dispatcher 내부도 채널별 try/catch. ③ **이력 롤백 무관**: 발송은 `AFTER_COMMIT`+`@Async` 별도 스레드/트랜잭션 → 발송 실패가 `sos_events` 저장을 롤백시키지 않음. |
| **A4. 이력 보존** ★ | ✅ PASS | `@TransactionalEventListener(AFTER_COMMIT)` — 이력 **커밋 후**에만 리스너 동작. 알림이 전부 실패해도 `sos_events` 행은 이미 영속. 엔티티/마이그레이션 주석에 "진실원본, 항상 남는다" 명시. |
| **A5. 메시지 명확성** | ✅ PASS | 본문 `"{wardName}님이 긴급 도움을 요청했습니다."` — 피보호자 이름 포함. WS payload·FCM data 모두 `wardId`/`wardName`/`sosEventId` 전달 → 보호자가 누구의 SOS인지 즉시 식별. |

### A 추가 강점 — 큐 포화 시 알림 유실 방지
`AsyncConfig.notificationExecutor`가 `CallerRunsPolicy`를 사용 → 스레드풀/큐(코어2·맥스10·큐100) 포화 시 **호출 스레드가 직접 실행**하여 작업이 버려지지 않음. 긴급 알림 폭주 상황에서도 드롭 없음. (생명선 기능에 적합한 설정)

---

## 3. PHASE B. 인가 / 검증

| 항목 | 판정 | 근거 |
|---|---|---|
| **B1. WARD 역할만** | ✅ PASS | `WardSosController` 클래스에 `@PreAuthorize("hasRole('WARD')")`. 테스트가 `@EnableMethodSecurity`로 AOP 직접 검증(WARD 허용). |
| **B2. 본인만 자기 SOS** | ✅ PASS | `wardId`를 `@AuthenticationPrincipal`(JWT subject)에서만 취득 — 요청 바디 없음. 타인 ID 주입 경로 부재 → 사칭 불가. 서비스도 `findById(wardId)`로 본인 행만 사용. |
| **B3. 보호자/관리자 차단** | ✅ PASS | `hasRole('WARD')` 단일 조건 → GUARDIAN/ADMIN은 403(`AccessDeniedException`). 테스트 `비WARD_거부`로 GUARDIAN 차단 확인. |
| **WS 구독 인가** | ✅ PASS | `/topic/{guardianId}/sos-triggered`는 `StompSubscriptionAuthorizationInterceptor`의 범용 `{userId}==세션` 검증으로 자동 보호(이벤트명 화이트리스트 불요). 타 보호자 SOS 토픽 도청 차단. |
| **B4. 중복 SOS 쿨다운** | ⚠️ 부재 | 디바운싱/쿨다운 없음 → 🟡 Medium(아래 이슈 M-2 참조). |

---

## 4. PHASE C. 구조 / 계약

| 항목 | 판정 | 근거 |
|---|---|---|
| **C1. @Transactional 경계** | ✅ PASS | `trigger()`만 `@Transactional`(저장+이벤트 발행). 보호자 조회·발송은 트랜잭션 밖(AFTER_COMMIT 리스너)으로 분리 → 응답 즉시 반환. |
| **C2. 이벤트 AFTER_COMMIT** | ✅ PASS | 발행은 트랜잭션 내, 처리는 `AFTER_COMMIT`. 커밋 실패 시 미발송(거짓 알림 방지). |
| **C3. 기존 패턴 일관성** | ✅ PASS | `ConnectionNotificationListener`와 동일하게 `@Async("notificationExecutor")`+`AFTER_COMMIT`, `WebSocketEventPublisher.sendToUser` 사용. 보호자 조회를 connection 도메인(`getActiveGuardianIds`)에 두어 도메인 경계 준수(global 오염 없음). |
| **HTTP 상태코드** | ✅ PASS(경미) | `200 OK` + `ApiResponse.ok`. 리소스 생성 관점에선 `201`이 더 정확하나 200도 허용 범위 → 🟢 Low(L-1). |
| **응답 포맷** | ✅ PASS | `ApiResponse<SosResponse>` 준수, `sosEventId`/`triggeredAt` 반환. |
| **Swagger 정확성** | ✅ PASS | `@Operation`에 동작·필수 알림·범위 밖(119/직접전화 프론트) 상세 기술. 실제 동작과 일치. |
| **C6. N+1** | ✅ PASS | `getActiveGuardianIds`는 단일 쿼리 후 `Connection::getGuardianId`(자체 필드) 매핑 — 추가 쿼리 없음. 리스너는 guardianId 문자열만 사용(보호자 User 로드 안 함). dispatcher의 per-user recipient 해석은 발송 본질상 불가피(N+1 아님). |
| **C7. sos_events 저장** | ✅ PASS | 단순 IDENTITY insert. `idx_sos_events_ward_created(ward_id, created_at DESC)` 인덱스. `ON DELETE SET NULL`(탈퇴 시 익명 보존, access_logs 정책 일치). V26=최신 버전, 충돌 없음. |

---

## 5. PHASE D. 테스트

| 필수 케이스 | 커버 | 위치 |
|---|---|---|
| SOS 발생 → 이력 저장 | ✅ | `SosServiceTest.trigger_저장_이벤트발행_응답` (wardId 캡처 검증) |
| ACTIVE 보호자 전원 알림 | ✅ | `SosNotificationListenerTest.보호자전원발송` (2명 각각 검증) |
| 필수 알림 설정 무시 ★ | ✅ | `필수알림_설정무시` (WARD_SOS dispatch + `isMandatory()==true`) |
| 보호자 1명 실패가 나머지 안 막음 ★ | ✅ | `실패격리` (GD0001 throw → GD0002 정상) |
| 알림 전체 실패해도 이력 남음 ★ | ✅(구조) | AFTER_COMMIT으로 구조적 보장 + 리스너 실패 격리 테스트. (E2E는 통합 테스트 영역) |
| WARD 아니면 403 | ✅ | `WardSosControllerSecurityTest.비WARD_거부` |
| 보호자 없을 때(이력만) | ✅ | `보호자없음` (`verifyNoInteractions`) |
| 미존재 사용자 | ✅ | `trigger_사용자없음` (USER_NOT_FOUND, 저장·발행 없음) |

테스트 품질 양호 — 핵심 신뢰성 케이스(필수/전원/격리/이력) 모두 단위 테스트로 고정.

---

## 6. 발견 이슈

### 🔴 Critical — 없음
알림 미발송·이력 유실·인가 우회 경로 발견되지 않음.

### 🟠 High — 없음
실패 격리 결함·즉각적 알림 폭탄 결함 없음.

### 🟡 Medium

**M-1. SOS 필수 채널이 FCM 단독 — 오프라인 보호자 미수신 가능 (신뢰성 권고)**
- `NotificationDispatcher.MANDATORY_CHANNELS = {FCM}`. SMS 채널(`SmsNotificationChannel`)은 구현돼 있으나 SOS 필수 채널에서 제외.
- 시나리오: 보호자가 ① FCM 토큰이 없거나/만료(미로그인·재설치·토큰 갱신 지연)됐고 ② 보호자 웹이 닫혀 WS 미구독이면 → **생명 관련 SOS를 전혀 못 받음**. FCM 푸시·WebSocket 모두 best-effort(앱/웹 상태 의존)이며 SMS가 긴급 상황에서 가장 도달률 높은 채널.
- **권고**: SOS 같은 mandatory 타입에 한해 `MANDATORY_CHANNELS`에 SMS 추가를 검토(타입별 채널셋 분리). 단 Solapi 발송 비용·도달 SLA를 고려한 **설계 결정**이며, FCM-우선 MVP도 합리적 선택. 즉시 수정 필수는 아니나 감사 최우선 목표("알림 확실성") 관점의 최우선 개선 후보.

**M-2. 중복 SOS 쿨다운/디바운싱 없음 (B4)**
- 연타 시 보호자에게 알림이 매 탭마다 발송 → 다수 보호자 대상 알림 폭탄·alarm fatigue 가능.
- 단, ① 보호자별 실패 격리가 있어 폭주가 다른 보호자를 막진 않고 ② 빈도는 사람 탭 속도로 제한되며 ③ **긴급 기능 특성상 진짜 재요청을 막는 건 위험** → High 아님.
- **권고**: 이력은 전부 남기되 **알림만** 짧은 쿨다운(예: 동일 ward 직전 N초 내 알림은 합치기/생략). 1차로 프론트 버튼 디바운싱 적용 권장. 차단형(429)은 지양 — 긴급 재요청 봉쇄 위험.

### 🟢 Low

**L-1. HTTP 200 vs 201**
- `sos_events` 리소스를 생성하므로 REST상 `201 Created`가 더 정확. 200도 허용 범위이고 기존 프로젝트 관례를 따른 것으로 보여 수정 불요(정보성).

**L-2. wardName null 가드 없음**
- `User.name`이 null이면 본문이 `"null님이 긴급 도움을 요청했습니다."`가 됨. 가입 시 name 필수라 실무상 발생 가능성 낮음. 방어적 폴백(예: 이름 없으면 "보호 대상자") 정도만 선택적 고려.

---

## 7. 종합 판정

✅ **PASS** — 머지 상태 양호, 추가 조치 없이 운영 가능.

- **알림 확실성(최우선)**: A1~A5 전 항목 PASS. 필수 알림 강제 발송, ACTIVE 보호자 전원, 보호자별·채널별 실패 격리, AFTER_COMMIT 이력 보존, `CallerRunsPolicy` 드롭 방지까지 생명선 기능에 요구되는 신뢰성 설계가 갖춰짐.
- **인가/구조/테스트**: 모두 PASS. WARD 전용·본인 한정·WS 토픽 인가, 기존 알림 패턴과 일관, 핵심 신뢰성 케이스 단위 테스트 고정.
- **개선 후보(비차단)**: M-1(SMS 폴백 검토)·M-2(알림 쿨다운)은 신뢰성을 한 단계 더 높이는 **선택적 강화**이며 현재 구현의 결함은 아님.

---

## 8. 이슈별 컨벤셔널 커밋 메시지 초안 (적용 시)

> 아래는 권고 사항 반영 시 사용할 초안. 본 점검은 **코드 변경 없음**(점검만).

```
feat(notification): SOS 등 필수 알림에 SMS 폴백 채널 추가

긴급 SOS는 FCM 단독 발송이라 보호자가 오프라인(토큰 만료/웹 닫힘)이면
미수신 위험. mandatory 타입에 한해 SMS를 강제 채널로 추가해 도달률 보강.
```

```
feat(sos): 보호자 SOS 알림 쿨다운 추가 (이력은 전량 보존)

연타 시 보호자 알림 폭탄 방지. 동일 ward 직전 N초 내 알림은 합치되
sos_events 이력은 모두 기록해 긴급 재요청을 봉쇄하지 않음.
```

---

## 9. 조치 결과 — 이슈 4건 전부 반영 (2026-06-09, branch `feature/ward-sos-audit-fixes`)

| # | 반영 내용 | 변경 파일 |
|---|---|---|
| **M-1** | **조건부 SMS 폴백** — 필수 알림 기본 FCM, 보호자에게 FCM 토큰이 없을 때만(`fcmService.hasToken==false`) SMS 추가(`NotificationDispatcher.mandatoryTargets`). 정상(토큰 보유) 보호자는 SMS 비용 0, '푸시가 닿지 않는 보호자'만 SMS로 보강. ※초기엔 항상 FCM+SMS로 구현했다가, 사용자 결정(비용↔도달률)으로 **"토큰 없을 때만 SMS"**로 전환. | `NotificationDispatcher` · `FcmService.hasToken`(신규) · `FcmTokenRepository.existsByUserId`(신규) |
| **M-2** | **알림 쿨다운**(`SosNotificationCooldown`, Redis `SET NX EX` 30초). 리스너가 보호자 조회 후 `tryAcquire`로 **알림만** 생략 — `sos_events` 이력은 항상 저장, 긴급 재요청 차단(429) 안 함. Redis 장애 시 **fail-open**(발송). | `SosNotificationCooldown`(신규) · `SosNotificationListener` |
| **L-1** | HTTP `201 Created` 반환(이력 리소스 생성). | `WardSosController` |
| **L-2** | `wardName` 공백 시 폴백 `"보호 대상자"` — "null님이..." 방지. | `SosService` |

**테스트**: `SosNotificationCooldownTest`(허용/생략/fail-open 3), `SosServiceTest.trigger_이름없음_폴백`, `SosNotificationListenerTest.handleSosTriggered_쿨다운_알림생략`, 디스패처 `필수알림_FCM토큰있음_FCM만`/`필수알림_FCM토큰없음_SMS폴백`. → SOS·디스패처 테스트 + `./gradlew build -x test` **BUILD SUCCESSFUL**.

**불변 규칙 유지**: 쿨다운·폴백 모두 "이력은 무조건 남는다", "긴급 재요청 차단 안 함", "인프라 장애가 SOS 알림을 막지 않음(fail-open)" 원칙을 깨지 않음.
