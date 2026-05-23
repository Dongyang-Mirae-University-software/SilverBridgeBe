# auth/user 도메인 보안·구조 점검 보고서 (2026-05-20 라운드)

**점검 일자**: 2026-05-20
**점검자**: Claude Code (Opus 4.7)
**대상 브랜치**: `feature/audit-auth-2026-05-20` (`dev` 분기)
**점검 트리거**: 2026-05-20 프로토타입 정합(`cb6c211`)으로 프로필 필드(성별·생년월일·우편번호) 추가 및 비밀번호 재설정 6자리 통일 이후 스킬 기반 점검 미수행

> ⚠️ 본 보고서는 2026-05-16 보안 1차 라운드(H-1~H-6, M-2~M-13) 종료 이후의 **재점검**입니다. 이전 라운드 결과는 `docs/audit-report-auth.md` (2026-05-15 자) 에 보존되어 있고, 본 문서는 별도 파일로 누적.

---

## 1. 점검 범위

### 1차 — auth / user 도메인 (52 파일)
- `domain/auth/{config,controller,dto,entity,listener,oauth,repository,service}` (42)
- `domain/user/{controller,dto,entity,event,repository,service}` (10)

### 2차 — 보안 횡단 (10 파일)
- `global/jwt/` (3), `global/security/` (4), `global/util/{VerificationCodeValidator,MaskingUtil,RedisKeys,RedisCounter}`, `global/config/{SecurityConfigValidator,RequiredPropertiesValidator}`

### 3차 — 스키마·정책
- Flyway V16 (TOKEN_REUSE_DETECTED 액션), V18 (gender/birth_date/postcode/name 축소)
- `ErrorCode` 인증/인가 관련 항목

### 제외
- 로그인 유지 (프론트엔드 책임, 백엔드 코드 부재)
- 약관 동의 (백엔드 미구현 — 프론트에서 단계 제거 예정)
- V18 이전 사용자 NULL 보정 마이그레이션 (제품 결정 사항으로 분리)

---

## 2. 적용 스킬

| Phase | 스킬 | 목적 |
|-------|------|------|
| 0 | architecture-review / spring-boot-patterns / jpa-patterns | 점검 기준 수립 |
| A | security-audit / concurrency-review | 인증·인가·동시성·민감정보 |
| B | architecture / spring / clean / solid | 구조·품질 |
| C | api-contract-review | HTTP 계약 일관성 |
| D | jpa-patterns / performance-smell-detection | 데이터·성능 |
| E | logging-patterns | 로깅·관찰 |
| F | test-quality | 테스트 커버리지 갭 |
| G | (security-audit 보조) | 환경·시크릿 |

---

## 3. 발견 이슈 (심각도별)

### 🔴 Critical — 1차 커밋 `2e91381` 으로 수정 완료

| ID | 위치 | 내용 | 조치 |
|----|------|------|------|
| C-1 | `AuthService.java:138-143` (login INACTIVE) | INACTIVE 차단 시 `deleteByUserId` 직후 throw로 트랜잭션 롤백 → refresh token 실제로 폐기 안 됨 (H-4 fix 무효) | `refreshTokenRevocationService.revokeAll()` (REQUIRES_NEW) |
| C-2 | `AuthService.java:189-193` (refresh EXPIRED) | 만료 토큰 `delete(savedToken)` 후 throw로 롤백 — 매일 3시 스케줄러 전엔 잔존 | `revokeOne()` |
| C-3 | `AuthService.java:199-203` (refresh INACTIVE) | C-1과 동일 패턴 | `revokeOne()` |
| **C-4** | `AuthService.java:259-265` (`detectAndHandleReuse`) | **도난 감지 시 `deleteByUserId` 가 caller throw로 롤백 — H-3 fix 무효. access_log만 REQUIRES_NEW로 살아남고 실 token 회수 안 됨** | `revokeAll()` |
| C-5 | `KakaoAuthService.java:62-66` (kakaoLogin INACTIVE) | C-1과 동일 패턴 | `revokeAll()` |

**근거 체인**:
1. `CustomException extends RuntimeException` (`global/exception/CustomException.java:6`)
2. Spring `@Transactional` default rollback rule = RuntimeException
3. `rollbackFor`/`noRollbackFor` 미지정 (grep 결과 0건)
4. 같은 트랜잭션 안의 `delete*` → 직후 `throw CustomException` 시 **모두 롤백**

