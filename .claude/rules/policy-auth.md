---
paths:
  - "**/domain/auth/**"
  - "**/domain/user/**"
  - "**/global/jwt/**"
  - "**/global/security/**"
  - "**/global/validation/**"
---

# 도메인 정책 - 계정·인증 (auth·user·토큰·가입·탈퇴)

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

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
  - **안전 이력의 보존 정책은 둘로 갈린다 (2026-09-11 회귀 재점검 R-3, 의도된 차이)**: `sos_event`는 SET NULL로 **익명 보존**(보호자 이력에 이름 없이 남음), `anomaly_event`·`anomaly_incident`(+응답·재촉 로그)는 V30·V44에서 **CASCADE 삭제**로 확정했다. 문의(`inquiry`)도 CASCADE다. "탈퇴자가 남긴 데이터를 붙들지 않는다"가 기본이고 SOS만 감사 목적 예외다 - 한쪽에 맞추자고 FK를 바꾸지 말 것. 전체 표는 `docs/(2026-09-10) audit-regression-pre-230.md` PHASE B.
- **본인 확인 유지(H-6)**: 일반=비밀번호, 카카오=confirmation "탈퇴" 일치 확인. access token 단독 탈취로 인한 임의 삭제를 차단(soft→hard 전환에도 동일 적용).
- **비가역**: 복구 불가 — 기존 soft delete의 복구·전수 감사 이점은 포기(접속로그 익명 기록만 잔존).
- 상세: PR #181.

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

## 회원 탈퇴 — 2단계 사이 실패 복원력 + INACTIVE 불변식 (2026-06-11)

- **의도**: 탈퇴는 `withdraw()`(INACTIVE 전환, 커밋) → AFTER_COMMIT 리스너 3종 → `purgeWithdrawnUser()`(영구 삭제)의 2단계라, 사이에 배포 재시작·인프라 순단이 끼면 INACTIVE 행이 잔존(M-S1-1). 이 상태는 재로그인(INACTIVE)·탈퇴 재시도(토큰 무효화로 401)·재가입(이메일/전화 잔존) 모두 불가한 자가 복구 불능 좀비.
- **변경(3중 방어)**: ① 탈퇴 리스너 3종(auth 토큰정리·connection 해제·notification FCM)을 try/catch로 best-effort화 — 한 리스너 실패가 나머지·purge를 막지 않음(행 정리는 purge FK CASCADE가 최종 담당, 유실 가능한 건 상대 알림·WITHDRAW 접속로그뿐). ② 컨트롤러 purge 실패 시 1회 재시도 + `[WITHDRAW-PURGE-FAILED]` ERROR. ③ `WithdrawnUserPurgeScheduler`(10분 주기)가 `INACTIVE && updated_at < now-10분` 행을 스윕 purge — 좀비는 최대 ~20분 내 자동 회수.
- **불변 규칙 (INACTIVE 불변식)**: `Status.INACTIVE`를 만드는 경로는 **탈퇴(`User.deactivate()`) 단 하나**여야 한다. 스윕이 "오래된 INACTIVE = 좀비"로 판정해 **영구 삭제**하므로, 관리자 계정 제한·휴면 등 다른 용도로 INACTIVE를 재사용하면 **해당 계정이 스윕에 삭제된다**. 그런 기능 도입 시 반드시 별도 상태값(예: RESTRICTED)을 추가할 것. (`user.activate()`는 현재 미사용 — 복구 기능 추가 시에도 동일 주의)
- **수용한 한계**: 스윕 경유 purge는 리스너를 거치지 않아 실패 경로에 한해 상대방 알림·WITHDRAW 감사로그가 유실될 수 있음(`[WITHDRAW-SWEEP]` WARN으로 흔적 보존). 리스너 내부 REQUIRES_NEW 커밋 실패는 try/catch 밖이라 전파되지만 이 경우도 스윕이 회수.
- 상세: `docs/(2026-06-11) audit-full-api-session1.md` M-S1-1.

## 2026-10-02 QA(BE) 반영 · 
### 인증 필터·토큰 (D1~D5)

