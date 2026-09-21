# 영향 범위 점검 - 관리자 이상감지 로그 v2 · 연기=화재 통합

> 2026-09-21 · 템플릿 C · 대상 브랜치 `feature/admin-anomaly-log-v2`(PR 전) · 근거 `(2026-09-21) feature-admin-anomaly-log-v2.md`

## 불변식

백엔드에 SMOKE 값은 없다(수신 smoke→FIRE, 과거 행은 V53이 FIRE로) / knife→WEAPON이지만 WEAPON은 감지 대상 아님 /
유형 라벨은 `DetectedTypeLabel` 한 곳 / 관리자 이상감지 API는 조회 전용이고 파라미터 생략 시 기존 동작과 같다.

## 판정

**PASS - 🔴·🟠·🟡 없음, 🟢 3(주석 2건 이 PR에서 반영, 1건 수용).**

| 항목 | 결과 | 근거 |
|---|---|---|
| A. `DetectedType` 사용처 | PASS | 진입점은 `fromAi` 하나(`AnomalySignalParser:43`). main에 SMOKE switch·equals 없음. 라벨은 `DetectedTypeLabel`만, 알림 리스너는 위임 |
| B. V53 SQL | PASS | `detected_type` 컬럼은 `anomaly_event`·`anomaly_incident` 둘뿐(V31 이후 이름 일치), CHECK 제약 없음 |
| C. 새 JPQL | PASS(육안) | 불린·IN·enum null 패턴은 리포 기존 쿼리와 같다. `escape '\\'`는 `UserRepository`의 기존 검색과 같은 문자열. 프로젝션 alias(`detectedType`·`reviewStatus`·`total`)와 getter 일치, COUNT(Long) → `long` 언박싱. 페이징 count 자동 파생 가능 |
| D. 하위호환·계약 | PASS | 파라미터 생략 시 하한 2000-01-01로 전체 포함. `durationMinutes`는 추가 필드. 잘못된 period·type은 전용 enum이라 400. 보호자 API에서 SMOKE가 사라져도 FE 매핑이 죽은 코드가 될 뿐 파손 없음 |
| E. 검색어·인가 | PASS | 클래스 레벨 `@PreAuthorize` 유지(summary 포함), 감사 로그 없음, 분모 0 → null, 오탐 비율은 응답률·신뢰도와 함께 |
| F. 문서 | PASS | CLAUDE.md·기능 문서가 코드와 일치. 정책 파일의 "0건 유형" 규칙과 모순 없음 |

## 이슈

- 🟢 **E-1 (반영)** `AnomalyEventCooldown` 주석이 "화재와 연기가 동시에 잡히면 서로 다른 이력"이라고 적고 있었다 → 한 키를 공유한다로 정정.
- 🟢 **E-2 (반영)** `AnomalySignal`·`AnomalyJudge` 주석의 "fire/smoke" → smoke→FIRE·knife→WEAPON 명시.
- 🟢 **E-3 (수용)** Redis 쿨다운 키에 `detectedType.name()`이 들어가 배포 시점에 남은 `…:SMOKE` 키는 TTL로 자연 소멸한다.
  배포 직후 한 번, 원래라면 SMOKE 키에 걸렸을 FIRE 감지가 쿨다운을 통과할 수 있다(1회성).
- **의도된 동작 변화**: 같은 카메라의 화재·연기가 한 상황으로 묶이고 쿨다운을 공유해 알림 빈도가 약간 줄 수 있다.

## 남은 위험

- 새 JPQL 2개는 실 DB 미실행 - Spring Data가 기동 시 검사하므로 gosky 배포 기동 로그로 확인한다.

## 검증

- `./gradlew test` 609 tests / 0 failures / 0 errors (skipped 1).
