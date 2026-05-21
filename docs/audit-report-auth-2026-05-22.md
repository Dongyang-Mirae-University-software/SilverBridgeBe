# auth/user 도메인 보안·구조 종합 점검 보고서 (2026-05-22 라운드 / 3차)

**점검 일자**: 2026-05-22
**점검자**: Claude Code (Opus 4.7, 1M)
**대상 브랜치**: `fix/auth-audit-2026-05-22` (`dev` 분기)
**점검 트리거**: 1·2차 라운드 종료 후 프로토타입 정합(`cb6c211`) 반영 상태에서 보안 핵심 도메인 재점검 요청

> 본 보고서는 3차 라운드입니다. 이전 결과는 보존됩니다.
> - `docs/audit-report-auth.md` (2026-05-15, 1차)
> - `docs/audit-report-auth-2026-05-20.md` (2026-05-20, 2차)

---

## 0. 이전 시도 흔적 처리 결과 (PHASE -1)

이전 세션 "꼬임" 가능성 점검 결과, **점검 작업 자체는 꼬이지 않았고**(1·2차 모두 완료·머지·문서화) 작업 트리도 깨끗했다. 단 하나의 정리되지 않은 흔적을 발견:

- **`.claude/settings.json` (커밋 안 됨)**: git 안전장치 `deny`(`git push origin dev` / `git push --force` / `git commit`)가 제거되어 `allow`로 이동된 상태. 직접 push/commit이 무프롬프트 자동 허용되는 위험.
- **처리 방침(사용자 결정)**: **그대로 두고 진행**. 본 점검 중 어떤 git commit/push도 자동 수행하지 않음(CLAUDE.md §2). 본 라운드 코드 변경에는 이 파일을 **포함하지 않음**.

---

## 1. 점검 범위

### 1차 — auth / user 도메인
`domain/auth/{config,controller,dto,entity,listener,oauth,repository,service}` (46) · `domain/user/{controller,dto,entity,event,repository,service}` (10)

### 2차 — 보안 횡단
`global/jwt`(3) · `global/security`(SecurityConfig·RateLimitService 등) · `global/util`(VerificationCodeValidator·MaskingUtil·RedisKeys·RedisCounter) · `global/config`(SecurityConfigValidator·RequiredPropertiesValidator) · `global/validation`(BirthDateValidator·ValidBirthDate) · `global/enums`(Gender) · `global/aop`(ApiLoggingAspect) · `global/exception`(ErrorCode·GlobalExceptionHandler)

### 3차 — 스키마·정책·환경
Flyway V1~V18(users/refresh_tokens 관련), application.yaml, .gitignore

### 검토했으나 미구현 확인(결정 #1)
- **로그인 유지**: 백엔드 코드 부재 — 프론트 책임(refreshToken 저장 위치). 만료(30분/7일) 고정.
- **약관 동의**: 백엔드 미구현.

---

## 2. 적용 스킬
PHASE 0 architecture-review / spring-boot-patterns / jpa-patterns · PHASE A security-audit / concurrency-review · PHASE B architecture/spring/clean/solid · PHASE C api-contract-review · PHASE D jpa-patterns/performance-smell-detection · PHASE E logging-patterns · PHASE F test-quality

---

## 3. 요약

| 등급 | 발견 | 본 라운드 수정 | 이월 |
|------|------|----------------|------|
| 🔴 Critical | 0 | — | — |
| 🟠 High | 2 | 2 | 0 |
| 🟡 Medium | 4 | 2 | 2 |
| 🟢 Low | 8 | 6 | 2 |

> 1·2차에서 적용된 보안 fix(비번 변경 후 access token 무효화, refresh 폐기 REQUIRES_NEW, rotation+재사용 감지, 로그인 응답 통합, SMS nonce, BCrypt 12, JWT secret 검증 등)는 모두 **현행 코드에 살아있음을 재검증**.

---

## 4. 발견 이슈 (Phase별)

### 🟠 High