**도입한 패턴**: `RefreshTokenRevocationService` (`@Transactional(REQUIRES_NEW)`) — `AccessLogService`와 동일하게 호출 트랜잭션과 분리. 정상 흐름(로그인 단일 디바이스 정책의 deleteByUserId, refresh rotation의 delete)은 throw가 없어 원본 호출 유지.

**테스트**: `AuthServiceTest`의 3개 verify(도난 감지·만료 케이스)를 revocation 호출 검증으로 정정.

---

### 🟠 High — 2차 누적 커밋에 반영 완료

| ID | Phase | 위치 | 발견 | 조치 |
|----|-------|------|------|------|
| H-A1 | A1 | `AuthService.java:122-136` | login fail counter `increment` + `expire` 분리. 두 명령 사이 장애 시 TTL 누락. RateLimitService/VerificationCodeValidator의 `RedisCounter.incrementWithTtl` 패턴 미적용 (M-4 라운드 누락분) | **Fixed** — `redisCounter.incrementWithTtl(failKey, lockTtlMinutes * 60)` 적용. 윈도우는 첫 실패 시각 기준 고정(sliding 잠재 영구화 위험도 함께 제거) |
| H-A8 | A8 | `KakaoOAuthClient.java` | `RestClient.create()` — 카카오 토큰/유저정보 호출에 connect/read timeout 미설정 (기본 무한 대기). 카카오 서버 응답 지연 시 Tomcat connection thread 점유 → 모든 요청 행함 | **Fixed** — `SimpleClientHttpRequestFactory` + `connectTimeout=3s, readTimeout=5s` |

---

### 🟡 Medium — 2차 누적 커밋에 일부 반영 / 일부 다음 스프린트

| ID | Phase | 위치 | 발견 | 조치 |
|----|-------|------|------|------|
| M-M1 | A5 | `KakaoOAuthClient.java`, `JwtAuthenticationFilter.java`, `SecurityConfig.java` | `ObjectMapper`를 직접 생성. `JacksonConfig`의 안전 설정 미적용 | **Fixed** — Spring 빈 `ObjectMapper` 주입(KakaoOAuthClient 생성자 / JwtAuthenticationFilter `@RequiredArgsConstructor` / SecurityConfig에서 필터 생성 시 전달) |
| M-M2 | A3 | 인증 DTO 10건 | 비밀번호·이메일·verificationNonce·kakaoId 등 입력 길이 상한 누락/과대. BCrypt 72byte cutoff 고려 미반영 | **Fixed (확장)** — §10-입력 길이 정책 변경 참조 (email max=50, password max=64, verificationNonce max=36, kakaoId max=20 전체 DTO 일관 적용) |
| M-M3 | E | `KakaoOAuthClient.java` | `log.error("…body={}", body)` — 토큰 발급 실패 응답 본문 통째로 출력. errorCode/code만 의미 있음 | **Fixed** — `extractTokenErrorCode/extractApiErrorCode` 헬퍼 추가, log에는 status + errorCode만 출력 |
| M-B1 | B | `UserService.java:3` → `domain.auth.service.SmsService` | user 도메인 → auth 도메인 역방향 의존 (`consumeVerification` 호출). 도메인 경계 흐림 | 이월 — 인증 nonce 검증을 Controller 인터셉터로 이동 또는 `PhoneVerificationPort` 인터페이스로 추출. 현 구조 유지도 수용 가능 (별도 협의) |
| M-F1 | F | `src/test/.../KakaoAuthServiceTest.java` 부재 | 신규/기존 사용자 분기·INACTIVE·이메일 중복·SMS nonce 검증 등 커버리지 0 | 이월 — JUnit 5 + Mockito + AssertJ |
| M-F2 | F | `src/test/.../PasswordResetServiceTest.java` 부재 | 이메일/SMS 양쪽 경로·만료·5회 초과·카카오 차단·SAME_AS_CURRENT 등 미커버 | 이월 |
| M-F3 | F | `BirthDateValidatorTest` 부재 | 미래 날짜/만 14세 경계/null 통과 검증 누락 (V18 신규 필드) | 이월 |
| M-F4 | F | `RefreshTokenRevocationServiceTest` 부재 | 신규 서비스. REQUIRES_NEW로 실제 commit되는지 통합 테스트 필요 | 이월 — `@SpringBootTest` 또는 `@DataJpaTest` |
| M-F5 | F | `JwtAuthenticationFilterTest` 부재 | 로그아웃 토큰 차단·비밀번호 변경 후 invalidation·Bearer 형식 검증 누락 | 이월 — `@WebMvcTest` 또는 MockMvc |

