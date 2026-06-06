# connection 누적 변경 스팟 점검 (지난 풀 점검 이후)

- **점검 일자**: 2026-06-06
- **도메인**: `connection`
- **점검 유형**: 누적 변경 스팟 점검 (변경 부분 한정)
- **기준점**: 지난 풀 점검 `docs/(2026-05-21) audit-report-connection.md` 이후
- **빌드**: `./gradlew build -x test --no-daemon` → **EXIT 0**
- **종합 판정**: ✅ **PASS** — 신규 결함 0건 (Critical/High/Medium/Low 모두 없음). 정보성 노트 2건.

---

## 0. 점검 대상 변경 식별 (커밋 기준)

| # | 변경 | 커밋 | 날짜 | 비고 |
|---|------|------|------|------|
| 1 | active/pending 조회 분리 + `relation` 컬럼 | `f8b667f` / `93bad11`(V19) | 05-20~21 | ⚠️ 풀 점검과 동시기. relation 컬럼은 배경의 "V15"가 아니라 **`V19__add_connection_relation.sql`** |
| 2 | 거절(refuse) 실시간 알림 추가 | `2a69188` | 05-28 | `ConnectionRefusedEvent` + `handleRefused` |
| 3 | 연결 해제 알림 "거절" 오표시 조사 | `f634813` | 05-31 | **백엔드 무변경** — FE 렌더링/와이어 네이밍 이슈로 종결 |
| 4 | partner 프로필 전체 필드 추가 | `7b5f8ed` | 06-02 | 성별/생년월일/이메일/우편번호 |
| 5 | 알림 채널 추상화 (FCM→dispatcher) | `ee997f9` | 05-31 | 연결 알림 4종 발송 경로 전환 (요청 범위 추가 포함) |

**점검 파일(10)**: `ConnectionService` · `ConnectionNotificationListener` · `ConnectionResponse` · `PendingConnectionResponse` · `ConnectionRequestDto` · `Connection`(entity) · 이벤트 4종(`Requested/Accepted/Refused/Disconnected`) · `V19` 마이그레이션. 연관: `NotificationType`(enum) · `WebSocketEventPublisher` · `StompSubscriptionAuthorizationInterceptor` · `User`(entity getter).

---

## PHASE A. 알림 정합성 (4종 문구) — ✅ PASS

4가지 알림이 이벤트 → 리스너 → 문구 → 와이어 식별자까지 서로 섞이지 않음을 전수 검증.

| 액션 | 발행 이벤트 (ConnectionService) | 수신 리스너 | 수신자 | FCM 본문 | WS 이벤트명 | `data.type` |
|------|--------------------------------|-------------|--------|----------|-------------|-------------|
| **요청** | `ConnectionRequestedEvent` | `handleRequested` | 피보호자 | (relation O) `"{관계} {이름}님이 연결을 요청했어요."`<br>(relation X) `"{이름} 보호자가 연결을 요청했습니다."` | `connection-request` | `CONNECTION_REQUEST` |
| **수락** | `ConnectionAcceptedEvent` | `handleAccepted` | 보호자 | `"피보호자가 연결 요청을 수락했습니다."` | `connection-accepted` | `CONNECTION_ACCEPTED` |
| **거절** | `ConnectionRefusedEvent` | `handleRefused` | 보호자 | `"연결 요청이 거절되었습니다."` | `connection-refused` | `CONNECTION_REFUSED` |
| **해제** | `ConnectionDisconnectedEvent` | `handleDisconnected` | 반대편 당사자 | (by=GUARDIAN) `"보호자가 연결을 해제했습니다."`<br>(by=WARD) `"피보호자가 연결을 해제했습니다."` | `connection-cancelled` | `CONNECTION_CANCELLED` |

- **A1 (이벤트↔리스너↔문구)**: 4종 모두 1:1 정확. 이벤트가 뒤바뀐 경로 없음. ✅
- **A2 (WS 이벤트명)**: 요청/수락/거절은 명칭 일관. 해제는 `connection-cancelled`(내부 enum `CONNECTION_DISCONNECTED`와 불일치) — **신규 결함 아님**. PR #190에서 와이어 호환 유지(breaking 회피) 목적으로 의도적으로 보존, FE에 계약 인계 완료. → 정보성 노트 ①.
- **A3 (액터 표기)**: 해제 본문 주체를 `disconnectedBy`(GUARDIAN/WARD)로 분기, 수신자는 "해제하지 않은 반대편". 일반 해제·탈퇴 정리(`tearDownConnectionsOnWithdrawal`) 양쪽 모두 액터/수신자 정확. ✅
- **A4 (해제 문구 버그 수정 확인)**: 백엔드에 `"거절"` 문구가 해제 경로에 섞인 곳 없음(전수 grep 확인). 해제 본문은 `"...연결을 해제했습니다"`로 정상. #190 조사대로 백엔드 무결 — 거절 커밋(`2a69188`)은 `handleRefused`를 **추가만** 하고 `handleDisconnected`를 건드리지 않음. ✅

