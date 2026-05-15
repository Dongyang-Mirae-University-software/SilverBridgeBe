# auth / user 도메인 점검 리포트

| 항목 | 값 |
|------|-----|
| 점검 일자 | 2026-05-15 |
| 점검자 | Claude Code (스킬: security-audit, concurrency-review, architecture-review, spring-boot-patterns, java-code-review, clean-code, api-contract-review, jpa-patterns, performance-smell-detection, logging-patterns, test-quality) |
| 범위 | domain/auth, domain/user, global/jwt, global/security, global/util(MaskingUtil/RedisKeys/VerificationCodeValidator/UserIdGenerator), global/config(SecurityConfigValidator/RequiredPropertiesValidator/SecurityConfig), global/exception(ErrorCode/CustomException/GlobalExceptionHandler), global/aop/ApiLoggingAspect, db/migration(users/refresh_tokens/access_logs), application.yaml, .env.dev, build.gradle, .gitignore |
| 점검 파일 수 | 49 (Java 38 + SQL 11 + 설정 4 — 일부 중복 포함) |
| 적용 정책 | 분석·제안만, 자동 git 작업 금지, 응답 포맷 변경 시 별도 표시, Critical 발견 시 일시정지 후 협의 (CLAUDE.md §2) |

---

## 요약

| 등급 | 건수 | 의미 |
|------|------|------|
| 🔴 Critical | 1 | 즉시 수정 필요 — 보안 취약점·데이터 손실 위험 |
| 🟠 High | 7 | 다음 배포 전 수정 — 보안·안정성 |
| 🟡 Medium | 13 | 다음 스프린트 — 유지보수성·정확성 |
| 🟢 Low | 7 | 백로그 — 네이밍·컨벤션 |

Critical 1건 = 비밀번호 변경/재설정 후 기존 Access Token이 30분간 유효하게 남는 점.

---

## Phase A. 보안 (security-audit + concurrency-review)

### 🔴 A-Critical-1. 비밀번호 변경/재설정 후 Access Token 무효화 누락

위치
- `domain/auth/service/PasswordResetService.java:158` (`confirmReset`)
- `domain/user/service/UserService.java:78` (`changePassword`)
- `domain/auth/listener/UserAccountEventListener.java:38` (`handlePasswordChanged`)

현재 동작
- 비밀번호 변경 시 `refreshTokenRepository.deleteByUserId(userId)`로 refresh token만 정리.
- 클라이언트가 보유한 access token(JWT, 30분 만료)은 그대로 유효. 토큰 자체가 stateless라 서버에서 폐기 불가.

위협 시나리오
- 사용자가 “비밀번호 노출 의심”으로 비밀번호 변경 → 공격자가 탈취한 access token으로 최대 30분간 모든 인증 API 호출 가능. `/api/user/me/select`, `/api/user/me/delete`, 보호된 게임/공지 등 전부.
- 이는 “비밀번호 변경” 자체의 보안 목적과 정면 배치. 사용자 요구 항목 A1(“로그아웃 시 accessToken 블랙리스트 등록 확인”)에 비춰도 변경 후 블랙리스트 등록 누락.

권장 수정 방향 (3개 후보 — 트레이드오프 함께)

후보 1. 비밀번호 변경 시점을 User에 저장 + JWT iat와 비교  (✅ 추천)
- DB 변경: `users.password_changed_at TIMESTAMPTZ` 컬럼 추가 (Flyway V16).
- 비밀번호 변경/재설정 시 `user.passwordChangedAt = now()` 저장.
- `JwtAuthenticationFilter`가 토큰의 `iat`(issued at) 클레임을 읽어 DB의 `passwordChangedAt` 이전이면 401. 단, 매 요청 DB 조회 발생 → User를 Redis에 캐시(`user:password-changed:{userId}` 키, 30분 TTL) 또는 access token TTL 동안 Redis에 invalidation timestamp 저장.
- 응답 포맷 변경: 없음. 동작 변경: 변경 직후 기존 토큰이 401로 차단(Swagger 문서에 이미 “재로그인 필요” 명시).

후보 2. Redis sliding invalidation
- 키: `password:invalidate:{userId}` = `now()`, TTL = access token max expiration (30분).
- `JwtAuthenticationFilter`에서 토큰 iat < 키 값이면 401.
- DB 변경 없음, Redis 1회 GET 추가. 가장 가벼움.

후보 3. JWT에 tokenVersion 클레임 + User.tokenVersion 컬럼
- 비밀번호 변경 시 `user.tokenVersion++`.
- Filter에서 토큰 클레임과 DB 값 비교. 가장 강력하지만 DB 조회 비용 ↑.

