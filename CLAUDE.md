# SilverBridgeBe — Claude Code 워크스페이스

> Spring Boot 4 / Java 21 백엔드. AI 협업 가이드 — 이 파일은 매 세션 로드되므로 **간결**하게 유지한다.

---

## 1. 프로젝트 핵심

- **빌드**: Gradle (`./gradlew`) — ❗Maven 아님 / **Java 21**(toolchain) / **Spring Boot 4.0.5**
- **인프라**: PostgreSQL + Flyway / Redis(캐시·세션) / WebSocket(실시간)
- **보안·연동**: Spring Security, OAuth2(카카오), JWT(jjwt) / Firebase(FCM), Solapi(SMS), SMTP / springdoc-openapi(Swagger) / OWASP DependencyCheck
- **메인 브랜치**: `dev` — push 시 CD 자동 배포 (§3 참조)
- **도메인**: `kr.silverbridge.main.domain/<bounded-context>` = `admin`·`announcement`·`auth`·`connection`·`notification`·`sos`·`user`. 공통 코드는 `global/`(aop·config·jwt·security·websocket 등).
  - ⚠️ **도메인 로직을 `global`에 넣지 말 것** — 도메인 코드는 `domain/<context>/`.
- **알림 채널 추상화**(notification, 2026-05-31): 이벤트 → `NotificationDispatcher` → 사용자 설정 활성 채널 발송. 채널=`NotificationChannel` 전략(FCM·SMS 구현, KAKAO_ALIMTALK·EMAIL은 enum만 — 2·3단계). **새 채널 = 구현체 빈 추가**만(디스패처 자동 수집). WebSocket은 추상화 밖(항상 발송). **SMS 인증번호는 디스패처 미경유=필수**(설정 무시). 기본값 FCM ON(설정 행 없으면 적용·백필 불요). 설정 API `/api/user/me/notification-settings`(GET·PUT).

---

## 2. 협업 원칙 (Human-in-the-loop)

1. **개발자가 결정, AI는 제안만** — 변경은 제안 후 검토·승인받아 적용.
2. **자동 git 커밋·푸시 금지** — 명시적 승인 없이 git에 쓰지 않는다. **IMPORTANT.**
3. **변경 투명화** — 어떤 파일이 왜 바뀌는지 항상 설명.
4. **순서**: 분석 → 설명 → 제안 → 리뷰 → 적용 → 커밋.

🚩 **Red Flags** (diff에서 보이면 멈추고 근거 확인): 의도 무관 포맷 변경 / 이유 없는 import 추가·제거 / 대규모 파일 이름·위치 변경 / 동작이 미묘하게 바뀌는 리팩터 / 요청 범위를 벗어난 변경.

---

## 3. 브랜치 & 커밋 전략

> ⚠️ **`dev` / `prod` 직접 커밋·푸시 금지.** feat 브랜치 → PR → `dev` 흐름만 허용.

- **브랜치 명명**: `type/short-description` (소문자·하이픈, 이슈 번호: `type/123-desc`)
  - type: `feature` `fix` `hotfix` `refactor` `design` `docs` `test` `chore` `release` `infra`
  - ⚠️ 브랜치는 `feature/...`, 커밋은 `feat: ...` — 헷갈리지 말 것.
- **커밋 메시지**: `type: 내용` (첫 글자 소문자, 한 커밋 = 한 작업, 본문은 `어떻게`보다 `무엇을`·`왜`)
  - type: `feat` `fix` `refactor` `design` `comment` `style` `docs` `test` `chore` `init` `rename` `remove`

### 표준 작업 순서

1. `git pull origin dev`
2. `git checkout -b type/short-description` (dev에서 분기)
3. 작업 + 커밋 (conventional commit)
4. **push 전 검증**: `./gradlew build -x test --no-daemon`
5. `git push origin type/short-description`
6. `gh pr create --base dev` → 머지 → **로컬·원격 feat 브랜치 삭제**(머지가 dev에 반영됐는지 확인 후)

### 자동 배포 (CD)

- 트리거: `dev` push(또는 수동 `workflow_dispatch`). 러너: self-hosted `[self-hosted, dev]`.
- 동작: `git pull` → `docker compose -f docker-compose.dev.yml up -d --build api` → 이미지 정리.
- **`dev` 머지 = 즉시 dev 환경 배포** 임을 항상 의식할 것. (문서만 변경 시 `cd.yml` paths-ignore로 배포 스킵)

---

## 4. 스킬

`.claude/skills/`에 19개 설치. 트리거가 명확하면 자동 로드, 호출 전 해당 `SKILL.md`를 읽는다. 전체 목록·분류: **`.claude/skills/README.md`**.

