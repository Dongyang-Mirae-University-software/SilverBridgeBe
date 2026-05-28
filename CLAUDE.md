# SilverBridgeBe — Claude Code 워크스페이스

> Spring Boot 4 / Java 21 기반 백엔드 프로젝트의 AI 협업 가이드

---

## 1. 프로젝트 개요

| 항목 | 값 |
|------|-----|
| 그룹 | `kr.silverbridge` |
| 루트 패키지 | `kr.silverbridge.main` |
| 빌드 도구 | **Gradle** (`./gradlew`) |
| Java 버전 | **21** (toolchain) |
| 프레임워크 | Spring Boot **4.0.5** |
| DB | PostgreSQL + Flyway 마이그레이션 |
| 캐시/세션 | Redis |
| 보안 | Spring Security, OAuth2 Client, JWT (jjwt 0.12.6) |
| 외부 연동 | Firebase Admin, Solapi(SMS), SMTP Mail |
| 실시간 | WebSocket |
| API 문서 | springdoc-openapi 2.8.6 (Swagger UI) |
| 의존성 보안 | OWASP DependencyCheck 10.0.4 |
| 메인 브랜치 | `dev` (CD 자동 배포) |

### 도메인 구조

```
kr.silverbridge.main
├── domain
│   ├── admin           ── 관리자 공지사항 (감사로그 쓰기 포함)
│   ├── announcement    ── 공지/알림
│   ├── auth            ── 인증/인가
│   ├── connection      ── 연결 관리
│   ├── notification    ── 푸시/알림 발송
│   └── user            ── 사용자
(2026-05-19 미검증 API 정리로 ai·anomaly·call·game 도메인 제거)
└── global
    ├── aop / client / config / entity / enums
    ├── exception / jwt / response
    ├── security / util / websocket
```

---

## 2. 협업 원칙 (Human-in-the-loop)

claude-code-java의 [DESIGN_PRINCIPLES](../claude-code-java/docs/DESIGN_PRINCIPLES.md) 및 [SAFE_WORKFLOWS](../claude-code-java/docs/SAFE_WORKFLOWS.md)를 따른다.

1. **개발자가 결정, AI는 제안만** — Claude는 변경을 제안하고, 개발자가 검토·승인한다.
2. **자동 git 커밋·푸시 금지** — 명시적 승인 없이 git에 쓰지 않는다.
3. **변경 범위 투명화** — 어떤 파일이 왜 바뀌는지 항상 설명한다.
4. **점진적 채택** — 읽기 전용 분석 → 제안 → 리뷰 후 적용 순서.

### 안전 워크플로우 (권장)

```
분석(Analyze) → 설명(Explain) → 제안(Propose) → 리뷰(Review) → 적용(Apply) → 커밋(Commit)
```

### 🚩 변경 시 경고 신호 (Red Flags)

리뷰할 diff에서 다음이 보이면 **일시 정지**하고 근거를 묻는다.

- 의도와 무관한 포맷 변경
- 이유 없는 import 추가/제거
- 대규모 파일 이름·위치 변경
- 동작이 미묘하게 바뀌는 로직 리팩터
- 스킬 범위를 벗어난 변경 제안

자세한 내용: [RED_FLAGS](../claude-code-java/docs/RED_FLAGS.md)

---

## 3. 브랜치 & 커밋 전략

> ⚠️ **`dev` / `prod` 직접 커밋·푸시 금지.** feat 브랜치 → MR → dev 흐름만 허용.

**브랜치**: `prod`(배포 기준) / `dev`(개발 기준, MR로만 반영) / feat(dev에서 분기, 머지 후 삭제)

**명명**: `type/short-description` (소문자·하이픈, 이슈 번호: `type/123-desc`)

**type**: `feature` `fix` `hotfix` `refactor` `design` `docs` `test` `chore` `release` `infra`

**규칙**:
- push 전 `git pull origin dev` 로 feat 최신화
- MR base = `dev`, 머지 후 source 삭제
- **PR 머지 직후 로컬·원격 feat 브랜치를 모두 삭제** (`git branch -d <branch>` + `git push origin --delete <branch>`), 머지 커밋이 dev에 반영되었는지 확인 후 진행

### 커밋 메시지 규칙

형식: `type: 내용` (첫 글자 소문자, 한 커밋 = 한 작업)

**type**: `feat` `fix` `refactor` `design` `comment` `style` `docs` `test` `chore` `init` `rename` `remove`

본문은 **`어떻게`보다 `무엇을`·`왜`**, 여러 줄은 `-`로 구분.

```
feat: 사용자 로그인 기능 추가
- JWT 기반 로그인 처리 구현
- 로그인 실패 시 에러 메시지 반환
```

> ⚠️ 브랜치는 `feature/...`, 커밋은 `feat: ...` — 헷갈리지 말 것.