수정 영향 파일 (후보 1·2 공통)
- `JwtAuthenticationFilter` — iat 검증 추가
- `JwtTokenProvider` — iat 추출 헬퍼 추가
- `PasswordResetService.confirmReset` — invalidation 저장
- `UserService.changePassword` — invalidation 저장
- `UserAccountEventListener.handlePasswordChanged` — invalidation 저장 (이벤트 발행 후 처리)

프론트 호환성: **응답 포맷 변경 없음.** 동작은 “비밀번호 변경 직후 기존 토큰으로 호출하면 401” — Swagger 문서가 이미 모든 변경 API에 “재로그인 필요” 안내되어 있어 정상 흐름에선 영향 없음.

---

### 🟠 A-High-1. 로그인 응답이 계정 enumeration 허용

위치 `domain/auth/service/AuthService.java:113-118`

```java
User user = userRepository.findByEmail(email)
        .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));   // 404

if (user.getStatus() == Status.INACTIVE) {
    throw new CustomException(ErrorCode.INACTIVE_USER);                      // 403
}

if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
    ...
    throw new CustomException(ErrorCode.INVALID_PASSWORD);                   // 401
}
```

- 가입 안 됨 → 404, 존재하나 비활성 → 403, 존재 + 비밀번호 틀림 → 401.
- 공격자가 임의 이메일을 보내며 응답 코드로 가입 여부를 알아낼 수 있음 (OWASP A07: Identification and Authentication Failures).

권장
- 가입 안 됨 + 비밀번호 틀림 모두 `INVALID_CREDENTIALS`(401) 단일 응답으로 통합.
- INACTIVE는 의도적으로 노출해도 무방하나, 정책에 따라 동일 메시지로 통일 가능.

프론트 마이그레이션 (이 항목만 응답 변경 영향 있음)
- 현재 프론트가 404 응답 분기에서 “가입하지 않은 이메일” 안내를 띄우는 경우 → 401 + 동일 메시지로 통일. 사용자 UX는 “이메일 또는 비밀번호가 올바르지 않습니다”로 일원화.
- 적용 순서 권장: 백엔드 응답 통합 PR 전에 프론트 분기 정리 PR을 먼저 머지하면 무중단 전환 가능. 또는 응답을 동일 메시지로 두되 HTTP 코드는 점진 통합(409와 같은 임시 코드는 피함).

```java
// AuthService.java — 수정 제안
private static final ErrorCode INVALID_CREDENTIALS = ErrorCode.INVALID_PASSWORD;  // 또는 새 ErrorCode 추가

User user = userRepository.findByEmail(email)
        .orElseThrow(() -> new CustomException(INVALID_CREDENTIALS));         // 401 통합

if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
    // ... 실패 카운트 처리는 user 존재 시에만 (DoS 방지는 H-2에서 별도)
    throw new CustomException(INVALID_CREDENTIALS);                           // 401
}

if (user.getStatus() == Status.INACTIVE) {
    throw new CustomException(ErrorCode.INACTIVE_USER);                       // 비밀번호 검증 후 INACTIVE 노출
}
```

INACTIVE는 비밀번호 검증을 먼저 통과한 사용자에게만 노출되도록 순서를 바꾸면, enumeration도 막고 비활성 사용자에게는 정확한 안내가 가능.

---

### 🟠 A-High-2. 이메일 기반 로그인 잠금 — 정상 사용자 DoS 가능

위치 `AuthService.java:104-129`

- 실패 카운트/잠금 키가 `LOGIN_FAIL + email`, `LOGIN_LOCK + email`. 공격자가 피해자 이메일을 알면 5회 실패만 시도해 30분 잠금 가능.

권장
- 잠금 키를 (이메일 + IP) 조합 또는 user.id 기반으로 변경.
- 정상 정책: 이메일별 잠금은 유지하되 IP별 fail count도 함께 운영. IP가 다른 다수 실패는 분산 공격이므로 별도 captcha/Bot 차단.
- 또는 잠금 후에도 정상 사용자에게는 비밀번호 정상 입력 시 잠금 해제 옵션 제공.

응답 포맷 변경: 없음.

---

### 🟠 A-High-3. Refresh Token Rotation 재사용 탐지 부재

위치 `AuthService.java:168-198`

- 현재: `findByToken → delete → save`. 한 번 사용된 token으로 재요청 시 `findByToken`이 비어 INVALID_TOKEN.
- 부재: 탈취된 token이 사용된 후 정상 사용자가 다음 rotation에 도달했을 때 “이전 token이 이미 사용됨” 신호를 감지하지 못함 → family invalidation 안 됨.

