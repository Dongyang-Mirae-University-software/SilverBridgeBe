# 전체 API 종합 점검 — 세션 2 (connection + notification + SOS)

- **점검 일자**: 2026-06-11 · **세션**: 2/3 · **대상**: connection 10 + notification 4 + SOS 1 = 15 엔드포인트 (+ 디스패치 인프라)
- **점검 성격**: connection=회귀 위주(기준선 05-21 + 스팟 06-06 PASS) / **notification=정밀(풀 점검 이력 없음)** / SOS=스팟(06-09)+수정 PR #200 반영 확인
- **환경**: dev 145c176, 직전 전체 빌드 통과본과 동일 트리
- **산출물**: 본 보고서 + progress.md (CSV는 세션 1에서 제거 결정에 따라 미생성)

---

## 1. 인가 매트릭스 ★

전제 검증: `@EnableMethodSecurity` 존재 + JWT 필터가 `ROLE_{role}` 권한 부여 → 클래스 레벨 `@PreAuthorize` 정상 동작 확인.

| # | 엔드포인트 | 역할 | 소유 검증 | 판정 |
|---|---|---|---|---|
| 1–2 | GET `/api/guardian/connection/{select,requests}` | GUARDIAN | principal 기반 조회 | ✅ |
| 3 | POST `/api/guardian/connection/request` | GUARDIAN | 역할 쌍·자기연결·중복 검증 | ✅ |
| 4–5 | DELETE `/api/guardian/connection/{cancel,disconnection}/{id}` | GUARDIAN | `getConnectionForGuardian` (guardianId 일치 강제) | ✅ IDOR 없음 |
| 6–7 | GET `/api/ward/connection/{active,pending}` | WARD | principal 기반 조회 | ✅ |
| 8–10 | POST accept / DELETE refusal·disconnection `{id}` | WARD | `getConnectionForWard` + accept는 `initiatedBy` 자기수락 차단 | ✅ IDOR 없음 |
| 11 | POST `/api/notifications/fcm-token` | 인증 | principal로 등록 + rate limit | ⚠️ M-S2-2 (소유자 재할당 없음) |
| 12 | DELETE `/api/notifications/fcm-token` | 인증 | **token 소유 검증 없음** | ⚠️ L-S2-3 |
| 13–14 | GET/PUT `/api/user/me/notification-settings` | 인증 | principal만 사용 | ✅ |
| 15 | POST `/api/ward/sos` | WARD | principal만 사용 + `WardSosControllerSecurityTest` | ✅ |

## 2. PHASE A — 보안·인가·알림 확실성

### 🟠 H-S2-1 · 만료 FCM 토큰 정리가 구조적으로 실패 → SOS SMS 폴백 영구 무력화

- `FcmService.sendMulticast` → `cleanupInvalidTokens` → `fcmTokenRepository.deleteByToken()` 경로에 **활성 트랜잭션이 없다**: 호출 스택이 리스너(`@Async`, AFTER_COMMIT — 원 트랜잭션 종료 후) → 디스패처 → 채널 → `sendToUser`(무트랜잭션)이고, `deleteByToken`은 derived delete라 트랜잭션 필수(`TransactionRequiredException`). `FcmService.deleteToken`(로그아웃)·`deleteAllTokens`(탈퇴)는 `@Transactional`이 있어 정상이고, **cleanup 경로만 빠졌다**.
- **연쇄 효과**: ① 만료 토큰 영구 잔존(정리 로직 사실상 dead code) ② `hasToken()` 항상 true → 디스패처의 SOS SMS 폴백 조건(토큰 없음)이 영원히 미충족 → **앱 삭제/토큰 만료 보호자에게 긴급 SOS가 푸시·SMS 모두 영구 유실** ③ cleanup 예외가 채널 send 실패로 기록돼 로그 오염.
- 단, 같은 멀티캐스트의 유효 토큰 발송은 cleanup 이전에 이미 완료되므로 정상 디바이스 푸시는 영향 없음.
- **검증 노트**: 정적 분석 결론(런타임 재현 미수행). `FcmService` 테스트가 없어 회귀망에도 안 걸림(§5).
- **권장**: `FcmTokenRepository.deleteByToken`에 `@Transactional`(+`@Modifying @Query` 벌크화 권장) 또는 cleanup을 프록시 경유 `@Transactional` 빈으로 분리. self-invocation이라 `FcmService` 내부 메서드 어노테이션만으론 무효.

### 🟡 M-S2-1 · presence 기반 SMS 폴백 — 첫 SOS 유실 갭 (H-S2-1 수정 후에도 잔존)