---

### 🟢 Low — 백로그

| ID | Phase | 위치 | 발견 | 권장 |
|----|-------|------|------|------|
| L-A5 | A5 | `SecurityConfig.java` | HSTS/X-Frame-Options/X-Content-Type-Options/CSP 미설정. REST API라 직접 영향 제한적 | `http.headers(...)` 적용 |
| L-C1 | C | `UserController.java` | 경로가 동사 포함: `/me/select`, `/me/update`, `/me/update/password-change`, `/me/delete`. 프론트 통합 완료라 변경 비용 큼 | 프론트 마이그레이션 가능 시 RESTful 경로로 정리 (`GET /me`, `PUT /me`, `PUT /me/password`, `DELETE /me`) |
| L-A4 | A4 | `FindEmailResponse.java:33` | 가입일(`joinedAt`) 응답 노출. name+phone 매칭으로 가입일 reconnaissance 가능. 사용자 정책으로 허용됨 | 변경 없음 |
| L-A3 | A3 | `AuthService.register/kakaoRegister` | `existsByEmail/Phone` 후 `save` 사이 race. `DataIntegrityViolationException` → 409 매핑으로 보호됨 (`GlobalExceptionHandler.java:111`). 메시지 통합도 OK | 변경 없음 (이미 안전) |
| L-A6 | A6 | `VerificationCodeValidator.java:38,72` | `savedCode.equals(inputCode)` — non-constant time. MAX_ATTEMPTS 5회로 차단되어 영향 제한적 | 선택 사항 |
| L-G1 | G | `application.yaml` DB 기본값 | `DB_USERNAME:dev` / `DB_PASSWORD:dev` 기본값. 로컬 컨테이너 의도이나 운영 env 누락 시 사용 가능 | `:CHANGE-ME` placeholder로 변경 |
| L-D1 | D | V18 신규 컬럼 | gender/birth_date/postcode 인덱스 없음. 현재 검색·정렬 쿼리 없음 | 향후 관리자 통계 도입 시 검토 |
| L-F1 | F | `PasswordResetController` MockMvc 통합 테스트 부재 | 컨트롤러 통합 테스트 없음 | 후속 |

---

## 4. 통과한 항목 (점검은 했으나 이슈 없음)