권장
- `refresh_tokens`에 `family_id`(UUID) 컬럼 추가 또는 별도 `revoked_at` 컬럼. 재사용 감지 시 동일 user의 모든 token revoke.
- 단순화 버전: `findByToken` 실패 시 `existsByUserId(suspectedUserId)`가 true면 즉시 user의 모든 token 삭제 + access log에 `TOKEN_REUSE_DETECTED` 등 새 액션 기록.

프론트: 정상 사용자에게는 영향 없음. 탈취 의심 시 강제 재로그인.

---

### 🟠 A-High-4. INACTIVE 계정 차단 시 refresh token 즉시 삭제 누락

위치 비교
- `AuthService.refresh:182-185` — `refreshTokenRepository.delete(savedToken)` ✅
- `AuthService.login:116-118` — token 삭제 없음 ❌
- `KakaoAuthService.kakaoLogin:61-63` — token 삭제 없음 ❌

권장
- login/kakaoLogin의 INACTIVE 분기에 `refreshTokenRepository.deleteByUserId(user.getId())` 추가. 일관성 확보.

---

### 🟠 A-High-5. SMS_VERIFIED 키가 phone 단일 식별자

위치 `SmsService.java:50-60` + `AuthService.register:74` + `KakaoAuthService.kakaoRegister:108` + `UserService.updateProfile:55`

- `SMS_VERIFIED:{phone}` 키는 phone만으로 식별. 누가 인증했는지 정보 없음. 10분 윈도우 안에 동일 phone을 다른 컨텍스트(다른 이메일, 다른 카카오ID, 다른 사용자의 프로필 변경)에서 재사용 가능.

위협 시나리오
- 정상 사용자 A가 phone X로 SMS 인증 완료 후 가입 폼 작성 중 → 공격자 B가 phone X를 알고 (전화번호부 유출 등) 같은 시간 윈도우에 자기 카카오ID + phone X로 회원가입 시도 → 통과 가능. (단, existsByPhone 체크가 있어 가입 자체는 1회만 성공. A가 먼저 완료하면 B는 실패. 윈도우가 좁아 실제 위협 강도는 제한적)

권장
- 인증 완료 키에 “인증 세션 식별자”를 함께 저장. 예: 인증 시점에 nonce 발급 → 회원가입 요청에 nonce 포함 → 서버가 `SMS_VERIFIED:{phone}` 키의 값(nonce)과 요청 nonce 일치 확인.
- 또는 회원가입/카카오가입 요청의 phone과 인증 완료된 phone이 동일한 “세션”에서 발생했는지 검증할 수 있도록 sessionId 결합.

프론트 영향: 인증 응답에서 nonce 반환 → 회원가입 요청에 포함. 필드 추가는 호환성 OK(클라이언트가 무시 가능), 검증 활성화 시점 조율 필요.

---

### 🟠 A-High-6. 카카오 사용자 탈퇴 시 본인 확인 약함

위치 `UserService.withdraw:131-144` + `WithdrawRequest`

- 카카오 사용자는 `password == null` 허용. access token만 있으면 탈퇴 처리됨. 토큰 탈취 시 30분 내 계정 비활성화(영구적) 가능.

권장
- 카카오 사용자에게도 본인 확인 절차 추가:
  - 카카오 재인증(현재 access token으로 카카오 user info 재요청해 신뢰 확인) 또는
  - 사용자에게 “탈퇴” 단어를 직접 입력하게 하여 명시적 동의 확인
- 본 점검 권장은 옵션 B(명시적 confirmation 문자열) — 외부 API 호출 없이 구현 가능.

응답 포맷: WithdrawRequest에 `confirmation` 필드 추가(클라이언트 추가 입력 필요) → 프론트 영향 있음. 단계적 적용 권장.

---

### 🟠 A-High-7. AccessLogRepository — JPQL 파라미터 타입 불일치 (dead code 포함)

위치 `domain/auth/repository/AccessLogRepository.java:13-14`

```java
@Query("SELECT COUNT(l) FROM AccessLog l WHERE l.action = :action AND l.createdAt >= :from")
long countByActionAndCreatedAtAfter(@Param("action") String action, @Param("from") OffsetDateTime from);
```

- `l.action`은 `@Enumerated(EnumType.STRING) AccessAction`. JPQL에서 String 파라미터와 비교하면 Hibernate가 enum으로 자동 변환하지 않아 비교 실패 또는 0 반환 가능.
- 호출자가 현재 없음(grep 확인) → dead code. 향후 사용 시 즉시 버그.

