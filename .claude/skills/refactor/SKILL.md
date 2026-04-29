---
name: refactor
description: 코드 정리·리팩토링이 필요할 때 사용. "리팩토링", "코드 정리", "중복 제거", "메서드 분리", "이름 개선" 같은 요청이나 PR 머지 전 품질 정리 단계에서 발동. 동작(behavior) 변경 없이 가독성·구조·재사용성만 개선한다. 버그 수정·기능 추가·성능 튜닝·보안 패치는 이 스킬의 범위가 아니다.
---

## 목적
동작을 바꾸지 않고 가독성·응집도·재사용성을 높인다.

## 입력/스코프
- 기본: 현재 브랜치 변경분 (`git diff origin/dev...HEAD`)
- 사용자가 경로 지정 시 해당 디렉토리만
- 제외: 자동 생성 코드, `build/`, `out/`, Flyway 마이그레이션 SQL, 외부 라이브러리

## 절차
1. **변경 범위 파악** — `git diff --stat`
2. **기존 코드 탐색** — 새 추상화 만들기 전 `Grep`/`Glob`으로 유사 기능 확인 (CLAUDE.md 규칙 2)
3. **스멜 검출** — 아래 "검출 기준" 순서대로 스캔
4. **위험도 분류** — 즉시 적용 / 사용자 확인 필요 / 별도 PR 권장
5. **적용** — 위험 큰 항목(상속 변경, 패키지 이동)은 사용자 확인 후
6. **검증** — `./gradlew build -x test --no-daemon` 통과 필수
7. **커밋** — `refactor: 한국어 요약`, feature 브랜치에서만

## 검출 기준 (이 프로젝트 특화)

### 구조
- **God Service / 긴 메서드**: 클래스 400줄·메서드 60줄 초과 → 책임 분리 (`AdminUserService`/`AdminConnectionService` 패턴)
- **Service-in-Service 순환 의존** → 도메인 이벤트 또는 Coordinator로 분리
- **Controller에 비즈니스 로직** → Controller는 검증·DTO 변환·서비스 호출만 (`agent_docs/api-design.md`)

### Spring / Java 21
- `@Autowired` 필드 주입 → 생성자 주입 + `@RequiredArgsConstructor`
- 단순 데이터 컨테이너 class → `record` (Request/Response DTO)
- 다단 if-else / instanceof 체인 → switch expression / pattern matching
- 과도한 `Optional.get()` 체인 → `orElseThrow(() -> new CustomException(...))`

### 프로젝트 규칙 위반 (CLAUDE.md non-negotiable)
- `RuntimeException` 직접 throw → `CustomException(ErrorCode.X)` (규칙 7)
- Entity가 `BaseTimeEntity` 미상속 → 상속 추가 (규칙 6)
- Entity 직접 응답 노출 → Response DTO 분리 (규칙 6)
- Redis 키 문자열 직접 조합 → `RedisKeys` 상수 (규칙 3)
- Connection 상태 변경 시 Service에서 WebSocket/FCM 직접 호출 → `ApplicationEventPublisher` (규칙 4, `agent_docs/spring-event.md`)
- Rate Limit 인라인 키 → `rateLimitService.check(endpoint, identifier)` (규칙 5)

### 가독성
- 마법 숫자/문자열 → 상수·enum
- 부정형 boolean (`isNotValid`) → 긍정형
- 매개변수 5개 이상 → 파라미터 객체/record
- WHAT을 설명하는 주석 → 변수명·메서드명으로 자체설명, 주석은 WHY만

### 죽은 코드
- 사용처 없는 public 메서드/필드/클래스/import → 삭제 (백워드 호환 셔 만들지 않음)
- 주석 처리된 코드 → 삭제 (git history로 복원 가능)

## Non-goals
- 동작 변경 금지 (버그 수정·기능 추가·API 응답 형태 변경은 별도 PR)
- 성능 튜닝 → `db-improve`/`performance-check` 영역
- 보안 패치 → `security-scan` 영역
- 의존성 버전 변경 → `dependency-check` 영역
- 테스트 추가 강제 안 함 (위험도만 보고)

## 출력 포맷

### 1) 요약 표
| # | 파일:라인 | 카테고리 | 심각도 | 한 줄 요약 |
|---|---|---|---|---|

심각도:
- **Critical**: CLAUDE.md non-negotiable 규칙 위반
- **High**: 구조적 문제 (God class, 순환 의존)
- **Medium**: 가독성·관용 표현
- **Low**: 사소한 정리 (unused import 등)

### 2) 항목별 상세
각 항목: 파일:라인 / 현재 코드 발췌(3~10줄) / 문제 / 권장 수정안 / 위험도·영향 범위

### 3) 적용 계획
- 즉시 적용(안전): N개
- 사용자 확인 필요: N개
- 별도 PR 권장: N개

## 작업 마무리
- `./gradlew build -x test --no-daemon` 통과 확인
- 커밋: `refactor: <도메인> <요약>`
- PR 본문에 "동작 변경 없음" 명시