### 작업 순서 (Standard Workflow)

> 모든 코드 변경은 **항상** 아래 순서를 따른다. (서로 무관한 변경은 별도 브랜치·PR로 분리)

1. `git pull origin dev` — dev 최신화
2. `git checkout -b type/short-description` — dev에서 작업 브랜치 분기
3. 작업 및 커밋 (conventional commit, 한 커밋 = 한 작업)
4. **push 전 검증**: `./gradlew build -x test --no-daemon`
5. `git push origin type/short-description`
6. PR 생성 → `dev`로 머지 → 브랜치 자동 삭제 → 서버 자동 배포(CD)

### 자동 배포 (CD)

- 트리거: `dev` 브랜치 push 또는 수동 `workflow_dispatch`
- 러너: self-hosted (`[self-hosted, dev]` 라벨)
- 동작: `git pull` → `docker compose -f docker-compose.dev.yml up -d --build api` → 이미지 정리

`dev`로 머지되는 모든 변경은 즉시 dev 환경에 배포된다는 점을 항상 의식할 것.

---

## 4. 사용 가능한 스킬 (19개)

`.claude/skills/`에 설치되어 있으며, 자연어로 호출하기 전에 먼저 SKILL.md를 로드한다.

```
> view .claude/skills/<skill-name>/SKILL.md
> "<자연어 요청>"
```

### 4.1 레벨별 분류

| 레벨 | 스킬 | 적용 단위 |
|------|------|-----------|
| **Macro** | `architecture-review` | 패키지·모듈·레이어 구조 |
| **Meso** | `design-patterns`, `java-code-review`, `solid-principles` | 클래스·인터페이스 협업 |
| **Micro** | `clean-code` | 함수·네이밍·표현식 |
| **Framework** | `spring-boot-patterns`, `jpa-patterns` | Spring/JPA 특화 |
| **Cross-cutting** | `security-audit`, `test-quality`, `logging-patterns`, `concurrency-review`, `performance-smell-detection`, `api-contract-review` | 보안·테스트·로깅·동시성·성능·API |
| **Workflow** | `git-commit`, `issue-triage`, `changelog-generator`, `java-migration`, `maven-dependency-audit` | 커밋·이슈·릴리스 작업 |

### 4.2 타입별 분류 (Audit vs Template)

| 타입 | 스킬 | 용도 |
|------|------|------|
| **Audit** (기존 코드 점검) | `java-code-review`, `architecture-review`, `security-audit`, `api-contract-review`, `performance-smell-detection`, `concurrency-review`, `maven-dependency-audit` | 진단·리뷰 |
| **Template** (새 코드 작성) | `spring-boot-patterns`, `jpa-patterns`, `design-patterns`, `solid-principles`, `clean-code`, `logging-patterns`, `test-quality` | 패턴 적용 |
| **Tooling** | `git-commit`, `issue-triage`, `changelog-generator`, `java-migration` | 운영 자동화 |

### 4.3 자주 쓰는 스킬

| 스킬 | 사용 예 |
|------|---------|
| `git-commit` | "스테이징된 변경에 대한 conventional commit 메시지 생성" |
| `test-quality` | "AuthService에 JUnit 5 + AssertJ 단위 테스트 추가" |
| `security-audit` | "auth 도메인의 OWASP 취약점 점검" |
| `jpa-patterns` | "User 엔티티의 N+1 쿼리 검토" |
| `spring-boot-patterns` | "Controller-Service-Repository 레이어 검토" |
| `architecture-review` | "domain/global 패키지 경계 점검" |
| `issue-triage` | "최근 10개 이슈 분류 및 라벨 제안" |

> 🔁 한 세션에서는 같은 스킬을 **한 번만** 로드한다. 컨텍스트에 유지되므로 재로드는 토큰 낭비.

---

## 5. 빌드 & 테스트 명령어

> ❗ Maven이 아닌 **Gradle** 프로젝트.

```bash
# 전체 빌드
./gradlew clean build

# 테스트만
./gradlew test

# 단일 테스트 클래스
./gradlew test --tests "kr.silverbridge.main.domain.auth.AuthServiceTest"

# 애플리케이션 실행
./gradlew bootRun

# 의존성 트리
./gradlew dependencies

# OWASP 의존성 취약점 스캔 (HTML/JSON 리포트)
./gradlew dependencyCheckAnalyze --info
# 결과: build/reports/dependency-check-report.html
# CVSS 7.0 이상 발견 시 빌드 실패 (build.gradle 설정)
# NVD_API_KEY 환경변수 설정 시 동기화 속도 향상
```

### 테스트 전략