- 자주 쓰는: `git-commit` · `test-quality` · `security-audit` · `jpa-patterns` · `spring-boot-patterns` · `architecture-review` · `issue-triage`
- 🔁 **세션당 한 번만 로드** (컨텍스트에 유지되므로 재로드는 토큰 낭비).

---

## 5. 빌드 & 테스트

> ❗ **Gradle** 프로젝트 (Maven 아님).

```bash
./gradlew clean build                    # 전체 빌드
./gradlew test                           # 테스트만
./gradlew test --tests "kr.silverbridge.main.domain.auth.AuthServiceTest"   # 단일 클래스
./gradlew bootRun                        # 실행
./gradlew dependencyCheckAnalyze --info  # OWASP 스캔 → build/reports/dependency-check-report.html
```

- OWASP: CVSS 7.0+ 발견 시 빌드 실패. `NVD_API_KEY` 설정 시 동기화 빠름. suppress는 `dependency-check-suppressions.xml`.
- **테스트**: JUnit 5 + AssertJ + Spring Security Test. 핵심 비즈니스·정책 로직 80%+ (보일러플레이트 제외). AI 작성 테스트 검토는 `test-quality` 스킬.

---

## 6. DB & 마이그레이션

- 마이그레이션: `src/main/resources/db/migration/V*.sql` (Flyway). 스키마는 Flyway 단일 관리 — 빈 DB에 V1부터 순차 적용, 별도 schema.sql 시딩 없음(drift·baseline 충돌 방지).
- ⚠️ **Spring Boot 4는 Flyway 자동설정이 분리됨** — `spring-boot-starter-flyway` 의존성이 없으면 마이그레이션을 조용히 건너뜀.
- 로컬: `docker compose -f docker-compose.dev.yml up -d` (env: `.env.dev`). env 변경은 `restart`가 아닌 `up -d`로 재생성해야 반영됨.
- **운영 DB 마이그레이션은 PR 단위로 검토**, 비가역 DDL은 별도 표시. 기존 `V*.sql` 수정 금지.

---

## 7. 피해야 할 패턴

1. **`dev` 직접 커밋·푸시** — 반드시 PR 경유
2. **AI 출력 그대로 적용** — 항상 diff 검토 후 적용
3. **스킬 반복 로드** — 세션당 한 번
4. **Maven 명령 사용** — 이 프로젝트는 Gradle (`./gradlew ...`)
5. **`global`에 도메인 로직 추가** — 도메인 코드는 `domain/<context>/`
6. **테스트 없는 비즈니스 로직 머지** — 핵심 로직은 JUnit 5 + AssertJ로 검증

---

## 8. 도메인 보안 정책

코드만으로 드러나지 않는 보안 정책의 의도·이력은 **`.claude/rules/domain-security-policy.md`** 에 기록한다(매 세션 자동 로드). 새 보안 결정은 거기에 추가한다.

핵심 불변 규칙 (배경·상세는 위 파일):

- **회원 탈퇴 = hard delete** (재가입 허용). 본인확인 유지 — 일반=비밀번호, 카카오=confirmation "탈퇴" 일치. 정리 로직은 `UserWithdrawnEvent` AFTER_COMMIT.
- **연결 알림 비대칭(의도)**: 거절 → 보호자 알림O / 요청 취소·탈퇴 PENDING 종료 → 무알림. "일관성" 명목으로 ②③에 알림 추가 금지.
- **비밀번호 재설정**: 미가입 404·카카오 400 명시(시니어 UX 우선) + IP/이메일 rate limit.
- **카카오 OAuth**: `client_secret`는 `.env.dev`로만 주입(Git 평문 금지), 시작 시 fail-fast 검증.
- **접속로그(access_logs)**: 가입 트랜잭션 내부에서 `accessLogService.log()`(REQUIRES_NEW) 직접 호출 금지(미커밋 user FK 위반) — AFTER_COMMIT 이벤트로.
- **클라이언트 IP**: `getRemoteAddr()` 직접 사용 금지 — `ClientIpResolver`(nginx `X-Real-IP`) 사용.

---

## 9. 리소스

- **내부**: `.claude/skills/README.md`(스킬 인덱스) · `.claude/rules/`(도메인 보안 정책) · `프로젝트_설명.txt`(도메인·요구사항) · `docs/progress.md`(진행 기록 누적)
- **외부**: [Spring Boot](https://docs.spring.io/spring-boot/index.html) · [Flyway](https://documentation.red-gate.com/fd) · [Conventional Commits](https://www.conventionalcommits.org/) · [OWASP Dependency-Check](https://jeremylong.github.io/DependencyCheck/)

---

**최종 업데이트**: 2026-05-30 (공식 Claude Code 가이드 기준 리팩터링 — 347→~140줄, 도메인 보안 정책을 `.claude/rules/`로 분리) · **Spring Boot** 4.0.5 / **Java** 21 / **빌드** Gradle