권장 — 호출자가 없으니 두 가지 선택지:
- 옵션 A. 메서드 삭제 (가장 간단)
- 옵션 B. `String action` → `AccessAction action` 으로 시그니처 수정. 호출 예정이 있다면 옵션 B.

---

## Phase A — 🟡 Medium / 🟢 Low

| 등급 | ID | 위치 | 내용 |
|------|----|------|------|
| 🟡 | M-1 | KakaoLoginRequest, KakaoOAuthClient | 카카오 OAuth state 파라미터 검증 없음. 카카오 인가코드 1회성/짧은 만료라 위협 제한적이지만 표준 권장 |
| 🟡 | M-2 | LoginResponse Swagger | "유효 시간: 14일" — 실제는 7일(application.yaml `refresh-token-expiration=604800000`). AuthController/TokenRefreshResponse는 7일 표기. 문서 수정 |
| 🟡 | M-3 | PasswordResetService.confirmReset | `accessLogService.log(userId, AccessAction.PASSWORD_RESET)` — IP/UA null. PasswordResetController가 HttpServletRequest 미수신. 감사로그 품질 |
| 🟡 | M-4 | RateLimitService.check | `INCR` 후 첫 호출에만 `EXPIRE` — INCR과 EXPIRE 사이 서버 죽으면 TTL 미설정 키 영구화 위험. `INCR EX` 단일 Lua script 또는 `IF NOT EXISTS SET` 후 INCR로 변경 권장 |
| 🟡 | M-5 | PasswordResetService.requestReset | `@Transactional(readOnly=true)` 안에서 SMTP send — 외부 I/O가 트랜잭션 점유. readOnly 트랜잭션 밖으로 send 분리 |
| 🟡 | M-6 | JwtAuthenticationFilter.isLoggedOut | 블랙리스트 키가 `logout:{full-token}`. token 자체가 키라 Redis 메모리/저장 효율 낮음. SHA-256 hash 또는 JTI로 단축 |
| 🟡 | M-7 | AuthController.logout | `bearerToken.substring(7)` — `Bearer ` 접두사·길이 사전 검증 없음. 잘못된 헤더 형식 시 500. `StringUtils.hasText + startsWith` 검증 후 추출 |
| 🟡 | M-8 | confirmReset, refresh, kakaoLogin/Register | `rateLimitService.check` 미적용. UUID/JWT라 brute force는 비현실적이나 분당 호출 제한 권장 |
| 🟡 | M-9 | SmsKeyConfig.PASSWORD_RESET_EMAIL | record 이름이 SmsKeyConfig인데 이메일도 사용 → `VerificationKeyConfig` 정도로 일반화 (사용처 4곳 동시 변경 필요) |
| 🟡 | M-10 | PasswordResetService.requestReset vs SmsVerificationService.sendCode | 거의 동일한 cooldown·attempt·발송 로직이 이메일 경로에 별도 구현됨. EmailVerificationService로 추출하면 중복 제거 |
| 🟡 | M-11 | BCryptPasswordEncoder() | strength 미지정(기본 10). OWASP 2025 권장 12. `new BCryptPasswordEncoder(12)`. 단, 기존 해시도 함께 검증 가능(BCrypt는 strength 자동 인식) |
| 🟡 | M-12 | AuthService 상수 LOGIN_MAX_ATTEMPTS·LOGIN_LOCK_TTL | private static. 정책성 상수는 `AuthPolicy` 클래스로 묶거나 `@ConfigurationProperties` 외부화 |
| 🟡 | M-13 | UserController withdraw 카카오 password null 정책 | WithdrawRequest 주석/Swagger에는 명시되어 있으나 검증은 UserService 분기로만. password 길이 검증 등 입력 검증 명시화 |
| 🟢 | L-1 | CustomUserDetails.isEnabled | `user.getStatus().name().equals("ACTIVE")` — enum 직접 비교 `user.getStatus() == Status.ACTIVE` 가 더 명확 |
| 🟢 | L-2 | VerificationCodeValidator | `INCR` 후 매번 `EXPIRE` — Lua script로 합치면 미세하지만 효율 ↑. 현재 코드도 안전성 측면은 OK |
| 🟢 | L-3 | `/api/auth/**` permitAll | logout처럼 토큰 헤더 필수인 엔드포인트가 포함. `permitAll` 의 의미상 무인증 허용으로 읽혀 혼란 — 명시적 분리(`/api/auth/logout` 만 authenticated)로 가독성 ↑ |
| 🟢 | L-4 | AuthService.maskEmail | `MaskingUtil.maskEmail` 단순 위임 — 메서드 삭제 후 직접 호출 |
| 🟢 | L-5 | User.isSocialProvider | `provider != Provider.LOCAL` — 향후 다른 provider 추가 시 의미 모호. `provider == Provider.KAKAO` 더 명확 |
| 🟢 | L-6 | UserIdGenerator | `do/while existsById` — userRepository 의존. 충돌 확률 매우 낮으므로 단일 호출만으로 충분(또는 lookup 캐시) |
| 🟢 | L-7 | ApiLoggingAspect.currentUserId | principal이 String일 때만 처리. CustomUserDetails 케이스 누락. 두 principal 형 모두 지원하도록 instanceof 확장 |

