# Skills (스킬)

스킬은 Claude에게 Java 개발에 필요한 특정 패턴을 학습시키는 **재사용 가능한 프롬프트** 모음입니다.

## 폴더 구조 규칙

각 스킬 폴더는 다음 두 개의 파일을 포함합니다.

| 파일 | 용도 | 대상 |
|------|------|------|
| `SKILL.md` | Claude(AI)에게 전달하는 지시문 | AI (`view`로 로드됨) |
| `README.md` | 사용 문서, 예시, 팁 | 사람 (온보딩용) |

## 제공되는 스킬 목록

### 워크플로우 (Workflow)
| 스킬 | 설명 |
|------|------|
| [work-prompt](work-prompt/) | **구현·점검 요청 시 가장 먼저** — PHASE 구조 작업 프롬프트를 설계해 제시하고 "시작해" 승인 후 실행 (생성/점검 템플릿 2종) |
| [git-commit](git-commit/) | Java 프로젝트용 컨벤셔널 커밋 메시지 작성 |
| [changelog-generator](changelog-generator/) | git 커밋 이력으로부터 체인지로그 생성 |
| [issue-triage](issue-triage/) | GitHub 이슈 분류 및 우선순위 정리 |

### 코드 품질 (Code Quality)
| 스킬 | 설명 |
|------|------|
| [java-code-review](java-code-review/) | 체계적인 Java 코드 리뷰 체크리스트 |
| [api-contract-review](api-contract-review/) | REST API 점검: HTTP 의미, 버저닝, 호환성 |
| [concurrency-review](concurrency-review/) | 스레드 안전성, 레이스 컨디션, `@Async`, Virtual Threads |
| [performance-smell-detection](performance-smell-detection/) | 코드 레벨 성능 냄새 탐지 (스트림, 박싱, 정규식 등) |
| [test-quality](test-quality/) | JUnit 5 + AssertJ 테스트 작성 패턴 |
| [maven-dependency-audit](maven-dependency-audit/) | 의존성 업데이트 및 취약점 점검 |
| [security-audit](security-audit/) | OWASP Top 10, 입력 검증, 인젝션 방지 |

### 아키텍처 & 설계 (Architecture & Design)
| 스킬 | 설명 |
|------|------|
| [architecture-review](architecture-review/) | 거시적 관점 리뷰: 패키지, 모듈, 레이어, 경계 |
| [solid-principles](solid-principles/) | SOLID 원칙 + Java 예제 |
| [design-patterns](design-patterns/) | Factory, Builder, Strategy, Observer, Decorator 등 |
| [clean-code](clean-code/) | DRY, KISS, YAGNI, 네이밍, 리팩토링 |

### 프레임워크 & 데이터 (Framework & Data)
| 스킬 | 설명 |
|------|------|
| [spring-boot-patterns](spring-boot-patterns/) | Spring Boot 베스트 프랙티스 |
| [java-migration](java-migration/) | Java 버전 업그레이드 가이드 (8→11→17→21) |
| [jpa-patterns](jpa-patterns/) | JPA/Hibernate 패턴 (N+1, 지연 로딩, 트랜잭션) |
| [logging-patterns](logging-patterns/) | 구조적 로깅 (JSON), SLF4J, MDC, AI 친화적 포맷 |

## 새로운 스킬 추가하기

### 시작 전 체크리스트

새 스킬 아이디어가 기존 스킬과 충돌하지 않는지 확인하세요.

- [ ] **중복 없음** — 위 표에서 비슷한 스킬이 있는지 확인
- [ ] **레벨이 명확함** — Micro(함수) / Meso(클래스) / Macro(패키지) / Framework / Cross-cutting
- [ ] **타입이 명확함** — Audit(기존 코드 점검) 또는 Template(작성 방법 제시)
- [ ] **고유한 가치** — 기존에 없는 어떤 가치를 추가하는가?
- [ ] **좁은 범위** — 한 세션 안에 적용 가능 (체크리스트 15개 미만)

> 📖 **상세 가이드라인:** [docs/SKILL_GUIDELINES.md](../docs/SKILL_GUIDELINES.md)

### 구현 절차

1. 폴더 생성: `.claude/skills/<skill-name>/`
2. `SKILL.md` 작성 (Claude용 지시문)
3. `README.md` 작성 (사람용 문서, 기존 README를 템플릿으로 사용)
4. 위 표 갱신
5. 메인 README.md 업데이트

## 사용 방법

스킬은 컨텍스트에 따라 Claude Code가 자동으로 로드합니다. 직접 호출도 가능합니다.

```bash
# 자동 — Claude가 사용 시점을 감지
> "이 변경사항을 커밋해줘"        # git-commit 로드
> "이 코드를 SOLID 관점에서 리뷰해줘"  # solid-principles 로드

# 수동 — 슬래시 커맨드로 직접 호출
> /git-commit
> /solid-principles
```

## 더 알아보기

- [Claude Code Skills 공식 문서](https://code.claude.com/docs/en/skills) — 스킬 작성 및 사용에 대한 공식 가이드