- **A1 비밀번호 해싱**: BCrypt strength 12 (OWASP 2025 권장)
- **A1 JWT**: HS256 + secret ≥ 32 bytes 강제 (`SecurityConfigValidator`)
- **A1 만료**: access 30분 / refresh 7일 — 적정
- **A1 RT Rotation**: `AuthService.refresh` 가 옛 token 폐기 후 새 token 발급 — 정상 (Critical fix 이후 실제로 동작)
- **A1 로그아웃 블랙리스트**: SHA-256 hash 키로 Redis 적용 (M-6)
- **A2 인가**: `SecurityConfig` path 기반 + `/api/admin/**` hasRole + JWT 필터 체인 순서 OK
- **A2 비밀번호 변경 후 access token 즉시 무효화**: `password:invalidate` 도장 + 필터 `iat ≤ invalidatedAt` 차단 (afcd495 Critical fix)
- **A3 입력 검증**: 신규 필드(gender/birthDate/postcode) 전부 `@Valid` 적용, `@ValidBirthDate` 커스텀 검증 동작
- **A4 enumeration 차단**: USER_NOT_FOUND + INVALID_PASSWORD → INVALID_CREDENTIALS 통합 (H-1)
- **A4 비밀번호 재설정 가입 여부 비노출**: requestReset / requestResetBySms 모두 미가입 시 200 반환
- **A6 SMS·이메일 어뷰징**: IP RateLimit (60s/10req) + nonce 1회용 + MAX_ATTEMPTS 5
- **A6 인증 완료 nonce**: SMS_VERIFIED 키 + UUID nonce → 같은 phone에 다른 사용자가 우회 가입 차단 (H-5)
- **A7 단일 디바이스**: login·refresh 모두 기존 RT 삭제 후 신규 저장
- **A7 TokenCleanupScheduler**: 매일 새벽 3시 만료 토큰 일괄 삭제
- **A8 카카오 인가코드 재사용**: KOE320/321 매핑 + Redis pending 1회 소비
- **A8 카카오 신규 가입 4단계**: pending TTL 10분 + SMS nonce 별도 검증 → 단계 건너뛰기 방어
- **A9 카카오 탈퇴 confirmation**: confirmation="탈퇴" 검증 (H-6)
- **B 도메인 경계**: listener(`auth → user.event`), oauth 하위 패키지 위치, 이벤트 AFTER_COMMIT — 양호
- **B 트랜잭션**: 메서드별 `@Transactional` 명시. readOnly 분리. `M-5` 외부 호출 트랜잭션 분리도 적용됨
- **C 응답 포맷**: 전 경로 `ApiResponse<T>` 일관. 필터 401도 동일 포맷
- **C 신규 필드 Swagger**: gender/birthDate/postcode/joinedAt/verificationNonce 모두 `@Schema` 적용
- **D JPA**: User 엔티티 관계 매핑 없음 → N+1 없음. existsBy 활용. open-in-view=false
- **D 인덱스**: V11에서 누락 인덱스 보강. V15에서 (role,status,created_at) 추가
- **D unique 충돌**: DataIntegrityViolationException → 409 매핑 (`GlobalExceptionHandler.java:111`)
- **E PII 마스킹**: MaskingUtil.maskPhone/maskEmail 적용. 토큰/비밀번호/인증코드 직접 로깅 없음
- **E MDC userId**: `[user=%X{userId:-anonymous}]` 패턴 적용 (`ApiLoggingAspect`)
- **E access_logs WITHDRAW**: listener에서 정상 기록
- **G 환경변수**: JWT_SECRET·AI_KEY 길이/약한 값 거부 (`SecurityConfigValidator`). 필수 키 누락 일괄 검출 (`RequiredPropertiesValidator`)
- **G .gitignore**: `.env.*` 패턴 적용
- **G server.forward-headers-strategy=framework**: NGINX 뒤에서도 X-Forwarded-* 신뢰 (RateLimit·access_log IP 정확)

---

## 5. 프론트 호환성 영향

**없음.** 본 PR의 수정은 모두 내부 동작 변경 — 응답 포맷·HTTP 상태·필드 변경 0건.

향후 권장 수정에서 프론트 영향 가능 항목:
- **L-C1**: UserController 경로 RESTful 정리 시 프론트 URL 마이그레이션 필요 (협의 사항)

---

## 6. 권장 수정 우선순위 (이번 PR 이후)

1. **(차기 스프린트)** M-F1~M-F5 — 테스트 갭. 회귀 방어
2. **(백로그)** L-A5 보안 헤더 + L-G1 DB credential placeholder
3. **(별도 협의 후)** L-C1 RESTful 경로 정리 — 프론트 마이그레이션 동반
4. **(별도 협의 후)** M-B1 UserService → SmsService 역방향 의존

---

## 7. 미해결 TODO (이월)

- [x] ~~H-A1: AuthService.login의 fail counter atomic 처리~~ — 본 PR 반영
- [x] ~~H-A8: KakaoOAuthClient timeout 설정~~ — 본 PR 반영
- [x] ~~M-M1: ObjectMapper Spring 빈 주입 (2개소)~~ — 본 PR 반영
- [x] ~~M-M2: 입력 길이 정책 정비~~ — 본 PR 반영 (10개 DTO 일관 적용)
- [x] ~~M-M3: KakaoOAuthClient log body 마스킹~~ — 본 PR 반영
- [ ] M-F1: `KakaoAuthServiceTest` 신설
- [ ] M-F2: `PasswordResetServiceTest` 신설
- [ ] M-F3: `BirthDateValidatorTest` 신설
- [ ] M-F4: `RefreshTokenRevocationServiceTest` 신설 (REQUIRES_NEW 통합 검증)
- [ ] M-F5: `JwtAuthenticationFilterTest` 신설
- [ ] L-A5: SecurityConfig 보안 헤더
- [ ] L-G1: DB credential 기본값 변경
- [ ] L-C1: UserController RESTful 경로 — 프론트 협의 필요
- [ ] M-B1: UserService → SmsService 역방향 의존 — 별도 협의

---

