# 도메인 보안 정책 메모

> 코드만으로는 드러나지 않는 보안 정책 결정의 의도·이력을 기록한다.
> (이 파일은 `paths:` 스코프 없이 매 세션 로드된다. CLAUDE.md §8에서 요약을 참조한다.)

## 비밀번호 재설정 — 가입 여부 명시 응답 (2026-05-23 갱신)

- **의도**: 시니어/4050 타겟 UX 우선 — "메일이 안 와요" 이탈 감소.
- **User Enumeration 정책 변경**: 기존 `find-password` send/resend는 미가입에도 **항상 200**(enumeration 차단)이었으나, **미가입 404 / 카카오 가입 계정 400**으로 명시 안내하도록 변경.
  - 이메일: 미가입 404("해당 이메일로 가입된 계정이 없습니다"), 카카오 400.
  - SMS: 이름+전화번호 미일치 404("사용자를 찾을 수 없습니다"), 카카오 400.
- **노출 보완 (Rate Limit 등)**:
  - `pw-reset-email`/`pw-reset-sms` send·resend: IP **이중 윈도우 1분 10회 / 1시간 30회**(초과 429).
  - per-email 발송 상한 `password:email:sendcount` **1시간 10회**(SMS `sms:sendcount` A-M3와 대칭).
  - 미가입 시 `[PW-RESET]` **WARN 로깅**(마스킹 식별자+IP), 미가입자 SMS/메일 **발송 전 차단**.
  - `/password/reset`(3단계)는 코드 선검증(A-M1) 유지 — **변경 없음**.
- **거부안**: 응답 시간 정규화(존재 여부를 의도적으로 노출 → 모순), 의심 IP 블랙리스트(공용 NAT 시니어 오차단), CAPTCHA(시니어 부담).
- 상세: `docs/(2026-05-23) policy-change-password-reset.md`, `docs/(2026-05-23) audit-report-auth-password-reset.md`.

## 카카오 OAuth — Client Secret 적용 (2026-05-25)

- **의도**: 인가코드 탈취 시 토큰 발급을 차단(REST API Key 단독 대비 보안 강화).
- **변경**: 백엔드 토큰 교환(`POST kauth.kakao.com/oauth/token`) 요청에 `client_secret` 추가.
- **환경변수**: `KAKAO_CLIENT_SECRET` — `.env.dev`로만 주입(코드/Git **평문 비노출**). `application.yaml`은 `${KAKAO_CLIENT_SECRET:}`로 매핑.
- **시작 시 검증**: 존재 여부 → `RequiredPropertiesValidator`(11개 키), 길이(≥32)·placeholder/약한 값 → `SecurityConfigValidator`. 미설정·약한 값이면 시작 중단(fail-fast).
- **운영 적용 전제**: 카카오 콘솔 [보안 > Client Secret] 코드 발급 + **"사용 함" 활성화** 필요. 활성화 없이 secret만 보내면 무시되고, 활성화 후 secret 누락 시 토큰 발급 실패.
- 상세: `docs/(2026-05-25) feature-kakao-client-secret.md`.

## 회원 탈퇴 — 영구 삭제(hard delete) 전환 (2026-05-26)

- **의도**: 기존 비활성화(soft delete)는 user 행이 남아 탈퇴 후 같은 이메일/전화번호로 **재가입이 막히던** 문제가 있었음(`existsByPhone`/`existsByEmail`가 INACTIVE 행도 포함). 탈퇴 시 계정·관련 데이터를 **영구 삭제**하여 재가입을 허용.
- **변경**: `withdraw()`(본인확인+`deactivate()`+`UserWithdrawnEvent`) 커밋 **후**, 컨트롤러가 `purgeWithdrawnUser()`로 user 행을 **hard delete** (이전엔 `deactivate()`로 INACTIVE 전환만).
  - **단계 분리 이유**: 정리 로직(연결 해제+상대 알림·FCM/refresh 토큰 정리·WITHDRAW 접속로그)이 `UserWithdrawnEvent`의 **AFTER_COMMIT 리스너**로 동작하며 "user 행이 살아있음"을 전제로 함 → 정리 완료 후 별도 트랜잭션에서 삭제.
- **연관 데이터**: users 참조 FK가 이미 `CASCADE`/`SET NULL`이라 **DB 마이그레이션 불필요**.
  - CASCADE 삭제: `connections`·`fcm_tokens`·`refresh_tokens`.
  - `SET NULL`: `access_logs`·`announcements`·`announcement_drafts` — **접속로그는 보안 감사용으로 익명 보존**(완전 삭제 아님). 업로드 프로필 이미지 파일은 커밋 후 제거(카카오 CDN 등 외부 URL이면 파일서버가 무시).
- **본인 확인 유지(H-6)**: 일반=비밀번호, 카카오=confirmation "탈퇴" 일치 확인. access token 단독 탈취로 인한 임의 삭제를 차단(soft→hard 전환에도 동일 적용).
- **비가역**: 복구 불가 — 기존 soft delete의 복구·전수 감사 이점은 포기(접속로그 익명 기록만 잔존).
- 상세: PR #181.

## 연결 거절 — 보호자 실시간 알림 추가 (2026-05-28)