---

## Phase B. 구조·품질 (architecture-review + spring-boot-patterns + java-code-review + clean-code)

### 구조 (architecture-review)

판정: 양호. 도메인 경계가 깨끗하다.

- `domain/user` → 이벤트 발행(`PasswordChangedEvent`, `UserWithdrawnEvent`) → `domain/auth/listener` 소비. 의존 방향 단방향(user → auth via event), 역방향 import 없음.
- `domain/auth/oauth/` 패키지가 auth 내부에 있는 점은 적절(카카오 OAuth는 auth 도메인 전용 외부 클라이언트).
- `SmsService`(회원가입 전용)와 `SmsVerificationService`(공통)의 분리도 책임이 명확.
- `global/util/RedisKeys` 중앙화: 키 prefix가 모두 한 곳에 모여 충돌 가능성 낮음.

발견 (M-9, M-10에 정리)

### Spring 패턴 (spring-boot-patterns)

- `@Transactional` 적용 적정.
  - `PasswordResetService.verifyEmailToken`, `verifySmsAndIssueToken` — 트랜잭션 어노테이션 없음. 메서드 내부에 user 조회만 있고 Redis 저장은 트랜잭션 밖이라 큰 문제는 없음. 일관성 위해 readOnly = true 권장. (🟢)
- `AFTER_COMMIT` 이벤트 처리: `UserAccountEventListener`가 정확히 `TransactionPhase.AFTER_COMMIT + REQUIRES_NEW`. 베스트 프랙티스 준수.
- `JwtAuthenticationFilter`는 `new`로 직접 생성. Filter 자체가 Bean이 아니므로 OK이지만, `@Bean`으로 등록하면 `OncePerRequestFilter` lifecycle을 Spring이 관리.

### Java/clean (java-code-review + clean-code)

- 복잡도 5단계 분기 초과 케이스 없음.
- 매직 넘버: `RESET_TOKEN_TTL_MINUTES = 30L`, `KAKAO_PENDING_TTL = 10L`, `VERIFIED_TTL_MINUTES = 10L` 등은 모두 명시 상수. ✅
- null/Optional: 적절히 사용. KakaoAuthService.kakaoLogin의 nested `findByProviderAndProviderId().map().orElseGet()`은 가독성 떨어짐 — 분기를 분리하면 가독성 향상 (🟢).
- L-4, L-5, L-6, L-7에 정리.

---

## Phase C. API 계약 (api-contract-review)

전반적 일관성: ✅
- 모든 응답이 `ApiResponse` 래퍼. 에러는 `GlobalExceptionHandler`에서 동일 포맷.

HTTP 상태 코드 일관성
- ✅ 잠금 → 429, SMS rate → 429.
- ⚠️ 비밀번호 재설정은 가입 여부 노출 방지를 위해 200(이메일/SMS) — 의도된 정책. 단, find-email은 404 USER_NOT_FOUND 반환 — 정책 차이가 의도된 것인지 재확인 (Swagger에 명시되어 있음 — 의도된 차이로 보임).

응답 포맷 일치
- 페이지네이션이 있는 엔드포인트는 admin 도메인만, auth/user 도메인은 단건. OK.

Swagger 문서 vs 실제 구현
- M-2 LoginResponse refresh 만료가 14일 vs 실제 7일. 수정.
- KakaoLoginResponse.isNewUser의 응답 구조는 단일 DTO에 두 모드를 합쳐 nullable 필드가 많음. 프론트가 이미 통합 완료 상태라면 변경 비권장. (보류)

---

## Phase D. 데이터 계층 (jpa-patterns + performance-smell-detection)

### N+1 / Lazy
- `User`, `RefreshToken`, `AccessLog` 모두 연관 관계 없음(외래키만, JPA relation 없음). N+1 가능성 없음. ✅