- **인증 저장소(Redis) 장애 (D1)**: 일반 경로는 무효화·블랙리스트 조회가 실패하면 **503**(`SERVICE_UNAVAILABLE`, `[AUTH-STORE-UNAVAILABLE]`)이다. **`POST /api/ward/sos`만 fail-open**(검사 생략 후 통과, WARN `[AUTH-STORE-FAIL-OPEN]`) - 서명·만료·typ 검증은 그대로 한다. WebSocket 핸드셰이크는 fail-open이 없고 503으로 거부한다.
  - **왜**: Redis 장애 중 SOS가 막히면 안전망이 무너진다. 그 외 경로는 정지·로그아웃된 토큰을 통과시키면 안 된다.
  - **수용한 한계**: Redis가 죽은 동안 **이미 로그아웃·정지·비밀번호 변경된 토큰이 SOS 경로에서만 통과**할 수 있다("정지는 즉시 차단" 정책의 유일한 예외). SOS는 어차피 보호자에게 가는 필수 알림이라 위험이 작다고 보고 둔다. fail-open을 다른 경로로 넓히지 말 것.
- **무효화 시각은 초 단위로 비교 (D2)**: `iat(초) < 무효화 시각(초)`일 때만 거부한다. 같은 초에 발급된 토큰은 허용(최대 1초 수용 - 밀리초 비교는 변경 직후 재로그인한 새 토큰을 거부했다). 옛 ms 값(1e11 이상)은 /1000으로 호환한다. 비교 단위를 다시 ms로 되돌리지 말 것.
- **refresh 토큰 (D3)**: jti 고유값 / `typ` 검사(access 토큰으로 refresh를 시도해도 **정상 세션을 폐기하지 않는다**) / 다른 기기 로그인으로 밀려난 refresh는 재사용 감지가 아니라 단순 `INVALID_TOKEN`(사용자별 마지막 로그인 시각 마커 `LoginSupersedeMarker`) / 회전 재사용 감지(H-3)는 유지. 밀려난 토큰을 `TOKEN_REUSE_DETECTED`로 처리하면 정상 사용자의 세션이 통째로 끊긴다.
- **관리자 본인 탈퇴 403 (D4)**: `ADMIN_CANNOT_WITHDRAW` + `[WITHDRAW-ADMIN-BLOCKED]` WARN. 마지막 관리자가 스스로 지우면 운영 주체가 사라진다(`CANNOT_MODIFY_ADMIN`과 같은 취지).
- **클라이언트 IP (D5)**: `app.client-ip.trusted-proxies`(`CLIENT_IP_TRUSTED_PROXIES`)에 든 피어일 때만 `X-Forwarded-For`를 **오른쪽부터** 해석하고, 비신뢰 피어가 보낸 헤더는 무시한다(스푸핑으로 rate limit·접속로그 IP를 속이는 것 방지). **기본 신뢰 대역에 사설망(`192.168.0.0/16` 등)이 들어 있어, 피어(nginx가 넘긴 X-Real-IP)가 사설 주소면 서버 `.env.dev` 설정 없이도 동작한다**(2026-10-05 gosky·vkcs-linux 모두 `CLIENT_IP_TRUSTED_PROXIES` 미설정으로 QA 통과). FE 서버가 공인 IP로 접속하는 구조가 되면 그 IP를 넣되, 환경변수는 기본값을 대체하므로 기본 대역도 함께 적을 것. `getRemoteAddr()` 직접 사용 금지 규칙은 그대로다.
  - **XFF는 래퍼를 벗긴 원래 요청에서 읽는다 (2026-10-05, AUTH-G28 재발)**: `server.forward-headers-strategy: framework`의 `ForwardedHeaderFilter`가 감싼 요청은 `X-Forwarded-*`를 숨긴다. 피어 주소만 래퍼를 벗기고 XFF는 감싼 요청에서 읽던 탓에 신뢰 피어여도 XFF가 항상 비어 FE 경유 사용자가 한 IP(192.168.0.1)로 합산됐다(PR #301). 벗기는 로직은 `ClientIpResolver.unwrap()` 한 곳이며 **피어와 XFF가 같은 원래 요청을 읽어야 한다** - 둘을 다른 요청 객체에서 읽게 되돌리지 말 것. 신뢰 판정은 피어로 먼저 하므로 원본 헤더를 읽어도 위조 방어는 그대로다. 테스트는 필터를 거치지 않은 `MockHttpServletRequest`만으로는 이 결함을 못 잡는다 - 실제 `ForwardedHeaderFilter`를 거친 요청으로 검증한다(`ClientIpResolverTest`).
- **속도 제한 장애 정책**: `RateLimitService`는 Redis 장애 시 **fail-open**(WARN) - 로그인·가입이 통째로 막히는 것보다 낫다. 반면 **로그인 잠금·인증번호 시도·발송 상한 카운터는 fail-closed**(보안 한도라 장애 중에 풀어주지 않는다). 두 부류를 같은 정책으로 통일하지 말 것.
- **STOMP 클라이언트 SEND**: `/app/` prefix만 허용하고 `/topic/...`은 거부한다. SUBSCRIBE만 검사하면 인증된 사용자가 `SEND /topic/{상대 userId}/sos-triggered`로 **가짜 SOS·화재 알림을 상대 화면에 띄울 수 있었다**. 서버 발행(`SimpMessagingTemplate`)은 이 인터셉터를 거치지 않아 영향이 없다.

## 2026-10-02 QA(BE) 반영 · 
### 입력 정규화·계정 식별 (이메일·텍스트)

- **이메일은 소문자로 정규화**: 입력은 소문자로 저장·조회하고, **V55(비가역)**가 기존 값을 소문자로 바꾸고 `lower(email)` 유니크 인덱스(`uq_users_email_lower`)를 만든다. ⚠️ **배포 전에 대소문자만 다른 중복이 없는지 점검**한다(마이그레이션 파일 머리의 `GROUP BY lower(email) HAVING count(*) > 1` 쿼리). 중복이 있으면 마이그레이션이 실패한다. 기존 `V*.sql`은 수정하지 않았다.
- **이름·텍스트 정규화**: 공용 유틸은 `global/validation`(`TextSanitizer`·`@VisibleText`·`@NoControlChars`)이다. 사용자가 입력하는 이름·표시 텍스트는 이것을 쓰고(보이지 않는 문자·제어문자·한글 채움 문자만 있는 값 차단), 도메인별로 따로 정규식을 만들지 말 것. `global`이 `domain`을 import하지 않는 규칙을 지킨다.
- **오류 응답에 `code` 필드**: FE가 문구가 아니라 `code`로 분기하도록 오류 응답에 코드를 싣는다. 문구는 바뀔 수 있으니 FE에 문구 파싱을 요구하지 말 것.

## 2026-10-02 QA(BE) 반영 · 
### 로그인 잠금·인증번호 시도 (기존 "검증 후 마지막 소비" 규칙 유지)

- **로그인 잠금**: 비밀번호 **비교 전에 시도를 예약**해 동시 요청에서도 5회를 넘지 못하게 한다. 미가입 이메일도 같은 방식으로 세어(enumeration과 별개로 잠금 동작을 동일하게) 가입 여부를 시간·응답으로 구분하지 못하게 한다. **비밀번호 변경·탈퇴 본인확인은 userId별 별도 잠금**이고, 비밀번호 재설정·변경에 성공하면 잠금을 푼다.
- **로그인 잠금 시간 10분 (2026-10-30 사용자 결정, 30분 → 10분)**: 시니어에게 30분은 길다. 값은 `auth.login.lock-ttl-minutes`(`AUTH_LOGIN_LOCK_TTL_MINUTES`, 기본 10) 하나이고 **실패 집계 창(failKey TTL)도 같은 값**이라 "30분 안 5회"가 "10분 안 5회"가 됐다(의도된 변화로 수용). 두 서버 `.env.dev`에 이 키는 없다(코드 기본값 적용). **비밀번호 변경·탈퇴 확인 잠금은 30분 그대로**이고(`UserService.PASSWORD_CHECK_LOCK_MINUTES`), 문구가 달라져 오류 코드를 `PASSWORD_CHECK_LOCKED`(429, "30분 후")로 나눴다 - `LOGIN_LOCKED`(429, "10분 후")는 로그인 전용. `LOGIN_LOCKED` 문구의 분 수는 하드코딩이라 **설정값을 바꾸면 문구도 함께 바꿀 것**(`AuthLoginPropertiesTest`가 기본값과 문구 일치를 고정).
- **인증번호 시도**: 시도 횟수는 비교 전에 예약하고 **정답이면 환불**한다. 확인 API는 IP 속도 제한이 있다. nonce·코드 소비는 여전히 **모든 검증 뒤 마지막**이며 `consume`은 원자적이다(2026-05-31 규칙 그대로). 소비를 앞당기거나 예약을 비교 뒤로 미루지 말 것.

## 2026-10-02 QA(BE) 반영 · 
### 카카오·파일서버

- **가입 `pendingToken`**: 카카오 로그인 응답으로 일회용 토큰을 내리고(서버에는 SHA-256 해시만 저장, TTL 30분), 가입 완료는 **그 토큰을 가진 본인만** 할 수 있다. 다시 로그인하면 새 토큰으로 교체된다. kakaoId만 알면 남의 가입 대기를 가로챌 수 있었던 문제를 막는다.
- **프로필 이미지 URL**: 카카오 CDN(https, `kakaocdn.net`)만 받는다. 임의 URL은 파일서버 삭제·표시 경로에서 SSRF·추적 URL이 된다.
- **일반 가입에서 `kakao_숫자@kakao.com` 거절**: 카카오 이메일 없는 계정에 서버가 부여하는 가상 이메일과 충돌해 남의 카카오 계정을 선점할 수 있다.
- **파일서버 삭제는 자기 baseUrl만**: 외부 URL(카카오 CDN 등)에는 삭제 요청을 보내지 않는다.


## refresh 토큰 HttpOnly 쿠키 (2026-10-06, XCUT-G31·AUTH-G26)

- **경위**: 로그인·갱신 응답 본문의 refresh 토큰을 FE가 비 HttpOnly 쿠키로 저장해 XSS 한 번이면 7일짜리 토큰이 읽혔다. 설계안 A안대로 BE가 `Set-Cookie`(HttpOnly·Secure·SameSite=Lax·`Path=/api/auth`·Max-Age 7일·Domain 없음, 이름 `careai_rt`)로 내린다. 결정 9개와 이유는 `docs/(2026-10-02) design-refresh-token-httponly-cookie.md` §5.
- **불변 규칙 ①(쿠키 범위)**: Path를 `/api/auth` 밖으로 넓히거나 `Domain`을 지정하지 말 것(`gosky.kr`는 우리 소유가 아니라 다른 서브도메인에 노출된다). 쿠키는 `/refresh`·`/logout` 경로에만 실린다. **access 토큰을 쿠키로 받는 인증을 만들지 말 것**(인증 필터는 계속 Authorization 헤더만 읽는다).
- **불변 규칙 ②(CSRF)**: 쿠키만으로 갱신할 때는 `Origin`이 있으면 `APP_CORS_ORIGINS`에 있어야 한다(없으면 403, 회전 전에). SameSite를 None으로 바꾸지 말 것. `Origin`이 없는 요청(FE 중계 서버)은 통과하는 것이 의도다.
- **불변 규칙 ③(만료)**: 로그아웃·비밀번호 변경·탈퇴 성공 때와, **갱신이 401·403·404로 거절될 때** 같은 속성으로 Max-Age=0을 내린다(HttpOnly라 화면이 지울 수 없다). 429·5xx에는 지우지 않는다. 관리자 정지·역할 변경은 브라우저에 헤더를 줄 수 없어 다음 갱신 거절 때 지워진다.
- **불변 규칙 ④(보안 규칙은 전달 방식과 무관)**: 회전·재사용 감지(H-3)·단일 기기 정책·AUTH-G03 단순 401은 `AuthService.refresh(String)` 한 곳에 있다. 쿠키 경로에서 따로 우회하거나 grace(유예)를 끼우지 말 것(XCUT-G06 유예는 여전히 보류).
- **전환 기간**: `auth.refresh-cookie.body-compat`(기본 true)이면 본문 `refreshToken`을 내리고 받는다(본문 우선). FE 전환 배포 확인 후 7일 뒤 false로 바꾸면 응답 본문 값은 null(키 유지), 요청 본문은 무시된다. 그 뒤 본문 경로 코드를 삭제한다. 롤백은 `AUTH_REFRESH_COOKIE_ENABLED=false`.
- **FE 프록시 주의**: FE `api/[...path]/route.ts`는 요청의 `cookie`·`origin` 헤더를 지운다 - `/auth/refresh`에 한해 `careai_rt`를 BE로 넘겨야 쿠키가 동작한다.

## 2026-10-31 전체 점검 반영 (H-2)

- **비밀번호 변경·재설정 뒤 정리는 단계별 best-effort**: `UserAccountEventListener.handlePasswordChanged`는 refresh 삭제와 access 무효화(Redis)를 각각 감싼다. 예외 하나가 메서드 밖으로 새면 REQUIRES_NEW가 롤백돼 **이미 지운 refresh 토큰이 되살아난다**(탈취를 의심해 재설정했는데 옛 세션이 7일 유지). 정지·역할 변경·탈퇴 핸들러와 같은 형태이며, 감싼 블록을 하나로 합치거나 풀지 말 것.