- **의도**: 피보호자가 연결 요청을 거절해도 보호자에게 이벤트가 안 가 보호자 웹이 새로고침 전까지 "요청중"에 멈춰 있던 문제. 수락(`ConnectionAcceptedEvent`)·해제(`ConnectionDisconnectedEvent`)와 **비대칭**이던 거절을 동일 패턴으로 정렬.
- **변경**: `refuseConnectionAsWard()`가 `refuse()` 커밋 후 `ConnectionRefusedEvent(connectionId, guardianId)` 발행 → `ConnectionNotificationListener.handleRefused()`(AFTER_COMMIT, `@Async`)가 보호자에게 WebSocket(`connection-refused`) + FCM(`"연결 요청이 거절되었습니다."`) 발송.
- **FCM 문구**: 시니어/4050 타겟 직관성 우선 — "거절되었습니다"로 명확히. 모호한 "종료/해제" 표현은 회피.
- **알림 비대칭 정책(의도된 것)**: 같은 PENDING 종료라도 알림 대상이 다름 — ① **피보호자 거절 → 보호자 알림O**(본인의 명시적 액션), ② **보호자 요청 취소(`cancel`) → 무알림**, ③ **회원 탈퇴 시 PENDING → CANCELLED 무알림**(`tearDownConnectionsOnWithdrawal`). 향후 "일관성" 명목으로 ②③에 알림을 추가하지 말 것 — 거절만 상대에게 통지가 필요한 명시적 거부 행위.
- **인가**: `connection-refused` 토픽은 `StompSubscriptionAuthorizationInterceptor`의 범용 `{userId}==세션` 검증으로 자동 보호(이벤트명 화이트리스트 없음) — 별도 등록 불필요.
- **DB 영향 없음**: 상태 전이(`refuse()`)는 기존과 동일, 이벤트 발행만 추가. 마이그레이션 불필요.
- 상세: `docs/(2026-05-28) feature-connection-refused-notification.md`.

## 카카오 가입 access_logs FK 위반 수정 (2026-05-30)

- **의도**: 카카오 가입의 `KAKAO_LOGIN` 접속로그를 가입 트랜잭션 안에서 REQUIRES_NEW로 남기면, 미커밋 user 행을 별도 트랜잭션이 못 봐 `fk_access_logs_user` 위반(SQLState 23503)이 발생. 이를 `DataIntegrityViolationException` 핸들러가 "중복"으로 오표시했음.
- **변경**: `KakaoRegisteredEvent` + `@TransactionalEventListener(AFTER_COMMIT)` 리스너로 접속로그를 **커밋 후** 기록(user 커밋 후라 FK 보장). 공유 `AccessLogService`는 미변경. 예외 핸들러는 SQLState 구분 — 23505(unique)만 409 "중복", FK 등은 500 + 원인 로깅.
- **불변 규칙**: 가입 트랜잭션 내부에서 `accessLogService.log()`(REQUIRES_NEW)를 **직접 호출하지 말 것** — 미커밋 user를 참조해 FK 위반. 접속로그는 AFTER_COMMIT 이벤트로.
- 상세: `docs/(2026-05-30) bugfix-kakao-signup-access-log-fk.md`, PR #185.

## 인증코드/nonce 소비 순서 — "검증 후 마지막 소비" (2026-05-31)

- **의도**: 인증 매개체(SMS 6자리 코드·가입 nonce)의 **검증과 소비(Redis 삭제)를 분리**한다. Redis 삭제는 `@Transactional` 롤백 대상이 아니므로, 검증·비즈니스 처리보다 **먼저 소비하면 이후 단계가 실패해도 인증이 비가역적으로 소모**돼 같은 코드/nonce로 재시도가 막힌다.
- **불변 규칙**: 인증코드/nonce 소비는 **모든 비즈니스 검증·처리가 성공한 "마지막"에** 수행한다.
  - 비번재설정 `confirmReset`: 맨 앞은 비소비형 `verificationCodeValidator.verifyWithoutConsume()`(enumeration 차단 A-M1 유지), 모든 검증·변경 성공 후 마지막에 `verificationCodeValidator.consume()`. 소비형 `verify()`(검증+삭제 결합)를 다운스트림 검증 앞에서 호출하지 말 것.
  - 가입 `AuthService.register`·`KakaoAuthService`: nonce는 비즈니스 검증 후 `smsService.consumeVerification()`으로 마지막 소비(이미 적용됨).
- **버그 이력**: `confirmReset`이 소비형 `verify()`를 맨 앞에서 호출 → 새 비밀번호=현재 비밀번호(`SAME_AS_CURRENT_PASSWORD`) 등 1차 실패 시 코드가 소모돼, 같은 코드 2차 재시도가 `EXPIRED_SMS_CODE`로 막힘. 카카오 가입 nonce 버그와 동일 뿌리(검증/소비 미분리 + Redis 비롤백).
- **공유 컴포넌트**: `VerificationCodeValidator`는 `verify`(소비형)·`verifyWithoutConsume`(비소비형)·`consume`(소비 전용)를 제공. 흐름별로 적절히 조합한다.
- **L-1 경계 (2026-06-06 점검, 의도적 미수정)**: 가입 nonce 소비는 "비즈니스 검증 후"지만 `userRepository.save()` *앞*이다. 이를 `save()` 뒤로 옮기는 단순 변경은 **무효** — User는 assigned-ID라 Hibernate가 INSERT를 커밋(flush)까지 지연시켜, 유니크 위반(`DataIntegrityViolationException`)이 consume *뒤*인 커밋 시점에 터진다. 게다가 가입 `save` 실패는 항상 ① 영구 중복(이메일/전화 — 같은 값 재시도 자체 불가) 또는 ② 서버측 결함(FK·NOT NULL — 재시도 무의미)뿐이라, nonce 보존 실익이 사실상 0(#184/#188이 막은 "1차 실패 후 정상 재입력" 케이스는 이 경로에 없음). → **"일관성" 명목으로 `saveAndFlush`나 nonce 검증/소비 분리를 추가하지 말 것**(복잡도·회귀만 증가).
- 상세: `docs/(2026-05-31) bug-investigation-password-reset-verification.md`, `docs/(2026-06-06) audit-spot-check-kakao-password-bugfix.md`(L-1 분석).