**A-H1 — JWT 토큰 타입 미구분 (refresh→access 혼용)** · `JwtAuthenticationFilter`, `JwtTokenProvider` · **수정 완료**
필터가 access/refresh를 구분하지 않아 refresh token을 `Authorization: Bearer`로 제시하면 `authenticated()` 엔드포인트(`/api/user/me/*` 등)가 통과됐고(role 없음→`ROLE_null`이라 admin은 차단), 로그아웃 후에도 유출 refresh token이 7일간 access처럼 동작.
→ 토큰에 `typ`(access/refresh) 클레임 추가, 필터는 access만 허용. typ 없는 과거 토큰도 거부(배포 후 자연 재발급으로 전환).

**A-H2 — `/signin` IP RateLimit 부재** · `AuthController` · **수정 완료**
per-user 잠금(5회/30분)이 막지 못하는 계정 분산 credential stuffing / password spraying이 무제한이었음. `RateLimitService.check("signin", ip)` 추가(1분 10회, 타 엔드포인트와 동일).

### 🟡 Medium

**A-M1 — 비번재설정 confirm 계정 enumeration** · `PasswordResetService.confirmReset` · **수정 완료**
코드 검증 전에 user를 조회해 미가입(404)/가입(400)/소셜 응답이 달라 enumeration·provider 추론 가능. → 6자리 코드 검증을 user 조회보다 **선행**. 미가입/카카오는 코드 미발급이라 동일하게 EXPIRED로 막힘(requestReset의 always-200과 정합).

**A-M3 — SMS 발송 per-phone/전역 상한 부재** · `SmsVerificationService` · **수정 완료**
쿨다운 폐지로 빈도 방어가 IP RateLimit(10/min)에만 의존 → IP 회전 시 임의 번호로 SMS 폭탄·비용 남용. → 공통 발송 길목에 **per-phone 시간당 10회 상한**(`sms:sendcount:{phone}`) 추가, 초과 시 429.

**A-M2 — X-Forwarded-For 무조건 신뢰** · `application.yaml` (`forward-headers-strategy: framework`) · **이월(인프라 확인)**
프록시 뒤에선 정상이나, 앱 포트(6511) 직접 접근 시 XFF 위조로 RateLimit 우회 + access_logs IP 오염. → 프록시 단일 인입 보장 또는 trusted-proxy 설정. 코드가 아닌 배포 토폴로지 확인 사안이라 이월.

**C-1 — `isNewUser` 직렬화 명칭 불일치 위험** · `KakaoLoginResponse` · **수정 완료**
`boolean isNewUser` + Lombok/Jackson 규칙으로 JSON이 `newUser`로 직렬화될 수 있어 Swagger·문서(`isNewUser`)와 불일치. → `@JsonProperty("isNewUser")`로 키 고정.

### 🟢 Low

| ID | 항목 | 파일 | 수정 |
|----|------|------|------|
| C-2 | 회원가입 상태코드 정렬(`/signup`=201 vs `/signup/kakao`=200) | `KakaoAuthController` | ✅ 201 정렬 |
| A-L1 | 인증코드 비교 constant-time화 | `VerificationCodeValidator` | ✅ `MessageDigest.isEqual` |
| A-L2 | 보안 헤더(HSTS·X-Frame-Options·X-Content-Type-Options·Referrer-Policy) | `SecurityConfig` | ✅ 부분(아래 CSP 이월) |
| A-L4 | 데드코드(`AuthenticationManager`·`CustomUserDetailsService`·`CustomUserDetails`) | `SecurityConfig` 외 | ✅ 제거 |
| A-L5 | 제거된 도메인 잔재(AI permitAll·AI 키 검증·`GAME_RESULT_NOT_FOUND`·미사용 인증 ErrorCode 5개) | 다수 | ✅ 제거 |
| A-L6 | 생년월일 상한(만 120세) 부재 | `BirthDateValidator`/`ValidBirthDate` | ✅ 상한 추가 |
| D-3 | `getSigningKey()` 매 호출 SecretKey 재생성 | `JwtTokenProvider` | ✅ 캐싱 |
| B-2 | 카카오 fallback email 매직 문자열 | `KakaoAuthService` | ✅ 상수화 |
| E-2 | 요청 추적 traceId MDC 부재 | `ApiLoggingAspect`+yaml | ✅ traceId 추가 |
| E-3 | 로그인 잠금 보안 WARN 로그 부재 | `AuthService` | ✅ 잠금 시 WARN |