- 폴백 판단이 "토큰 존재 여부"라 토큰이 DB에 있으나 무효인 보호자는: FCM 실패 + SMS 미발송 → **해당 SOS 유실**. (H-S2-1 수정 시) cleanup이 토큰을 지워 **다음** SOS부터 SMS 폴백. WebSocket은 접속 중일 때만 유효.
- **권장(설계 결정)**: `FcmNotificationChannel.send`가 발송 결과(성공 0건)를 노출 → 디스패처가 결과 기반 폴백. 또는 "긴급 알림은 FCM+SMS 동시 발송" 단순화(비용 증가와 트레이드오프).

### 🟡 M-S2-2 · FCM 토큰 등록 시 소유자 재할당 없음 (공유 디바이스)

- `registerToken`: 토큰이 이미 존재하면 **타 사용자 소유여도 무시**. 가족 공유 폰(시니어 타겟에서 현실적)에서 A 로그아웃(FE가 DELETE 미호출) 후 B 로그인 → 토큰이 A 소유로 잔존 → **A의 연결·SOS 알림이 B가 쓰는 디바이스에 계속 표시**(알림 오수신/PII성 노출), B는 자기 알림 미수신.
- **권장**: 존재 토큰의 userId가 다르면 소유자 갱신(upsert). 서버측 로그아웃(`AuthService.logout`)은 FCM 토큰을 건드리지 않으므로 FE 협조에만 의존하는 현 구조 보완 필요.

### 🟢 L-S2-3 · FCM 토큰 삭제에 소유 검증 없음
- `deleteToken(token)`이 인증만 요구하고 소유자 비교 없음 — 타인 토큰 값을 알면 삭제 가능(토큰 추측은 비현실적 → 실질 위험 낮음). `deleteByTokenAndUserId(token, userId)` 권장. 부수: delete에는 register와 달리 rate limit 없음.

### ✅ 확인된 안전 사항 (회귀 없음)
- connection IDOR 방어(소유 헬퍼 2종)·자기수락 차단·INACTIVE 대상 미존재 처리·낙관락(@Version)·REFUSED/DISCONNECTED 세분화 — 기준선/스팟 수정 전부 유지.
- **알림 비대칭 정책 유지**: 거절→알림O / 취소·탈퇴 PENDING 종료→무알림(`tearDownConnectionsOnWithdrawal` cancel 경로).
- **SMS 인증번호 디스패처 미경유** 구조 보장 유지(NotificationType 주석 명시, 인증 SMS는 SmsSender 직행).
- SOS 스팟(06-09) 수정 4건 반영 확인: 201 Created ✓ / 이름 폴백("보호 대상자") ✓ / 쿨다운(SET NX EX 원자적, 30초) ✓ / fail-open(쿨다운 인프라 장애 시 발송) ✓. 쿨다운이 이력 보존 원칙을 깨지 않음 ✓.
- 어제 M-S1-1 수정(탈퇴 리스너 try/catch) 반영 확인 ✓. `sos_events` FK SET NULL·`user_notification_settings` CASCADE — **hard delete 탈퇴 차단 없음** ✓.
- 필수/선택 알림 분류 정확(WARD_SOS만 mandatory), 설정 무시 강제 발송 ✓. 채널·보호자 단위 실패 격리 2중 try/catch ✓.

