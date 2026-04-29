---
name: security-scan
description: 코드 레벨 보안 결함을 점검할 때 사용. "보안 점검", "OWASP", "권한 검사", "인증 누락", "SQL injection", "XSS", "토큰 검증" 같은 요청에서 발동. 인증·인가·입력검증·민감정보 처리 측면에서 결함을 찾는다. 라이브러리 CVE는 dependency-check 영역.
---

## 목적
코드와 설정에서 인증·인가·입력검증·민감정보 누출·세션 관리·암호화 결함을 찾아 OWASP Top 10 기준으로 보고한다.

## 입력/스코프
- 기본: 현재 브랜치 변경분 + 보안 영향이 큰 경로 (`global/security`, `global/jwt`, `domain/auth`, Controller 전체)
- 사용자 지정 시 특정 도메인
- 분석 대상: `*Controller.java`, `*Service.java`, `SecurityConfig`, `JwtAuthenticationFilter`, `*Interceptor`, `application*.yaml`

## 절차
1. **인증 흐름 검토** — `SecurityConfig.java`, JWT 필터, OAuth2 카카오 흐름
2. **엔드포인트 권한 매핑** — 모든 `@*Mapping` 메서드에 `@PreAuthorize` 또는 SecurityConfig matcher 권한 확인
3. **입력 검증** — DTO `@Valid`, `@NotNull`/`@Size`/`@Pattern`, 파일 업로드 MIME/size 제한
4. **데이터 흐름 추적** — 사용자 입력 → DB / 외부 호출 / 로그까지의 경로
5. **설정 파일** — `application*.yaml` 의 시크릿 노출, CORS, actuator endpoints
6. **수정안 적용** — 즉시 패치 가능한 것만 적용, 구조적 결함은 PR로 분리
7. **검증** — `./gradlew build -x test --no-daemon`, 가능하면 보안 테스트 추가
8. **커밋** — `fix: <도메인> <취약점 요약>` 또는 `security: ...`

## 검출 기준 (OWASP Top 10 + 프로젝트 규칙)

### A01 Broken Access Control
- `@PreAuthorize` 누락된 endpoint (특히 ADMIN 도메인 — `agent_docs/security.md`)
- `@PathVariable` userId를 신뢰하고 본인 확인 안 함 → 인증 principal vs path id 비교 누락 (IDOR)
- WebSocket 토픽: 본인 userId가 아닌 다른 사용자 토픽 발행/구독 가능 → `StompSubscriptionAuthorizationInterceptor` 우회 경로 (CLAUDE.md 규칙 8)
- ADMIN 전용 메서드에 `hasRole('ADMIN')` 누락
- `SecurityConfig.permitAll()` 매처에 의도치 않은 endpoint 포함

### A02 Cryptographic Failures
- JWT secret을 코드에 하드코딩 / 짧은 secret (256bit 미만 HMAC)
- JJWT `parserBuilder().setSigningKey(...)` 에서 `none` 알고리즘 허용 여부
- 비밀번호 해싱: `BCryptPasswordEncoder` 사용 여부, strength ≥ 10
- Refresh token Redis 저장 시 평문 / TTL 누락
- HTTPS 강제 (운영) — `server.forward-headers-strategy`, 리다이렉트 설정

### A03 Injection
- JPQL/Native query 에 사용자 입력 문자열 결합 (`@Query("... where x = '" + input + "'")`) → 파라미터 바인딩
- `@Query(nativeQuery = true)` + 동적 ORDER BY/COLUMN → 화이트리스트 검증
- Solapi/Firebase 메시지 본문에 사용자 입력 그대로 포함 → 컨텐츠 인젝션 (헤더 주입 등)
- 로그 출력에 사용자 입력 + 개행 → log injection (CRLF)

### A04 Insecure Design
- 회원가입·로그인·비밀번호 재설정에 Rate Limit 누락 → `rateLimitService.check(endpoint, identifier)` (CLAUDE.md 규칙 5)
- 로그인 잠금 정책 (`agent_docs/security.md`) 미적용
- SMS 인증 코드 재발송 무제한 → 비용 공격 / SMS 폭탄
- 비밀번호 재설정 토큰 단발성 보장 (재사용 방지)