- **프레임워크**: JUnit 5 (`useJUnitPlatform`), Spring Boot Test, Spring Security Test
- **목표 커버리지**: 핵심 비즈니스 로직 80%+
- **중점**: 도메인 서비스·정책 로직 (보일러플레이트는 제외)
- **AI 작성 테스트 검토 시**: `test-quality` 스킬의 체크리스트 사용

---

## 6. 데이터베이스 & 마이그레이션

- 마이그레이션 파일: `src/main/resources/db/migration/V*.sql` (Flyway 규칙)
- 스키마는 Flyway가 단일 관리 — 빈 DB에 V1부터 순차 적용. 별도 schema.sql 시딩 없음 (drift·baseline 충돌 방지)
- Spring Boot 4는 Flyway 자동설정이 분리됨 — `spring-boot-starter-flyway` 의존성이 반드시 있어야 마이그레이션이 실행됨 (없으면 조용히 건너뜀)
- 로컬 개발: `docker compose -f docker-compose.dev.yml up -d`
- 환경 설정: `.env.dev`
- **운영 DB 마이그레이션은 항상 PR 단위로 검토** — 비가역적 DDL은 별도 표시

---

## 7. 일상 워크플로우 예시

### 새 기능 개발

```bash
# 1. 작업 브랜치 생성 (절대 dev에서 직접 작업 X)
git switch -c feature/notification-batch

# 2. (분석) 관련 도메인 구조 파악
> view .claude/skills/architecture-review/SKILL.md
> "notification 도메인의 현재 구조 검토"

# 3. (제안) Spring 패턴 따라 구현
> view .claude/skills/spring-boot-patterns/SKILL.md
> "BatchService에 @Scheduled 적용 패턴 제안"

# 4. (리뷰) 테스트 추가
> view .claude/skills/test-quality/SKILL.md
> "BatchService 단위 테스트 추가"

# 5. 빌드·테스트 통과 확인
./gradlew test

# 6. (커밋) 메시지 생성
> view .claude/skills/git-commit/SKILL.md
> "스테이징된 변경 커밋"

# 7. 푸시 → PR → dev 머지 → CD 자동 배포
git push -u origin feature/notification-batch
gh pr create --base dev
```

### 보안·의존성 점검 (주기적)

```bash
> view .claude/skills/security-audit/SKILL.md
> "auth/jwt/security 패키지 OWASP 점검"

./gradlew dependencyCheckAnalyze
# 리포트 검토 → 필요 시 dependency-check-suppressions.xml 갱신
```

### 코드 리뷰 (PR 받았을 때)

```bash
> view .claude/skills/java-code-review/SKILL.md
> "PR #N 변경 파일 리뷰 — 테스트 커버리지·예외 처리 중심"
```

---

## 8. 피해야 할 패턴

1. **`dev` 직접 커밋·푸시** — 반드시 PR 경유
2. **AI 출력 그대로 적용** — 항상 diff 검토 후 적용
3. **스킬 반복 로드** — 세션당 한 번
4. **Maven 명령 사용** — 이 프로젝트는 Gradle (`./gradlew ...`)
5. **`global` 패키지에 도메인 로직 추가** — 도메인 코드는 `domain/<bounded-context>/`
6. **테스트 없는 비즈니스 로직 머지** — 핵심 로직은 JUnit 5 + AssertJ로 검증

---

## 9. 도메인 보안 정책 메모

> 코드만으로는 드러나지 않는 보안 정책 결정의 의도·이력을 기록한다.

### 비밀번호 재설정 — 가입 여부 명시 응답 (2026-05-23 갱신)

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

### 카카오 OAuth — Client Secret 적용 (2026-05-25)

- **의도**: 인가코드 탈취 시 토큰 발급을 차단(REST API Key 단독 대비 보안 강화).
- **변경**: 백엔드 토큰 교환(`POST kauth.kakao.com/oauth/token`) 요청에 `client_secret` 추가.
- **환경변수**: `KAKAO_CLIENT_SECRET` — `.env.dev`로만 주입(코드/Git **평문 비노출**). `application.yaml`은 `${KAKAO_CLIENT_SECRET:}`로 매핑.
- **시작 시 검증**: 존재 여부 → `RequiredPropertiesValidator`(11개 키), 길이(≥32)·placeholder/약한 값 → `SecurityConfigValidator`. 미설정·약한 값이면 시작 중단(fail-fast).
- **운영 적용 전제**: 카카오 콘솔 [보안 > Client Secret] 코드 발급 + **"사용 함" 활성화** 필요. 활성화 없이 secret만 보내면 무시되고, 활성화 후 secret 누락 시 토큰 발급 실패.
- 상세: `docs/(2026-05-25) feature-kakao-client-secret.md`.

### 회원 탈퇴 — 영구 삭제(hard delete) 전환 (2026-05-26)