### 동시성
- SOS 연타: 쿨다운 SET NX 원자적 ✓. 이력은 무제한 insert 허용(의도 — "이력은 전량 보존") — rate limit 부재는 설계 수용(WARD 인증 필요).
- 설정 동시 PUT: upsert race → `uq_user_notif_channel` 충돌 시 409 — 드묾, Low 수용.
- 연결 상태 전이: @Version 낙관락 + 409 핸들러(기준선 #153) 유지 ✓.

## 3. PHASE B — API 계약

- 응답 포맷: 전부 `ApiResponse` ✓. 단 **래퍼 스타일 분기** — connection·notification·SOS는 `ResponseEntity<ApiResponse<T>>`, auth·user는 `ApiResponse<T>` 직접 반환(@ResponseStatus). 동작 동일, 스타일 비일관 → 🟢 L-S2-4 (세션 3 종합 시 컨벤션 결정 후보).
- 상태코드: SOS 201 ✓, 상태 충돌 409 ✓, 미소유 접근 `CONNECTION_NOT_AUTHORIZED` ✓.
- URL: `/api/{역할}/connection/...` 계층 일관, refusal/disconnection 명사형 일관 ✓.
- Swagger: `WardSosController`에 `@ApiResponses` 블록 부재(201/401/403 미문서화 — 타 컨트롤러는 충실) → 🟢 L-S2-5. 세션 1의 "429 미문서화" 계열과 함께 일괄 정리 권장.
- 에러 메시지 톤: 시니어 친화 일관 ✓. WS 와이어명 `connection-cancelled`(해제)은 의도된 호환 보존(06-06 스팟 결론 유지).

## 4. PHASE C — 구조·품질

- 이벤트: 알림 리스너 전부 AFTER_COMMIT + @Async(notificationExecutor) 일관 ✓. 탈퇴 정리 리스너만 동기(의도 — purge 순서, M-S1-1 전제) ✓.
- @Transactional 경계: 외부 I/O(FCM/SMS/WS) 전부 트랜잭션 밖 ✓ (예외가 H-S2-1의 cleanup — 반대로 "트랜잭션이 필요한데 없는" 케이스).
- JPA: 연결 응답 빌드 `findAllById` 벌크 조회로 N+1 없음 ✓. SOS 발송 루프는 보호자당 ~3쿼리(설정·수신자·토큰) — 가족 규모 N이라 수용.
- 🟢 L-S2-6: `NotificationDispatcher` 채널 실패 로그가 `e.getMessage()`만 기록(스택트레이스 없음) — H-S2-1 같은 구조 결함이 원인 불명 한 줄 로그로 묻힘. `log.error(..., e)` 권장. (SosNotificationListener도 동일 패턴)

## 5. PHASE D — 테스트 갭

테스트 기반 자체는 세션 1보다 좋음(Dispatcher·채널 2종·설정·SOS 4클래스·쿨다운·리스너 전부 존재).

| 갭 | 내용 |
|---|---|
| 🟡 `FcmService` 테스트 부재 | `cleanupInvalidTokens`(H-S2-1을 잡았을 지점)·`registerToken` 중복/소유·`hasToken` 미검증 |
| 🟢 `NotificationRecipientResolver` | 사용자 미존재 폴백(부분 수신자) 경로 미검증 |
| 🟢 dispatcher 폴백 매트릭스 | mandatory×(토큰 유/무)×(채널 실패) 조합 중 "토큰 있으나 발송 실패" 케이스 부재 — M-S2-1 문서화 겸 추가 권장 |

## 6. 이슈 요약

| ID | 심각도 | 도메인 | 내용 | 권장 |
|---|---|---|---|---|
| H-S2-1 | 🟠 High | notification | 만료 토큰 cleanup 무트랜잭션 → 정리 영구 실패 → SOS SMS 폴백 영구 무력화 | `deleteByToken` `@Transactional`(+@Modifying 벌크) — **우선 수정 권장** |
| M-S2-1 | 🟡 Medium | notification/sos | presence 기반 폴백 — 무효 토큰 보호자 첫 SOS 유실 | 결과 기반 폴백 or 긴급=FCM+SMS 동시(설계 결정) |
| M-S2-2 | 🟡 Medium | notification | 토큰 소유자 재할당 없음 — 공유 디바이스 알림 오수신 | 소유자 다르면 갱신(upsert) |
| L-S2-3 | 🟢 Low | notification | 토큰 삭제 소유 검증·rate limit 없음 | `deleteByTokenAndUserId` |
| L-S2-4 | 🟢 Low | 공통 | ResponseEntity 래퍼 스타일 도메인 간 비일관 | 세션 3 종합 시 컨벤션 결정 |
| L-S2-5 | 🟢 Low | sos | WardSosController Swagger @ApiResponses 부재 | 문서 보완(세션 1 429건과 일괄) |
| L-S2-6 | 🟢 Low | notification | 채널 실패 로그 스택트레이스 누락 | `log.error(..., e)` |
| (D) | 🟡/🟢 | notification | FcmService 등 테스트 갭 3건 | H-S2-1 수정 시 동반 |

**Critical: 없음.** 기존 이슈(중복 보고 제외): C-ARCH1·C-SOLID1 보류, B-C3a·B-C4a Low 백로그(05-21 기준선).

## 7. 도메인 간 일관성 (세션 누적)

- 세션 1 auth/user(직접 ApiResponse) ↔ 세션 2 도메인(ResponseEntity 래퍼) 스타일 분기 — 최종 리포트에서 단일 컨벤션 제안 예정.
- Swagger 문서 충실도 격차(세션 1: 429 누락 5곳 / 세션 2: SOS 블록 부재) — 전 도메인 공통 규칙으로 일괄 수정 권장.

## 8. 다음 세션(3) 인계

- **global 풀 점검 시 확인**: `WebSocketEventPublisher`·`StompSubscriptionAuthorizationInterceptor`(sos-triggered/connection-* 토픽이 범용 {userId} 검증으로 보호되는지 실증), `RateLimitService`, `GlobalExceptionHandler`의 23505/23503 분기, `notificationExecutor` 풀 설정(CallerRunsPolicy 전제 검증).
- H-S2-1 수정 여부에 따라 admin 점검 시 FCM 경로 재확인.
- announcement·admin은 점검 이력 전무 → 정밀.
