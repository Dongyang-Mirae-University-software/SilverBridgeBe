# 누적 점검 — SOS(#199) 이후 ~ 이상감지 알림(#217)

> 점검일 2026-07-14 · 기준 커밋 `9c902dc`(dev) · **점검만 수행, 코드 수정 없음**
> 배포 상태: vkcs-linux(CD) `9c902dc` / gosky(수동) `9c902dc` — 둘 다 스키마 V31

## 1. 대상

| PR | 내용 | 이번 점검 |
|---|---|---|
| #199·#200 | 피보호자 긴급 SOS | 회귀만 (기점검: `(2026-06-09) audit-spot-check-ward-sos.md`) |
| #209·#211 | 문의하기(inquiry) | 회귀만 (기점검: `(2026-07-03) audit-spot-check-inquiry.md`) |
| #213 | camera 도메인(소유권·SessionID) | ★ 신규 점검 |
| #214 | 이상감지 수신·판정·이력(1단계) | ★ 신규 점검 |
| #215 | 테이블 단수형 통일(V31) | ★ 신규 점검 |
| #216 | Gradle 빌드 캐시(CD) | ★ 신규 점검 |
| #217 | 이상감지 알림 2단계 | ★ 신규 점검 |

## 2. 엔드포인트 × 역할 인가

| 엔드포인트 | 역할 강제 | 방식 |
|---|---|---|
| `POST/GET/PATCH/DELETE /api/ward/camera[/{id}]` | WARD | 클래스 `@PreAuthorize("hasRole('WARD')")` |
| `POST /api/ward/sos` | WARD | 클래스 `@PreAuthorize` |
| `GET /api/guardian/cameras` | GUARDIAN | 클래스 `@PreAuthorize` |
| `POST/GET /api/guardian/inquiry[/{id}]` | GUARDIAN | 클래스 `@PreAuthorize` |
| `GET/POST /api/admin/inquiry[/{id}][/answer]` | ADMIN | `SecurityConfig`: `/api/admin/**` → `hasRole("ADMIN")` |
| `GET/PUT /api/user/me/notification-settings` | 인증 사용자 | `anyRequest().authenticated()` + `@AuthenticationPrincipal` |
| `POST/DELETE /api/notifications/fcm-token` | 인증 사용자 | 동일 |

## 3. IDOR 검증 — **전 도메인 PASS** ★

| 도메인 | 결과 | 근거 |
|---|---|---|
| camera | ✅ PASS | `CameraService.getOwnedCamera()` — `wardId` 불일치 시 **404 위장**(존재 노출 차단). `findOwnedByDeviceId`도 본인 소유만 인정 |
| inquiry | ✅ PASS | `InquiryService.getMyInquiry()` — 작성자 불일치 시 404 위장. 목록은 `userId` 스코프 |
| anomaly | ✅ PASS | 수신자를 **서버가 결정**(`getActiveGuardianIds(wardId)` + 본인). 클라이언트 입력이 수신자 결정에 개입하지 않음 |
| WebSocket | ✅ PASS | `StompSubscriptionAuthorizationInterceptor` — `/topic/{userId}/...`의 `{userId}`와 세션 userId 불일치 시 구독 거부. 신규 `anomaly-detected` 토픽도 **자동 보호**(이벤트명 화이트리스트 불필요) |