### 인덱스 적정성
- `users(email)` unique + 인덱스 ✅
- `users(phone)` unique partial 인덱스 (V2) ✅
- `users(provider, provider_id)` 인덱스 ✅
- `users(role, status, created_at DESC)` 복합 (V15, admin 검색용) ✅
- `users(name, phone)` 복합 인덱스 — find-email/SMS 비밀번호 재설정용 — 누락. 사용 빈도 낮으면 단일 phone/name 인덱스로 충분.
- `refresh_tokens(user_id)` 인덱스 ✅, `(token)` unique ✅, `(expires_at)` 인덱스(V11) ✅
- `access_logs(user_id)`, `(created_at)`, `(action, created_at)` (V11) ✅

성능 스멜
- `AuthService.login`: User 조회 1회 + RefreshToken delete + save + accessLog save → 4 DB calls. 적정.
- `KakaoAuthService.kakaoLogin`: WebClient 2회(token + userInfo) + DB 호출 다수. WebClient는 `RestClient.create()` 기본값 — 타임아웃 미설정. 카카오 API hang 시 스레드 잠금 위험. (🟡 추가) → M-14
- 비밀번호 재설정: SMTP는 외부 I/O. Async로 분리 검토 가능 (성능)
- JWT 파싱: 매 요청 1회 — HMAC256 검증 비용 미미. OK.

추가 발견 (Phase D)

| ID | 위치 | 내용 |
|------|------|------|
| 🟡 M-14 | KakaoOAuthClient | `RestClient.create()` — connect/read timeout 미설정. 외부 API hang 위험. timeout 명시 권장 |
| 🟡 M-15 | AccessLogRepository | `findAllByUserId` 같은 조회 메서드 없음 + countByAction 메서드는 dead code. 정리 필요 |

---

## Phase E. 로깅·관찰 (logging-patterns)

판정: 양호.

- `ApiLoggingAspect`가 모든 controller 메서드를 `@Around`로 감싸 [API] 로그 + userId MDC 자동 주입.
- PII 마스킹: `MaskingUtil.maskPhone`, `maskEmail` 적절히 사용. JWT/refreshToken/비밀번호/인증코드가 로그에 평문 출력되는 지점 없음.
- 카카오 OAuth 에러는 `log.error`로 응답 body 포함 — 카카오가 민감 정보 응답하지 않으므로 OK.
- WITHDRAW 액션 access_logs 기록 OK.

발견

| ID | 위치 | 내용 |
|------|------|------|
| 🟡 E-1 (위 M-3와 중복) | PasswordResetService.confirmReset | IP/UA 미기록 |
| 🟡 E-2 | AuthService.login fail/lock | 5회 잠금 발생 시 별도 log.warn 없음. 잠금은 보안 이벤트 → WARN/audit 권장 |
| 🟢 E-3 | MDC | userId만 주입. traceId 누락. 분산 환경 전환 시 필요 |
| 🟢 E-4 | ApiLoggingAspect | 파라미터 미로깅(progress.md PHASE 6 L3와 동일 트래킹) — 추후 keyword 마스킹 필요 |

---

## Phase F. 테스트 (test-quality)

### 기존 테스트 점검 (8개)

| 파일 | 평가 |
|------|------|
| `AuthServiceTest` | 로그인/회원가입/refresh/logout 골격 있음. 5회 잠금·INACTIVE refresh 삭제 케이스 보강 필요 |
| `SmsVerificationServiceTest` | 발송·검증·쿨다운 OK |
| `JwtTokenProviderTest` | 발급·검증·만료 처리 OK |
| `RateLimitServiceTest` | 분당 10회 경계 OK |
| `VerificationCodeValidatorTest` | 5회 시도 후 무효화 OK |
| `UserServiceTest` | 프로필 수정·탈퇴·비밀번호 변경 OK |
| `UserAccountEventListenerTest` | refresh 삭제 + access log 기록 OK |
| `PasswordFieldValidationTest` | 비밀번호 정규식 OK |

### 커버리지 갭