## 8. 산출물

- `docs/audit-report-auth-2026-05-20.md` — 본 보고서 (신규)
- `docs/audit-report-auth.md` — 이전 2026-05-15 점검 보고서 (그대로 보존)
- `docs/progress.md` — "2026-05-20 auth/user 2차 점검" 섹션 append
- 1차 커밋 `2e91381` — Critical 5개소 수정
- 2차 누적 커밋 — High 2 + Medium 5 (본 PR 마지막 커밋)

---

## 9. 점검 메타

- 총 점검 파일: 62 (1차 52 + 2차 10) + 마이그레이션 V16/V18 + ErrorCode
- 발견 이슈: Critical 5건 + High 2건 + Medium 9건 + Low 9건 = **25건**
- 본 PR 반영: Critical 5 + High 2 + Medium 5 (M-M1~M-M3 포함, M-B1·M-F1~M-F5 이월) = **12건 수정**
- 통과 항목: 30+ 영역
- 빌드/테스트: `./gradlew test` BUILD SUCCESSFUL (모든 수정 반영 후)

---

## 10. 입력 길이 정책 변경 (Best Practice 정비)

본 PR에서 M-M2를 계기로 인증 DTO 전반의 입력 길이 상한을 베스트 프랙티스 기준으로 통일.

### 변경 사항

| 항목 | 변경 전 | 변경 후 | 근거 |
|------|---------|---------|------|
| **email** | LoginRequest=100, 다른 DTO 없음 | 모든 인증 DTO **max=50** | RFC 5321 254 / 한국 서비스 평균 50 / 외부 회사 이메일 수용 |
| **password (plain input)** | RegisterRequest `@Size(min=8)`만, LoginRequest 없음, WithdrawRequest 100 | 모든 비밀번호 입력 **max=64** (NewPassword 류는 `min=8, max=64`) | OWASP 권장 + BCrypt 72byte cutoff 이내 |
| **verificationNonce** | 없음 (UUID) | RegisterRequest/KakaoRegisterRequest/UserUpdateRequest **max=36** | UUID 표준 36자 |
| **kakaoId** | `@NotBlank`만 | KakaoRegisterRequest **max=20** | Long 최대 19자(2^63) 여유 |

### 영향받은 DTO (10건)

- `LoginRequest` (email 100→50, password 신규 max=64)
- `RegisterRequest` (email 신규 max=50, password min=8 + 신규 max=64, verificationNonce 신규 max=36)
- `EmailCheckRequest` (email 신규 max=50)
- `KakaoRegisterRequest` (kakaoId 신규 max=20, verificationNonce 신규 max=36)
- `PasswordResetRequest` (email 신규 max=50)
- `PasswordResetEmailVerifyRequest` (email 신규 max=50)
- `PasswordResetConfirmRequest` (email 신규 max=50, newPassword min=8 + 신규 max=64)
- `PasswordChangeRequest` (currentPassword 신규 max=64, newPassword min=8 + 신규 max=64)
- `WithdrawRequest` (password 100 → 64)
- `UserUpdateRequest` (verificationNonce 신규 max=36)

### 유지 (이미 적정)

- **name** DTO 20 / DB VARCHAR(20) — V18에서 50→20 축소 시 적정화 완료
- **address** DTO 200 / DB VARCHAR(200) — 도로명 안전 여유
- **addressDetail** DTO 100 / DB VARCHAR(100)
- **phone** `@Pattern(\d{10,11})` — 형식 고정
- **postcode** `@Pattern(\d{5})` — 형식 고정
- **인증코드** `@Pattern(\d{6})` — 형식 고정
- **profileImageUrl** DB VARCHAR(500) — 카카오 CDN URL 수용
- **DB users.email VARCHAR(100)** — DTO 50으로 좁혀도 column은 여유 유지 (스키마 변경 없음)

### 영향 분석

- **응답 포맷 변경 없음** → 프론트 호환성 영향 0
- **DB 스키마 변경 없음** (Flyway 없음)
- **기존 가입자 영향 가능성**: 운영 DB에 이메일 50자 초과 가입자가 있을 경우 로그인 입력 검증에서 차단됨. 운영 적용 전 `SELECT count(*), max(LENGTH(email)) FROM users WHERE LENGTH(email) > 50` 1회 점검 권장 (해당 사용자가 있으면 별도 정책 검토)
