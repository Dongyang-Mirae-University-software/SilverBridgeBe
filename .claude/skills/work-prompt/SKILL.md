---
name: work-prompt
description: 기능 구현·기존 기능 점검 요청을 받았을 때, 실행 전에 단계(PHASE) 구조의 작업 프롬프트를 설계해 사용자에게 제시하고 "시작해" 승인을 받는다. 생성(구현) 템플릿과 점검(audit) 템플릿 2종. 사용자가 "~ 구현하자/진행하자", "~ 점검해줘", "설계안대로 만들어줘"라고 할 때 사용.
---

# Work Prompt (작업 프롬프트 설계)

구현·점검 요청을 **바로 실행하지 않는다.** 먼저 아래 템플릿으로 **작업 프롬프트를 설계해 사용자에게 제시**하고, 사용자가 **"시작해"**라고 하면 그 프롬프트대로 실행한다.

## 왜

- 설계 문서·요청 프롬프트는 **작성 시점 스냅샷**이라 리포보다 뒤처진다. 검증 없이 착수하면 이미 머지된 코드를 중복 생성하거나 확정된 결정을 되돌린다(실제 사례: 이상감지 설계안 §8의 "1차 PR 작업 목록" 대부분이 이미 머지된 상태였고, 트리거 모드 이름도 문서와 구현이 달랐다).
- 프롬프트를 먼저 보여주면 **범위·산출물·사용할 스킬**을 사용자가 착수 전에 교정할 수 있다.

## 절차

1. 요청 성격 판단 → **생성 템플릿** 또는 **점검 템플릿** 선택
2. 템플릿을 이번 작업에 맞게 채워 **프롬프트 초안을 사용자에게 출력** (코드 작성·수정 금지)
   - 이때 **프로젝트 사실을 단정하지 않는다**("X는 없음" 같은 문장 금지) — 사실 확인은 PHASE 0의 몫
3. 사용자 **"시작해"** → PHASE -1부터 순서대로 실행
4. PHASE 1(계획) 승인 후에만 코드 작성

## 스킬 배치 규칙

프롬프트에 **어느 PHASE에서 어떤 스킬을 쓸지 명시**한다. 아래는 기본값이며 **고정이 아니다** — 작업 성격에 맞는 스킬을 `.claude/skills/README.md`에서 확인해 넣고 뺀다(예: 동시성 코드면 `concurrency-review`, 패키지 경계 변경이면 `architecture-review`, 의존성 추가면 `maven-dependency-audit`).

| 작업 | 기본 스킬 배치 |
|---|---|
| 생성(구현) | 구현 = `spring-boot-patterns`(+ DB/엔티티 손대면 `jpa-patterns`) · 테스트 = `test-quality` · 커밋 초안 = `git-commit` |
| 점검(audit) | 보안·인가 = `security-audit` · 구조/계약 = `spring-boot-patterns` + `api-contract-review` + `jpa-patterns` · 테스트 = `test-quality` |

스킬은 **세션당 한 번만 로드**한다(CLAUDE.md §4).

---

## 템플릿 A — 생성(구현)

```
[기능/작업명] 구현.
근거 문서: docs/(...)  ← 있으면

PHASE -1. 사전 환경 확인
  - 오늘 날짜 / git 상태·최근 커밋 / 빌드 통과 / 마이그레이션 최신 버전

PHASE 0. 전제 검증  ★ 문서를 사실로 믿지 않는다
  - 문서·요청이 "있다/없다"고 가정한 것을 코드에서 직접 확인
    (클래스·메서드·enum 값 실재 여부, 재사용 대상 패턴, 설정 키, 테이블)
  - 보고: 전제 vs 실제 차이(drift) 표

PHASE 1. 구현 계획 + 승인
  - drift를 반영한 작업 범위 / 변경·신규 파일 목록
  - 기존 동작 보존 확인 (어떤 기존 경로가 안 바뀌는지 명시)
  - 결정이 필요한 항목 → 질문
  → 승인 대기

PHASE 2. 구현  [스킬: spring-boot-patterns, jpa-patterns]
  - 승인 범위만. 도메인 로직은 domain/<context>/ (global 금지)
  - 알림/이벤트는 AFTER_COMMIT + @Async
  - 기존 마이그레이션 수정 금지, 신규만

PHASE 3. 테스트·검증  [스킬: test-quality]
  - JUnit 5 + AssertJ, 핵심 비즈니스·정책 로직 위주
  - ./gradlew build 통과 확인 (결과를 그대로 보고)

규칙
  - 승인 전 코드 작성 금지 / 요청 범위 밖 변경 금지
  - 자동 git commit·push 금지 (커밋 메시지는 초안만)
  - 시크릿 평문 커밋 금지 (.env.dev 주입)

산출물
  ① docs/(YYYY-MM-DD) feature-<이름>.md — 범위·정책·변경 파일·검증 가이드·테스트 결과
  ② docs/progress.md 갱신
  ③ CLAUDE.md·프로젝트_설명.txt 영향 있으면 갱신
  ④ 컨벤셔널 커밋 메시지 초안  [스킬: git-commit]
```

## 템플릿 B — 점검(audit)

```
[기능/도메인] 점검.

PHASE -1. 사전 환경 확인
  - 오늘 날짜 / 대상 구현 커밋 식별 / 빌드 통과 / 관련 마이그레이션 버전

PHASE 0. 점검 대상 식별
  - 대상 도메인 파일 트리 + 엔드포인트별 역할 제한 표

PHASE A. 보안·인가 (최우선) ★  [스킬: security-audit]
  - IDOR(본인 자원만 접근) / 역할 인가(@PreAuthorize 누락) / 입력 검증 / PII·로그 노출

PHASE B. 기능 정합성
  - 상태 전환 / 카운트·필터·검색 정확성 / 알림 발송 조건·시점

PHASE C. 구조·계약  [스킬: spring-boot-patterns, api-contract-review, jpa-patterns]
  - @Transactional 경계 / 이벤트 AFTER_COMMIT / 응답 포맷·상태코드·Swagger / N+1·인덱스

PHASE D. 테스트  [스킬: test-quality]
  - 핵심 케이스 커버 여부 (특히 인가 우회·경계값)

보고 형식
  - 점검 파일 목록 / 엔드포인트 × 역할 인가 표 / IDOR 결과(PASS·FAIL)
  - 이슈: 🔴 Critical(인가 우회·PII 노출) / 🟠 High / 🟡 Medium / 🟢 Low
  - 종합 판정

규칙
  - 점검만, 코드 수정 금지 / 대상 도메인 밖 건드리지 말 것
  - DB 직접 조작 금지 / 자동 git commit·push 금지

산출물
  ① docs/(YYYY-MM-DD) audit-<대상>.md
  ② docs/progress.md 갱신 (PASS/FAIL)
  ③ 이슈 발견 시 수정용 커밋 메시지 초안
```