- **의도**: 기존 비활성화(soft delete)는 user 행이 남아 탈퇴 후 같은 이메일/전화번호로 **재가입이 막히던** 문제가 있었음(`existsByPhone`/`existsByEmail`가 INACTIVE 행도 포함). 탈퇴 시 계정·관련 데이터를 **영구 삭제**하여 재가입을 허용.
- **변경**: `withdraw()`(본인확인+`deactivate()`+`UserWithdrawnEvent`) 커밋 **후**, 컨트롤러가 `purgeWithdrawnUser()`로 user 행을 **hard delete** (이전엔 `deactivate()`로 INACTIVE 전환만).
  - **단계 분리 이유**: 정리 로직(연결 해제+상대 알림·FCM/refresh 토큰 정리·WITHDRAW 접속로그)이 `UserWithdrawnEvent`의 **AFTER_COMMIT 리스너**로 동작하며 "user 행이 살아있음"을 전제로 함 → 정리 완료 후 별도 트랜잭션에서 삭제.
- **연관 데이터**: users 참조 FK가 이미 `CASCADE`/`SET NULL`이라 **DB 마이그레이션 불필요**.
  - CASCADE 삭제: `connections`·`fcm_tokens`·`refresh_tokens`(잔존 시 `hospital_reservations`).
  - `SET NULL`: `access_logs`·`announcements`·`announcement_drafts` — **접속로그는 보안 감사용으로 익명 보존**(완전 삭제 아님). 업로드 프로필 이미지 파일은 커밋 후 제거(카카오 CDN 등 외부 URL이면 파일서버가 무시).
- **본인 확인 유지(H-6)**: 일반=비밀번호, 카카오=confirmation "탈퇴" 일치 확인. access token 단독 탈취로 인한 임의 삭제를 차단(soft→hard 전환에도 동일 적용).
- **비가역**: 복구 불가 — 기존 soft delete의 복구·전수 감사 이점은 포기(접속로그 익명 기록만 잔존).
- 상세: PR #181.

### 연결 거절 — 보호자 실시간 알림 추가 (2026-05-28)

- **의도**: 피보호자가 연결 요청을 거절해도 보호자에게 이벤트가 안 가 보호자 웹이 새로고침 전까지 "요청중"에 멈춰 있던 문제. 수락(`ConnectionAcceptedEvent`)·해제(`ConnectionDisconnectedEvent`)와 **비대칭**이던 거절을 동일 패턴으로 정렬.
- **변경**: `refuseConnectionAsWard()`가 `refuse()` 커밋 후 `ConnectionRefusedEvent(connectionId, guardianId)` 발행 → `ConnectionNotificationListener.handleRefused()`(AFTER_COMMIT, `@Async`)가 보호자에게 WebSocket(`connection-refused`) + FCM(`"연결 요청이 거절되었습니다."`) 발송.
- **FCM 문구**: 시니어/4050 타겟 직관성 우선 — "거절되었습니다"로 명확히. 모호한 "종료/해제" 표현은 회피.
- **알림 비대칭 정책(의도된 것)**: 같은 PENDING 종료라도 알림 대상이 다름 — ① **피보호자 거절 → 보호자 알림O**(본인의 명시적 액션), ② **보호자 요청 취소(`cancel`) → 무알림**, ③ **회원 탈퇴 시 PENDING → CANCELLED 무알림**(`tearDownConnectionsOnWithdrawal`). 향후 "일관성" 명목으로 ②③에 알림을 추가하지 말 것 — 거절만 상대에게 통지가 필요한 명시적 거부 행위.
- **인가**: `connection-refused` 토픽은 `StompSubscriptionAuthorizationInterceptor`의 범용 `{userId}==세션` 검증으로 자동 보호(이벤트명 화이트리스트 없음) — 별도 등록 불필요.
- **DB 영향 없음**: 상태 전이(`refuse()`)는 기존과 동일, 이벤트 발행만 추가. 마이그레이션 불필요.
- 상세: `docs/(2026-05-28) feature-connection-refused-notification.md`.

---

## 10. 리소스

### 내부
- `.claude/skills/README.md` — 스킬 목록 인덱스
- `프로젝트_설명.txt` — 도메인·요구사항 설명
- `docs/progress.md` — 점검·리뷰 진행 기록 (누적)

### 외부
- [claude-code-java](https://github.com/decebals/claude-code-java) — 스킬 저장소 원본
- [Spring Boot Reference](https://docs.spring.io/spring-boot/index.html)
- [Flyway Docs](https://documentation.red-gate.com/fd)
- [Conventional Commits](https://www.conventionalcommits.org/)
- [OWASP Dependency-Check](https://jeremylong.github.io/DependencyCheck/)

---

**최종 업데이트**: 2026-05-28 (연결 거절 시 보호자 실시간 알림 추가 — §9 도메인 보안 정책 메모 참고)
**Spring Boot**: 4.0.5 / **Java**: 21 / **빌드**: Gradle