### ✅ 재검증 (양호 — 변경 없음)
N+1 없음(User 컬렉션 없음) · email/phone(부분) DB unique → 동시 가입 DataIntegrityViolation→409 · refresh token unique+인덱스 · TokenCleanupScheduler `@EnableScheduling` 작동 · @AuthenticationPrincipal self-scoped(IDOR 없음) · PII 마스킹·OAuth body 미로깅 · 환경변수 시작 검증 + `.env.*` gitignore · JWT secret ≥256bit 검증.

### concurrency-review
fail counter·SMS attempt 원자적(Lua) ✅ · 동시 가입 unique→409 ✅ · 동시 refresh(같은 토큰 2회) 시 토큰 2개 생성 가능(same-user, 영향 미미) — 🟢 관찰만.

---

## 5. 프론트 호환성 영향

응답 **필드 삭제·이름 변경 없음**. 아래만 유의:

| 항목 | 영향 | 비고 |
|------|------|------|
| A-H1 토큰 타입 | 배포 시 기존 발급 access token(typ 없음)이 1회 401 → 클라이언트가 refresh로 재발급(typ 포함) → **자동 복구**. 정상 흐름(401→refresh→retry) 클라이언트는 무중단 | 응답 포맷 불변. refresh token을 Bearer로 보내던 클라이언트만 영향(정상 클라이언트 아님) |
| A-H2 `/signin` RateLimit | 1분 10회 초과 시 429 추가 | 정상 사용자 영향 없음 |
| C-1 `isNewUser` | JSON 키 `isNewUser`로 고정 | 카카오 프론트 미구현 — 영향 없음, 오히려 계약 정합 |
| C-2 카카오 가입 201 | `/signup/kakao` 200→201 | 카카오 프론트 미구현 — 영향 없음 |

---

## 6. 미해결 TODO

### 6.1 후속 처리 완료 (2026-05-22 follow-up, 별도 PR)
- **A-M2** ✅ `docker-compose.dev.yml` api publish를 `127.0.0.1:6511`로 제한 — 외부 직접 접근 차단(Swagger·API는 nginx 도메인 경유). 배포 후 nginx의 `/swagger-ui` 프록시 동작 확인 권장
- **A-L3 / G-2** ✅ `application.yaml` DB 자격증명 약한 기본값(`dev`) 제거 + `RequiredPropertiesValidator`에 `DB_USERNAME`/`DB_PASSWORD` 편입(fail-fast) — `.env.dev`가 명시 설정함을 확인
- **A-L2 CSP** ✅ `SecurityConfig`에 Swagger 호환 CSP 추가(`default-src 'self'` + script/style `unsafe-inline` + `frame-ancestors 'none'`/`object-src 'none'`/`base-uri 'self'`)
- **B-1** ✅ `PhoneVerificationPort`(user 도메인) 추출 — `UserService`가 포트 의존, `SmsService`가 구현. user→auth 직접 의존 제거(의존 방향 auth→user 단방향 정렬)

### 6.2 잔여 이월 (다음 사이클)
- **L-C1**(2차 이월) `UserController` RESTful 경로(`/me/select`·`/me/update` 등) — 통합된 프론트를 깨뜨려 프론트 마이그레이션 협의 필요
- **E-4** 약관 동의 시점 기록 — 약관 백엔드 미구현(결정상 구현 안 함). 구현 시 access_logs 또는 별도 테이블

---

## 7. 테스트 (PHASE F — 갭 전부 작성, 74건 통과)

**신규(5)**: `JwtAuthenticationFilterTest`(5, A-H1 회귀 포함) · `KakaoAuthServiceTest`(8) · `PasswordResetServiceTest`(7, A-M1 회귀) · `RefreshTokenRevocationServiceTest`(2) · `BirthDateValidatorTest`(7)
**보강(2)**: `JwtTokenProviderTest`(typ 구분 + caching 대응) · `SmsVerificationServiceTest`(per-phone 캡)
검증: `./gradlew test`(대상 74건 통과) + `./gradlew build -x test`(BUILD SUCCESSFUL).

---

## 8. 비고
- 본 라운드 코드 변경: 21개 파일 수정 + 2개 삭제 + 5개 테스트 신규 (`.claude/settings.json` 제외).
- 모든 변경은 결함 단위 커밋으로 분리 권장(커밋 초안은 PR 설명/세션 기록 참조). 자동 commit/push 미수행(CLAUDE.md §2).