**알림 채널 추상화(#5) 영향**: 리스너 4곳이 `fcmService.sendToUser` → `notificationDispatcher.dispatch`로 전환됐으나 WebSocket 발송은 유지. `NotificationType` 4종 모두 `mandatory=false`(연결 알림=선택, 사용자 설정 따름). 기본값 FCM ON이라 기존 동작 보존. SMS 인증번호는 디스패처 미경유라 영향 없음. ✅

---

## PHASE B. partner 프로필 노출 (프라이버시) — ✅ PASS

| 검증 항목 | 결과 | 근거 |
|-----------|------|------|
| **B1** 추가 필드 응답 포함 | ✅ PASS | `partnerPostcode`·`partnerGender`·`partnerBirthDate`·`partnerEmail` 모두 `ConnectionResponse`에 추가, `@Schema` 문서화 |
| **B2** 민감 필드 미노출 | ✅ **PASS (Critical 없음)** | `password`·`provider_id`·refresh/FCM 토큰 등 **응답 매핑에 일절 없음**. DTO는 User의 `id/name/profileImage/phone/address/addressDetail/postcode/gender/birthDate/email`만 선택적으로 추출 |
| **B3** null 안전 | ✅ PASS | `genderName(null)→null` 헬퍼로 enum NPE 방지. birthDate/postcode/email/profileImage 미입력(카카오/기존) 계정은 자연 null |
| **B4** 양쪽 적용 | ✅ PASS | `fromGuardianView`·`fromWardView` 둘 다 동일 필드·동일 ACTIVE 게이팅. 대칭 |

- **노출 게이팅**: 신규 4필드 모두 `revealContact = (status == ACTIVE)` 조건. PENDING/CANCELLED/REFUSED/DISCONNECTED는 전부 `null`. 기존 `partnerPhone/Address`와 동일 정책 확장. ✅
- **PendingConnectionResponse(수락 전 카드)**: partner 전체 필드 **미추가**가 정확 — 전화 마스킹·주소 미노출 최소정보 정책 유지. ✅
- **정보성 노트 ②**: `partnerEmail`은 일반(비카카오) 계정의 **로그인 식별자**이기도 함. ACTIVE 연결(가족 관계) 한정·양방향 노출은 케어 서비스 특성상 의도된 제품 결정(`docs/(2026-06-02)`). 결함 아님 — 제품 의도 재확인용 메모.

---

## PHASE C. 동시성 / 트랜잭션 — ✅ PASS

- **C1 (AFTER_COMMIT)**: 신규 `handleRefused` 포함 리스너 4종 모두 `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("notificationExecutor")`. DB 롤백 시 알림 미발송 보장, 발송 지연이 HTTP 응답에 미포함. ✅
- **C2 (상태 정합성)**: `Connection`에 `@Version`(V21 낙관적 락). 상태 전이는 가드(`refuse`=PENDING만, `disconnect`=ACTIVE만)로 보호 + 동시 전이 시 lost update 방지. 거절/해제는 전제 상태(PENDING vs ACTIVE)가 배타적이라 동시 충돌 시 한쪽은 상태 가드 또는 `OptimisticLockException`으로 거부. ✅
- **C3 (알림 실패 격리)**: 발송이 커밋 후 별도 스레드(@Async)라 트랜잭션에 영향 없음. WS 발송은 `try/catch`로 예외 흡수. ✅
- **C-패턴 일관성**: `ConnectionRefusedEvent`(record) + `handleRefused`가 기존 Accepted/Disconnected 이벤트·리스너와 동일 형태. Spring Event 패턴 일관. ✅

---

## PHASE D. API 계약 — ✅ PASS

- **D1 (active/pending 구조)**: `/ward/connection/active`→`List<ConnectionResponse>`(ACTIVE, 전화·주소 노출), `/ward/connection/pending`→`List<PendingConnectionResponse>`(최소정보+전화 마스킹). DTO 분리는 노출 정책 차이를 반영한 의도된 설계. 둘 다 `ApiResponse.ok` 래핑 일관. ✅
- **D2 (프론트 호환)**: partner 4필드는 `ConnectionResponse`에 **추가만**(append) — 기존 필드 시그니처·순서 불변. 비파괴적 변경. ✅
- **D3 (Swagger)**: 신규 필드·`relation`·세분화 status(`REFUSED`/`DISCONNECTED`) 모두 `@Schema`로 문서화, `allowableValues` 명시. ✅
- **D4 (relation 필드)**: `ConnectionResponse`·`PendingConnectionResponse` 양쪽에 포함, 기존 NULL 데이터는 응답도 null. DTO `@Size(max=10)`가 DB `VARCHAR(10)`와 정합(초과 입력 차단). ✅

---

## 발견 이슈 (심각도별)

- 🔴 Critical: **없음**
- 🟠 High: **없음**
- 🟡 Medium: **없음**
- 🟢 Low: **없음**

### 정보성 노트 (조치 불요)

| # | 내용 | 판단 |
|---|------|------|
| ① | 해제 알림의 WS 이벤트명·`data.type`이 `connection-cancelled`/`CONNECTION_CANCELLED`로, 내부 enum `CONNECTION_DISCONNECTED`·status `DISCONNECTED`와 불일치 | **의도된 와이어 호환 보존**(PR #190). FE 계약 인계 완료. 변경 시 breaking — 건드리지 말 것 |
| ② | `partnerEmail`은 일반 계정의 로그인 식별자이기도 함 | ACTIVE·가족 한정 양방향 노출 = 의도된 제품 결정(`docs/(2026-06-02)`). 제품 의도 재확인용 |

### 점검 중 바로잡은 사실관계

- 점검 배경의 "relation 컬럼 (V15)"는 착오 — 실제 마이그레이션은 **`V19__add_connection_relation.sql`**. (V15는 users 인덱스)
- 변경 #1(active/pending + V19)은 지난 풀 점검(05-21)과 거의 동시기/직전이라 이미 점검 범위였을 가능성이 높음. 이번 재점검에서도 결함 없음 확인.

---

## 종합 판정

✅ **PASS** — 지난 풀 점검 이후 누적된 connection 변경 5건(요청 범위 4 + 알림 채널 추상화) 전부 정합. 4종 알림 비혼동, partner 민감 필드 미노출(Critical 없음), AFTER_COMMIT/낙관적 락 정합, API 비파괴적 추가. 신규 결함 0건. **수정 사항 없음 → 커밋 대상 없음.**
