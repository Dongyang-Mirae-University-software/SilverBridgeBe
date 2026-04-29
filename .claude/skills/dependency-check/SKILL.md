---
name: dependency-check
description: 의존성 취약점·노후 라이브러리 점검이 필요할 때 사용. "취약점", "CVE", "의존성 점검", "라이브러리 업데이트", "보안 패치 버전" 같은 요청에서 발동. Gradle 의존성 트리를 분석하고 알려진 CVE·EOL 여부·메이저 업그레이드 영향도를 보고한다. 코드 레벨 보안 점검은 security-scan 영역.
---

## 목적
사용 중인 외부 라이브러리의 알려진 취약점(CVE)·EOL·구버전 사용을 식별하고, 안전한 업그레이드 경로를 제시한다.

## 입력/스코프
- 대상 파일: `build.gradle`, `build.gradle.kts`, `settings.gradle`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`
- 분석 도구: `./gradlew dependencies`, `./gradlew dependencyInsight`, OWASP Dependency-Check (있으면), GitHub Advisory Database, NVD

## 절차
1. **의존성 트리 추출**
   ```
   ./gradlew dependencies --configuration runtimeClasspath > /tmp/deps.txt
   ./gradlew dependencies --configuration compileClasspath >> /tmp/deps.txt
   ```
2. **버전 vs 최신 비교** — Maven Central / GitHub Releases 조회 (WebFetch 사용 가능)
3. **CVE 매칭** — 핵심 라이브러리 (아래 "주시 대상") 위주로 GitHub Advisory · NVD 검색
4. **Spring Boot BOM 의존성** — `spring-boot-dependencies` BOM이 관리하는 버전은 BOM 업그레이드로 한 번에 해결 가능
5. **transitive 의존성** — 직접 declare 안 했지만 취약점 있는 transitive 는 `implementation` 으로 강제 버전 명시
6. **업그레이드 영향도 평가** — Major bump는 breaking change 검토
7. **빌드 검증** — 버전 변경 후 `./gradlew build -x test --no-daemon`
8. **커밋** — `chore: <라이브러리> <old>→<new> 업그레이드` 또는 `fix: <CVE-ID> 패치`

## 검출 기준

### 주시 대상 (이 프로젝트 핵심 의존성)
| 라이브러리 | 현재 알려진 영역 | 점검 포인트 |
|---|---|---|
| Spring Boot 4.0.5 | 프레임워크 전반 | 4.x BOM 패치 버전 / Spring Security 고지 |
| Spring Security | 인증·인가 | OAuth2 / JWT 관련 CVE |
| JJWT 0.12.6 | JWT 서명·검증 | 알고리즘 confusion, 서명 검증 우회 |
| spring-security-oauth2-client | 카카오 OAuth | redirect URI / state 검증 |
| spring-data-jpa, hibernate-core | ORM | HQL injection, SQL injection 관련 |
| postgresql JDBC | DB 드라이버 | TLS, parsing 관련 |
| spring-boot-starter-websocket | STOMP/SockJS | sockjs-client 관련 |
| firebase-admin | FCM | 토큰 검증, Google 라이브러리 transitive |
| solapi (SDK) | SMS | HTTP 클라이언트 transitive |
| jackson-databind | JSON 직렬화 | 역직렬화 RCE 계열 (단골) |
| logback-classic | 로깅 | logback 1.2.x 이하면 CVE-2021-42550 등 |
| lombok | 빌드 시간만 | Java 21 호환성 |
| flyway-core | 마이그레이션 | 메이저 업그레이드 시 baseline 정책 변경 |
| springdoc-openapi | Swagger UI | swagger-ui 임베디드 XSS |

### 자동 신호
- **EOL** : Spring Boot 2.x / Java 8·11 / Junit 4 등은 즉시 마이그레이션 권장
- **메이저 차이 2개 이상**: e.g. 사용 중 1.x, 최신 3.x → 단계적 업그레이드 계획
- **transitive only**: 직접 선언 없는 라이브러리에서 CVE → 강제 버전 또는 BOM 업그레이드
- **알파/베타/RC 사용**: 운영 빌드에 포함되면 안전한 GA 권장

### Gradle Wrapper
- `gradle-wrapper.properties` 의 distribution URL 버전이 EOL 이거나 Java 21 미지원이면 업그레이드

## Non-goals
- **소스 코드 보안 결함** (인증 누락, SQL injection 작성) → `security-scan`
- **앱 성능** → `performance-check`
- **DB 쿼리** → `db-improve`
- **자동 업그레이드 PR 생성** : Dependabot/Renovate는 hooks 또는 GitHub 설정 영역

## 출력 포맷

### 1) 요약 표
| # | 라이브러리 | 현재 | 권장 | CVE/사유 | 심각도 | 영향도 |
|---|---|---|---|---|---|---|

심각도:
- **Critical**: CVSS 9.0+ 또는 RCE / 인증우회 / 노출된 엔드포인트에서 익스플로잇 가능
- **High**: CVSS 7.0~8.9 또는 운영 환경에서 트리거 가능한 결함
- **Medium**: CVSS 4.0~6.9, 제한된 조건에서만 트리거
- **Low**: 정보 노출, 로컬 only, 미사용 경로

영향도:
- **breaking**: 메이저 bump 또는 API 변경 동반
- **safe**: 패치 버전 (x.y.Z 만 변경)
- **transitive**: 직접 의존성 선언 변경 불필요

### 2) 항목별 상세
- **CVE ID / Advisory 링크**
- **취약점 요약** (1~2줄)
- **본 프로젝트 노출 여부** : 해당 클래스/기능을 실제로 사용하는지 코드 검색 결과
- **권장 조치** : 버전 명시 + Gradle 변경 스니펫
- **breaking change 유무** : major bump면 release notes 핵심 변경 요약

### 3) 업그레이드 계획
- **즉시 적용** (safe): 패치 버전만 올림, 빌드만 통과시키면 됨
- **별도 PR** (breaking): 메이저 bump, 코드 수정 동반
- **모니터링** : 현재 패치 없음, advisory 추적 필요

## 작업 마무리
- `./gradlew build -x test --no-daemon` 통과
- (있으면) `./gradlew test` 통과
- 커밋: `chore: <라이브러리> 버전 업그레이드 (CVE-XXXX-YYYY 패치)`
- 운영 영향이 큰 변경(Spring Boot major 등)은 PR description에 release notes 요약 첨부
