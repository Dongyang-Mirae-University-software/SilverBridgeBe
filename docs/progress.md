# SSO Backend 개발 진행 현황

## 체크리스트

- [x] 프로젝트 초기 설정
- [x] application.yml
- [x] Enum 클래스
- [x] BaseTimeEntity
- [x] Entity (User, RefreshToken, AccessLog)
- [x] Repository
- [x] 공통 설정 (ApiResponse, ErrorCode, GlobalExceptionHandler)
- [x] Security 설정
- [x] JWT 설정
- [x] 회원가입 API
- [x] 이메일 인증 API
- [x] 로그인 API
- [x] 로그아웃 API
- [ ] 카카오 OAuth API (보류 — 별도 학습 후 진행)
- [x] 토큰 갱신 API
- [ ] 비밀번호 찾기 API
- [ ] 관리자 API
- [ ] Swagger 설정

---

## [2026-04-03] 프로젝트 초기 설정

### 무엇을 만들었나
- Spring Boot 4.0.5 / Java 21 / Gradle 기반 프로젝트 뼈대 구성
- `docker-compose.dev.sso.yml` (MariaDB 11.8, Redis 7.2)
- `db/schema.sql` (users, refresh_tokens, access_logs 테이블 DDL)
- `.gitignore` (CLAUDE.md, .env.* 제외)
- `build.gradle` 의존성: JPA, Redis, Security, OAuth2, JJWT 0.12.6, Lombok, Swagger

### 왜 이렇게 만들었나
- 도메인형 패키지 구조(`domain/auth`, `domain/user`, `domain/admin`, `global/*`)로 기능 확장 시 응집도 유지
- MariaDB 포트 6406, Redis 포트 6506으로 충돌 방지
- 민감 정보는 `.env.dev.sso` 분리, git 미포함

### 어떻게 동작하나
```bash
docker compose -f docker-compose.dev.sso.yml up -d
docker exec -i dmusso-dev-sso-db mariadb -u sso -psso sso < db/schema.sql
./gradlew bootRun
```

### 주의사항
- 앱 실행 전 반드시 schema.sql 먼저 실행 (ddl-auto: validate)
- `.env.dev.sso`는 git에 올리지 않으므로 로컬에서 직접 생성 필요

---

## [2026-04-03] Enum / BaseTimeEntity / 공통 설정

### 무엇을 만들었나
- `global/enums`: Role(USER, ADMIN), Status(ACTIVE, INACTIVE), Provider(LOCAL, KAKAO)
- `global/entity/BaseTimeEntity`: createdAt, updatedAt 자동 관리
- `global/response/ApiResponse`: 공통 응답 포맷 `{ success, message, data }`
- `global/exception/ErrorCode`: HTTP 상태 코드 + 메시지 enum 관리
- `global/exception/CustomException`: ErrorCode 기반 런타임 예외
- `global/exception/GlobalExceptionHandler`: CustomException, @Valid 실패, 서버 오류 일괄 처리

### 왜 이렇게 만들었나
- 모든 API 응답 형태를 `ApiResponse`로 통일해 프론트와 계약 명확화
- `ErrorCode` enum으로 에러 코드/메시지 한 곳에서 관리 → 변경 시 전파 최소화
- `BaseTimeEntity`로 모든 엔티티의 생성/수정 시간 자동 처리 (`@EnableJpaAuditing`)

### 어떻게 동작하나
- 성공: `ApiResponse.ok(data)` or `ApiResponse.ok("메시지")`
- 실패: `throw new CustomException(ErrorCode.USER_NOT_FOUND)` → GlobalExceptionHandler가 잡아서 `ApiResponse.fail(message)` 반환

### 주의사항
- `@EnableJpaAuditing`은 `SsoBackendApplication`에 선언
- `@ConfigurationPropertiesScan`도 동일 위치에 선언 (JwtProperties 바인딩용)

---

## [2026-04-03] User 엔티티 / UserRepository

### 무엇을 만들었나
- `domain/user/entity/User`: users 테이블 매핑, UUID PK, Enum 필드 적용
- `domain/user/repository/UserRepository`: 이메일 조회, 중복 확인, 소셜 로그인 조회

### 왜 이렇게 만들었나
- UUID PK: 순차 ID 노출 시 사용자 수 추측 가능 → 보안상 UUID 사용
- 도메인 메서드(`verifyEmail`, `updatePassword`, `deactivate`)를 엔티티 내부에 배치해 상태 변경 로직 응집

### 어떻게 동작하나
- `UserRepository.findByEmail(email)` → 로그인/중복 검사
- `UserRepository.findByProviderAndProviderId(provider, providerId)` → 카카오 로그인 사용자 조회