### A05 Security Misconfiguration
- `application.yaml` 의 `spring.profiles.active` 가 운영에 `dev` 노출
- Actuator 전체 endpoint 노출 (`management.endpoints.web.exposure.include=*`) → `health,info` 만
- Swagger UI 운영 노출 여부 (정책에 따라)
- CORS `allowed-origins=*` 또는 `allow-credentials=true` + 와일드카드 조합
- Spring Security `csrf().disable()` 사용 시 정당성 (REST + JWT 면 OK, 폼 인증이면 위험)
- 에러 페이지 stack trace 노출

### A06 Vulnerable and Outdated Components
- → `dependency-check` 영역, 여기서는 다루지 않음

### A07 Identification & Authentication Failures
- JWT 만료시간 비합리적 (액세스 토큰 24h+ 등)
- Refresh token 회전(rotation) 정책 없음 → 탈취 시 영구 사용
- 로그아웃 시 토큰 블랙리스트 / Redis 무효화 누락
- 카카오 OAuth state 파라미터 검증
- 동시 세션 정책

### A08 Software & Data Integrity Failures
- Jackson 역직렬화 시 `@JsonTypeInfo` + 신뢰 안 되는 입력 (RCE 위험)
- 파일 업로드 → MIME sniffing 없이 확장자만 신뢰
- 외부 URL fetch (SSRF) — Solapi, FCM, 카카오 외 임의 URL 허용 금지

### A09 Security Logging & Monitoring Failures
- 인증 실패·권한 실패 로그 누락 → 침해 탐지 불가
- 로그에 비밀번호·토큰·주민번호·전화번호 평문 → `log-review` 영역과 겹치지만 보안 관점에서도 검출

### A10 Server-Side Request Forgery (SSRF)
- 사용자 입력 URL로 RestTemplate / WebClient 호출 → 화이트리스트 검증
- 프로필 이미지 등 외부 URL 다운로드 시 내부 IP 차단 (169.254.169.254 등)

### 프로젝트 추가 규칙
- `CustomException` + `ErrorCode` 사용 여부 (규칙 7) — `RuntimeException` 직접 throw 시 에러 응답 형태가 달라져 침해 탐지 어려움
- Connection 상태 변경 알림 우회 경로 (규칙 4)
- userId 검증: 6자리 문자열 형식 (규칙 9), 임의 길이 입력 거부

## Non-goals
- 라이브러리 CVE → `dependency-check`
- 성능 → `performance-check`
- 로그 품질 (포맷, 레벨) → `log-review`
- 가독성 리팩토링 → `refactor`

## 출력 포맷

### 1) 요약 표
| # | OWASP | 파일:라인 | 결함 | 심각도 | 익스플로잇 가능성 |
|---|---|---|---|---|---|

심각도:
- **Critical**: 인증/인가 우회, RCE, SQL injection, 시크릿 노출
- **High**: IDOR, 민감정보 노출, Rate Limit 부재로 인한 폭증 공격 가능
- **Medium**: 부분적 권한 검사 누락, 약한 암호 정책
- **Low**: 정보성 (에러 메시지에 내부 경로 노출 등)

익스플로잇 가능성: `easy` / `requires-auth` / `requires-admin` / `theoretical`

### 2) 항목별 상세
- **위치**: 파일:라인
- **취약 코드**: 발췌
- **공격 시나리오**: 1~3줄, 실제 어떻게 악용 가능한지
- **수정안**: 코드 + 설정 변경
- **검증 방법**: curl 예시 또는 단위 테스트 가이드

### 3) 적용 계획
- 즉시 패치 (Critical/High): N개
- 사용자 확인 필요 (정책 결정 동반): N개
- 별도 PR (구조 변경): N개

## 작업 마무리
- `./gradlew build -x test --no-daemon` 통과
- 커밋: `fix: <도메인> <취약점 요약>` (Critical/High 는 별도 commit)
- PR 본문에 영향 endpoint 와 검증 방법 명시
- 운영 배포 전 사용자 확인 필수 (특히 인증 흐름 변경)