기타 보안: **SQL 인젝션 없음**(네이티브 쿼리 0건, 관리자 검색은 JPQL 명명 파라미터 + `CONCAT` LIKE) · **시크릿 평문 없음**(`AI_API_KEY`·`KAKAO_CLIENT_SECRET` 모두 env 주입, #216 캐시는 BuildKit 캐시 마운트라 이미지 레이어에 남지 않음) · **PII 로그 과다 노출 없음**(로그는 userId·sessionId 수준, 이름·전화 미기록).

## 4. 발견 이슈

### 🟠 H-1 · AI 세션 재시작 시 구독이 복구되지 않는다 (조용한 침묵)

`domain/anomaly/client/AiLiveStreamSubscriber.java:138,146` · `subscribedSessions`는 **WS 재연결 시에만 clear**된다(`afterConnectionEstablished`·`afterConnectionClosed`).

- AI 스펙상 `live_streams`는 **세션 생성/종료 시** 전체 연결에 broadcast된다(`프로젝트_설명_AI서버.txt:195`). 우리는 이 목록을 받아 **아직 구독 안 한 세션만** subscribe한다.
- **문제 1** — 카메라의 `session_id`는 영속이라 iPad가 끊었다 같은 sessionId로 다시 붙으면 AI 입장에선 새 세션이지만, 우리는 `subscribedSessions.contains(sessionId)`가 true라 **subscribe를 다시 보내지 않는다**. AI가 세션 종료 시 구독을 정리했다면 그 뒤로 `latest_analysis`가 오지 않고, **에러 없이 이력·알림이 0건**이 된다.
- **문제 2** — 이미 스트리밍 중인 세션을 **나중에 카메라로 등록**하면, 그 세션의 생성 broadcast는 이미 지나갔으므로 **WS 재연결(또는 앱 재시작) 전까지 영원히 구독되지 않는다.**
- **권고**: ① `live_streams`/`session_status`에서 사라진·종료된 세션을 `subscribedSessions`에서 제거 ② 카메라 등록 이벤트 시 `{"action":"list"}` 재요청으로 재구독 트리거.

### 🟡 M-1 · `StompEventListener` NPE — WS 접속 감사 로그가 전혀 남지 않는다

`global/websocket/StompEventListener.java:20,27` · `accessor.getSessionAttributes()`가 null인 경우를 방어하지 않아 **접속·해제마다 NPE**. gosky 운영 로그에서 실제 발생 확인(`Error publishing SessionConnectedEvent` + 스택). 접속 자체는 되지만 ① 연결/해제 로그가 유실되고 ② ERROR 스택이 로그를 오염시킨다. `StompSubscriptionAuthorizationInterceptor`는 같은 상황을 null 체크로 방어하고 있어 **동일 패턴을 적용하면 된다.**

### 🟡 M-2 · camera 도메인 인가 회귀 테스트 부재

SOS에는 `WardSosControllerSecurityTest`(역할 강제 검증)가 있으나, camera는 `CameraServiceTest` 하나뿐이다. **소유권 검증(IDOR)·역할 제한이 테스트로 고정돼 있지 않아**, 리팩터 중 `@PreAuthorize`나 `getOwnedCamera` 호출이 빠져도 테스트가 잡아내지 못한다. (현재 코드는 PASS — 회귀 방지 장치가 없다는 뜻)

### 🟡 M-3 · `/api/guardian/cameras`만 복수형

다른 신규 엔드포인트(`/api/ward/camera`, `/api/guardian/inquiry`, `/api/admin/inquiry`)는 전부 단수형인데 이것만 복수형이다. FE 계약이 이미 물려 있으면 문서에 예외로 못박고, 아니면 정렬 권장.

### 🟢 L-1 · subscribe 페이로드를 문자열 연결로 생성

`AiLiveStreamSubscriber.java:145` — `"{\"action\":\"subscribe\",\"sessionId\":\"" + sessionId + "\"}"`. sessionId는 AI가 준 값이라 따옴표가 섞이면 JSON이 깨진다(현재 형식상 위험은 낮음). `ObjectMapper`로 직렬화 권장.

### 🟢 L-2 · 관리자 검색 keyword의 LIKE 와일드카드 미이스케이프

`InquiryRepository.searchForAdmin` — `%`·`_`가 그대로 패턴으로 해석된다. **인젝션은 아니고**(파라미터 바인딩) 검색 결과가 의도와 달라질 뿐.

### 🟢 L-3 · `AnomalyNotificationCooldown` 단위 테스트 없음

SOS는 `SosNotificationCooldownTest`가 있다. fail-open·본인/보호자 TTL 분기가 테스트로 고정돼 있지 않다.

## 5. 통과 확인 (회귀·구조)

- **#215 rename**: 엔티티 `@Table`이 전부 단수형(`users`만 예외 유지), **네이티브 쿼리 0건**, 코드에 남은 복수형은 전부 주석/문서 문자열 → 런타임 위험 없음 ✅
- **#216 빌드 캐시**: BuildKit 캐시 마운트는 `GRADLE_USER_HOME`에만 적용되고 `/app/build`는 제외(주석에 근거 기록됨) → **jar 산출물이 캐시에 숨지 않음**. 시크릿 유입 경로 없음 ✅
- **알림 정책 보존(#217)**: `SETTINGS_ONLY` 5종·`WARD_SOS` 폴백 동작 무변경, 테스트 가드(`정책_가드`)로 고정 ✅
- **N+1**: 관리자 문의 목록·보호자 카메라 목록 모두 `findAllById` 배치 조회 ✅
- **트랜잭션·이벤트**: 알림은 전부 AFTER_COMMIT + `@Async("notificationExecutor")`, 수신자별 try/catch 격리 ✅

## 6. 종합 판정

**조건부 PASS** — 보안(인가·IDOR·시크릿·인젝션)은 전 영역 통과. 다만 **H-1은 이상감지 기능이 "에러 없이 안 오는" 상태로 빠질 수 있는 결함**이라 통합 테스트 전에 고치는 것을 권한다.

**우선 수정 순서**: H-1(구독 상태 복구) → M-1(STOMP NPE) → M-2(camera 인가 테스트) → M-3/L-*

## 7. 운영 현황 (참고 — 결함 아님)

- gosky `camera` 테이블 **0행** → 구독 대상 없음 → `anomaly_event` 0건. FE가 카메라 등록 API를 아직 연동하지 않은 상태.
- AI 라이브 세션은 `stream_001`(iPad) 하나이며, 백엔드 발급 형식(`ward_xxxxxx_xxx`)이 아니다. **FE가 발급받은 sessionId로 스트리밍하도록 수정된 뒤에야 통합 검증이 가능**하다(H-1도 이때 함께 검증).