### 주의사항
- `@NoArgsConstructor(access = AccessLevel.PROTECTED)` — JPA 프록시용, 직접 생성 금지
- `@Builder`로만 인스턴스 생성

---

## [2026-04-05] JWT 설정

### 무엇을 만들었나
- `global/jwt/JwtProperties`: application.yaml의 jwt.* 값 바인딩
- `global/jwt/JwtTokenProvider`: Access/Refresh Token 생성, 검증, 클레임 추출, 남은 만료 시간 계산
- `global/jwt/JwtAuthenticationFilter`: 요청마다 Bearer 토큰 검증 후 SecurityContext 등록

### 왜 이렇게 만들었나
- Access Token에 userId, email, role을 담아 **매 요청 DB 조회 없이** 인증 처리 (성능)
- Refresh Token은 userId만 담아 최소 정보 유지
- `getRemainingExpiration()`: 로그아웃 시 Redis blacklist TTL을 토큰 남은 시간으로 정확히 설정하기 위해

### 어떻게 동작하나
```
요청 → JwtAuthenticationFilter → Bearer 토큰 추출
    → JwtTokenProvider.validateToken() → Claims 파싱
    → userId + role로 Authentication 생성
    → SecurityContextHolder 등록
```

### 주의사항
- secret은 UTF-8 바이트 변환 방식 (`Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`) — Base64 아님
- secret 길이 최소 32자 이상 필요 (HS256 기준)
- `JwtAuthenticationFilter`는 `@Component` 아님 → `SecurityConfig`에서 직접 `new`로 생성해 등록

---

## [2026-04-05] Spring Security 설정

### 무엇을 만들었나
- `global/security/CustomUserDetails`: User 엔티티를 Spring Security UserDetails로 래핑
- `global/security/CustomUserDetailsService`: 이메일로 DB 조회 후 UserDetails 반환
- `global/security/SecurityConfig`: FilterChain, 경로 인가, BCryptPasswordEncoder, AuthenticationManager

### 왜 이렇게 만들었나
- CSRF 비활성화: REST API + JWT 방식은 세션 미사용으로 CSRF 불필요
- STATELESS 세션: 서버에 세션 저장 없이 토큰만으로 인증
- Swagger 경로 permitAll: 개발 편의를 위해 인증 없이 접근 허용

### 어떻게 동작하나
- `permitAll` 경로: `/api/auth/**`, `/swagger-ui/**`, `/v3/api-docs/**`
- `hasRole("ADMIN")` 경로: `/api/admin/**`
- 나머지: `authenticated()`

### 주의사항
- `CustomUserDetailsService`는 JWT 필터 방식에서 직접 호출되지 않음 (토큰 클레임으로만 인증)
- 폼 로그인이나 `@AuthenticationPrincipal`로 전체 User 객체가 필요한 경우에만 동작

---

## [2026-04-05] RefreshToken / AccessLog 엔티티

### 무엇을 만들었나
- `domain/auth/entity/RefreshToken`: refresh_tokens 테이블 매핑, BIGINT PK
- `domain/auth/entity/AccessLog`: access_logs 테이블 매핑, 인증 이벤트 기록
- `domain/auth/repository/RefreshTokenRepository`
- `domain/auth/repository/AccessLogRepository`

### 왜 이렇게 만들었나
- RefreshToken DB 저장: 서버 재시작 후에도 유지, 강제 로그아웃(DB 삭제)으로 무효화 가능
- AccessLog: 언제 어디서 로그인/로그아웃이 발생했는지 보안 감사 추적

### 어떻게 동작하나
- `RefreshTokenRepository.deleteByUserId()`: 재로그인/로그아웃 시 기존 토큰 제거
- AccessLog action 값: `LOGIN`, `LOGOUT`, `KAKAO_LOGIN`, `TOKEN_ISSUE`, `PASSWORD_RESET`

### 주의사항
- 두 엔티티 모두 `created_at`만 존재 → `BaseTimeEntity` 미상속, `@CreatedDate` 직접 적용
- `@EntityListeners(AuditingEntityListener.class)` 각 클래스에 직접 선언 필요

---

## [2026-04-05] 회원가입 API

### 무엇을 만들었나
- `POST /api/auth/register`
- `domain/auth/dto/RegisterRequest`: email, password, name, phone
- `domain/auth/service/AuthService#register()`
- `domain/auth/controller/AuthController#register()`

### 왜 이렇게 만들었나
- 이메일 중복 확인을 서비스 레이어에서 처리해 DB unique 제약과 이중으로 방어
- 비밀번호는 BCrypt 암호화 후 저장 (평문 저장 절대 금지)