| 영역 | 우선순위 | 메모 |
|------|----------|------|
| KakaoAuthService (login 분기, 신규/기존, INACTIVE, 이메일 위변조 방어) | 🟠 | 가장 큰 갭. Mock WebClient or KakaoOAuthClient 인터페이스 추출 후 모킹 |
| PasswordResetService (이메일/SMS 양쪽 경로, 카카오 차단, requestReset의 "조용히 종료" 동작) | 🟠 | confirmReset 동시성 시나리오 포함 |
| FindPasswordController, PasswordResetController, KakaoAuthController | 🟡 | MockMvc로 통합 |
| AuthService.refresh — 동시성 (동일 token 동시 요청) | 🟠 | 재사용 탐지 검증용 |
| AuthService.login — 5회 잠금 + 잠금 중 정상 비밀번호 차단 | 🟠 | RateLimitServiceTest와 별개 |
| 동일 이메일 동시 가입 (race) | 🟡 | DataIntegrityViolationException → GlobalExceptionHandler 409 |

JUnit5 + Mockito + AssertJ + 한글 `@DisplayName` 컨벤션 유지 (progress.md 명시).

---

## Phase G. 환경·시크릿

### .env.dev 검토

| 항목 | 값/평가 |
|------|---------|
| `.gitignore` | `.env.*` 포함 ✅ (git check-ignore 확인 완료) |
| `JWT_SECRET` | 33자 ASCII. SecurityConfigValidator 통과(32자 이상). 길이는 충분(264bit), 다만 사람이 만든 듯한 패턴이라 엔트로피 검증 권장 |
| `POSTGRES_PASSWORD` | `dev12#` — 개발 환경이지만 너무 짧음. 운영에선 반드시 별도 |
| `MAIL_PASSWORD` | Gmail 앱 비밀번호 평문 ✅ (앱 비밀번호는 평문이 표준) |
| `KAKAO_REST_API_KEY` | 노출됨. 키 회수/재발급 권장? — 점검 리포트 기록 후 운영 결정 |
| `SOLAPI_API_*` | 노출됨. 동일 |
| `AI_SERVER_API_KEY` | 64자 hex (256bit). 적정. SecurityConfigValidator 32자 이상 통과 |
| `FIREBASE_SERVICE_ACCOUNT_BASE64` | base64 인코딩된 private key 포함. .env.dev에 평문 보존, gitignore 확인됨. 운영에선 Secret Manager 권장 |

발견

| ID | 위치 | 내용 |
|----|------|------|
| 🟡 G-1 | `.env.dev` | KAKAO/SOLAPI 키가 리포트 작성 시점에 점검자에게 노출됨. 점검 후 키 회전 검토 필요(.env.dev는 로컬 전용이라 gitignore되어 있지만, 점검 흐름에서 키가 컨텍스트에 들어옴) |
| 🟡 G-2 | application.yaml `jwt.refresh-token-expiration: 604800000` | LoginResponse Swagger 문서의 “14일”과 불일치(실제 7일). LoginResponse 문서 수정 |
| 🟡 G-3 | build.gradle | OWASP DependencyCheck 10.0.4 설정 OK. NVD API key 환경변수만 빠진 상태 — 운영자가 NVD_API_KEY 발급 후 등록 권장 |
| 🟢 G-4 | `.env.dev DB_PASSWORD=dev12#` | 개발 전용이지만 흔한 패턴. 로컬 도커 한정이라 위험도 낮음 |

### SecurityConfigValidator / RequiredPropertiesValidator
- 둘 다 PostConstruct에서 강제 검증. 빈 값/약한 값 시 시작 실패. ✅
- KNOWN_WEAK_JWT_SECRETS 목록에 과거 기본값 포함. 운영 배포 차단 효과 확실. ✅

---

## 프론트 호환성 영향 요약

| 항목 | 응답 포맷 | 동작 | 프론트 작업 |
|------|-----------|------|-------------|
| A-Critical-1 | 변경 없음 | 비밀번호 변경 직후 기존 access token 401 차단(이미 Swagger 안내) | 없음 |
| A-High-1 (enumeration) | **변경 있음** — 404 USER_NOT_FOUND → 401 INVALID_PASSWORD 통합 | 401 통합 응답 | 404 분기 제거 / 401 메시지 통일 (백엔드 통합 PR 전에 프론트 정리 권장) |
| A-High-2 | 변경 없음 | 잠금 키 정책 | 없음 |
| A-High-3 | 변경 없음 | 탈취 의심 시 강제 재로그인 | 없음 |
| A-High-4 | 변경 없음 | INACTIVE refresh 삭제 추가 | 없음 |
| A-High-5 (SMS 인증) | **변경 있음**(권장 적용 시) — 인증 응답에 nonce 추가, 가입 요청에 nonce 포함 | 클라이언트 추가 필드 전송 | 회원가입 흐름 nonce 보관·전송 |
| A-High-6 (카카오 탈퇴) | **변경 있음**(권장 적용 시) — WithdrawRequest에 confirmation 추가 | 사용자 명시적 동의 입력 | 탈퇴 화면 confirmation UI |
| A-High-7 | 변경 없음 | dead code 정리 | 없음 |
| M-2 (Swagger 문서) | 문서 수정만 | 동작 변경 없음 | 없음 |