### 어떻게 동작하나
1. `RegisterRequest` 유효성 검사 (@Email, @NotBlank, @Size min=8)
2. 이메일 중복 확인 → 중복 시 `EMAIL_ALREADY_EXISTS` 예외
3. 비밀번호 BCrypt 암호화
4. UUID PK 생성 후 저장

### 주의사항
- 현재 이메일 인증 없이 바로 가입 완료 (`emailVerified=false`)
- 추후 이메일 인증 API 구현 시 연동 필요

---

## [2026-04-05] 이메일 인증 API

### 무엇을 만들었나
- `POST /api/auth/email/send` — 6자리 인증 코드 생성 후 이메일 발송, Redis에 TTL 5분 저장
- `POST /api/auth/email/verify` — 코드 검증 후 `emailVerified=true` 처리
- `EmailVerifyService`, `EmailVerifyController`
- `EmailSendRequest`, `EmailVerifyRequest` DTO
- `ErrorCode.EMAIL_ALREADY_VERIFIED` (409) 추가
- `application.yaml`에 Spring Mail(SMTP) 설정 추가

### 왜 이렇게 만들었나
- `AuthController`/`AuthService`가 `feature/auth-api` 브랜치에만 존재해 머지 충돌 방지를 위해 별도 클래스로 분리
- `SecureRandom`으로 인증 코드 생성 — `Random`보다 예측 불가능해 보안상 안전
- 재발송 시 Redis 키를 덮어씌워 이전 코드 무효화
- 인증 완료 즉시 Redis 키 삭제 — 코드 재사용 방지

### 어떻게 동작하나
```
POST /send
  → 사용자 조회 (없으면 404)
  → 이미 인증됐으면 409
  → 6자리 코드 생성 → Redis 저장(TTL 5분) → 이메일 발송

POST /verify
  → Redis에서 코드 조회 (없으면 만료 400)
  → 코드 불일치 시 400
  → 일치하면 Redis 삭제 + user.emailVerified = true
```

### 주의사항
- `MAIL_USERNAME`, `MAIL_PASSWORD` 환경변수 필수 — `.env.dev.sso`에 설정 필요
- Gmail 사용 시 앱 비밀번호 발급 필요 (2단계 인증 활성화 후)
- 이메일 발송은 동기 처리 → 느릴 수 있음, 추후 비동기(@Async) 전환 고려

---

## [2026-04-05] 로그인 API

### 무엇을 만들었나
- `POST /api/auth/login`
- `domain/auth/dto/LoginRequest`: email, password
- `domain/auth/dto/LoginResponse`: accessToken, refreshToken, userId, email, name, role
- `domain/auth/service/AuthService#login()`
- `domain/auth/controller/AuthController#login()`

### 왜 이렇게 만들었나
- 단일 디바이스 정책: 재로그인 시 기존 Refresh Token 삭제 후 신규 발급 (동시 로그인 방지)
- IP, UserAgent를 AccessLog에 저장해 비정상 접근 추적 가능

### 어떻게 동작하나
1. 이메일로 사용자 조회 → 없으면 `USER_NOT_FOUND`
2. INACTIVE 계정 차단 → `INACTIVE_USER`
3. BCrypt 비밀번호 검증 → 불일치 시 `INVALID_PASSWORD`
4. Access Token(30분) + Refresh Token(7일) 발급
5. Refresh Token DB 저장, 마지막 로그인 시간 갱신, LOGIN 로그 기록

### 주의사항
- 로그인 실패 시에도 AccessLog를 남길지 추후 결정 필요
- Refresh Token은 DB에 저장 (Redis 아님)

---

## [2026-04-05] JWT Blacklist 차단 로직

### 무엇을 만들었나
- `JwtAuthenticationFilter`에 Redis blacklist 확인 로직 추가
- `SecurityConfig`에 `StringRedisTemplate` 주입

### 왜 이렇게 만들었나
- 로그아웃 후에도 Access Token은 만료 전까지 유효 → 탈취 시 재사용 가능한 문제 차단
- Redis에 `blacklist:{token}` 키가 있으면 이미 로그아웃된 토큰이므로 즉시 거부
- TTL을 토큰 남은 만료 시간으로 설정해 Redis 메모리 자동 정리

### 어떻게 동작하나
```
요청 → Bearer 토큰 추출
    → Redis blacklist 확인 → 있으면 401 즉시 반환 (필터 체인 중단)
    → JwtTokenProvider.validateToken() → 유효하면 SecurityContext 등록
```

### 주의사항
- blacklist 확인이 validateToken()보다 먼저 실행되어야 함 (순서 중요)
- 필터에서 직접 response 반환 시 `filterChain.doFilter()` 호출하지 않아야 함