응답 포맷에 직접 영향 가는 변경은 H-1, H-5, H-6 세 건. 모두 점진 적용 가능(필드 추가 → 검증 활성화 분리 적용).

---

## 미해결 TODO (다음 사이클 이월)

- A-High-3 family invalidation 적용 시 `refresh_tokens` 스키마 변경 필요 — Flyway V16 마이그레이션 별도 PR
- M-1 카카오 OAuth state 검증 — 카카오 측 SDK가 프론트에서 state 검증을 하는지 확인 후 결정
- A-High-6 카카오 탈퇴 본인 확인 — UX 설계 협의 후 진행
- 동시성 테스트 추가(JUnit 5 `@RepeatedTest` + Awaitility 또는 별도 통합 테스트)
- OWASP DependencyCheck CI 결과 점검(`./gradlew dependencyCheckAnalyze` 직접 실행 → 별도 보고서)
- KAKAO/SOLAPI 키 회전 운영 결정

---

## 커밋 메시지 초안 (자동 커밋 금지 — 사용자 승인 후 적용)

수정 PR을 분리해서 진행 권장. 각 PR별 초안.

```
fix(auth): 비밀번호 변경/재설정 후 access token 즉시 무효화
- users.password_changed_at 컬럼 추가(V16) 및 사용자별 invalidation timestamp 도입
- JwtAuthenticationFilter가 토큰 iat와 invalidation timestamp 비교 후 401 반환
- PasswordResetService.confirmReset / UserService.changePassword / UserAccountEventListener에서 invalidation 갱신
- 비밀번호 변경 직후 기존 access token이 30분간 유효하던 보안 결함 해소
```

```
fix(auth): 로그인 응답 통합으로 계정 enumeration 차단
- USER_NOT_FOUND(404) → INVALID_PASSWORD(401) 통합
- INACTIVE 분기를 비밀번호 검증 이후로 이동
- 프론트 호환성: 404 분기 제거 필요 (별도 PR로 선행)
```

```
fix(auth): 로그인 잠금 키를 user.id 기반으로 변경하여 DoS 차단
- LOGIN_FAIL/LOGIN_LOCK 키를 email 기반에서 user.id 기반으로 전환
- 미존재 사용자의 fail count 증가는 IP 기반으로만 처리
```

```
feat(auth): Refresh Token Rotation 재사용 탐지(family invalidation)
- refresh_tokens.family_id 컬럼 추가(V17)
- AuthService.refresh가 이전 token 재사용 감지 시 해당 user의 모든 token 무효화
- access_logs에 TOKEN_REUSE_DETECTED 액션 추가
```

```
fix(auth): INACTIVE 사용자 로그인 차단 시 refresh token 즉시 삭제
- AuthService.login, KakaoAuthService.kakaoLogin 의 INACTIVE 분기에 deleteByUserId 추가
- AuthService.refresh와의 일관성 확보
```

```
fix(auth): SMS 인증 완료 상태에 nonce 결합하여 phone 재사용 차단
- 인증 응답에 nonce 발급, 회원가입 요청에 포함
- SMS_VERIFIED 키 값으로 nonce 저장하여 phone 단일 식별 제거
```

```
fix(user): 카카오 사용자 탈퇴 시 명시적 confirmation 입력 요구
- WithdrawRequest.confirmation 필드 추가, "탈퇴" 문자열 일치 검증
- access token 단독 탈취 시 즉시 탈퇴 위협 차단
```

```
chore(auth): AccessLogRepository.countByActionAndCreatedAtAfter 시그니처 수정
- @Param("action") String → AccessAction enum
- JPQL action 비교 정상화. 현재 호출자 없음 — 미래 사용 시 버그 차단
```

```
docs(swagger): refresh token 만료 표기 일치화(7일)
- LoginResponse Swagger 문서의 "유효 시간: 14일" → "7일" 수정 (실제 application.yaml과 정합)
```

---

## 참고

- Phase 분류 기준: 보안(A) → 구조/품질(B) → API(C) → 데이터(D) → 로깅(E) → 테스트(F) → 환경(G).
- 적용 가이드: Critical 우선, High는 다음 배포 전, Medium은 다음 스프린트, Low는 백로그.
- 본 리포트는 분석·제안 단계. 실제 코드 수정은 사용자 검토·승인 후 별도 PR로 진행.
