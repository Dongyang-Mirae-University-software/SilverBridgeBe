# 영향 범위 점검 - 이상감지 판정 다수결 전환·동수 재확인 안내·관리자 정정 폐지

> 2026-09-21 · 템플릿 C · 대상 브랜치 `feature/anomaly-majority-review`(PR 전) · 근거 `(2026-09-21) policy-change-anomaly-majority-review.md`

## 불변식

판정 = 응답 보호자 다수결, 동수만 CONFLICTED / 관리자는 판정하지 않는다 / 동수 안내는 응답한 다른 보호자에게 보호자당 1회 FCM(SETTINGS_ONLY) /
제거된 API·필드·에러 코드는 어디서도 참조되지 않는다.

## 판정

**PASS - 🔴·🟠 없음, 🟡 2 · 🟢 2.** PR 전 필수 수정 없음. 🟡 E-1·🟢 B-1은 이 PR 안에서 바로 반영했다.

| 항목 | 결과 | 근거 |
|---|---|---|
| A. 판정 상태 사용처 | PASS | `AdminDashboardService:155-158` 4값 집계(등호 비교 없음), 재촉 Planner PENDING 전용(`:93`·`:157`), 동수 안내 CONFLICTED 전용(`:231`), `GuardianAnomalyService:126` 동수 트리거만 `==` |
| B. `NotificationType` 추가 | PASS | 디스패처는 정책으로만 분기(`NotificationDispatcher:69`), 강제 채널 가드 테스트는 강제 종류만 대조, 알림톡 매핑은 `ANOMALY_DETECTED`뿐, 알림 종류를 저장하는 테이블·CHECK 없음 |
| C. 제거 잔재 | PASS | main·test에 살아 있는 참조 없음(설명 주석만). `ANOMALY_REVIEW_RESOLVE` enum·V50 CHECK 유지, `AdminAuditActionCheckSyncTest`(enum ⊆ CHECK) 통과 |
| D. V52 SQL·엔티티 매핑 | PASS(육안) | `UPDATE ... FROM`·`COUNT FILTER` 문법, CASE 결과가 `chk_anomaly_incident_status` 범위 안, INSERT는 feedback UNIQUE 덕에 충돌 불가, `boolean↔BOOLEAN`·`OffsetDateTime↔TIMESTAMPTZ`·IDENTITY 일치(`ddl-auto=validate` 기동 안전) |
| E. 동시성·경계 | 🟡 2 · 🟢 1 | 아래 |
| F. 문서 정합 | PASS | Swagger(보호자·관리자·태그), 정책 파일, CLAUDE.md가 코드와 일치 |

## 이슈

### 🟡 E-1. 동수 제외 기록의 exists→save 경합 - **반영 완료**

`skipConflictNoticeFor`가 조회 후 저장이라, 같은 보호자의 동시 응답(더블 탭)이나 스케줄러 선점과 겹치면 두 트랜잭션이 모두 검사를 통과해
UNIQUE 위반(23505)으로 **보호자 응답 자체가 409로 실패**할 수 있었다. 확률은 극히 낮지만 응답 저장이 안내 기록 때문에 실패하면 안 된다.
→ 네이티브 `INSERT ... ON CONFLICT (incident_id, guardian_id) DO NOTHING`(`AnomalyReviewConflictLogRepository.insertSkipIfAbsent`)으로 교체.
스케줄러 쪽 선점(`saveAll`)이 응답의 제외 기록과 겹치면 그 주기만 롤백되고 5분 뒤 다시 시도한다(이미 처리된 행으로 보고 건너뛴다) - 수용.

### 🟡 E-2. 동시 응답 시 판정 상태 덮어쓰기 (기존 결함, 이번 변경으로 악화 없음) - 후속

`AnomalyIncident`에 `@Version`이 없고 `submitFeedback`은 자기 트랜잭션이 읽은 응답으로 상태를 재조립한다. 두 보호자가 **같은 순간** 반대로
응답하면 각자 자기 표만 보고 REAL/FALSE_ALARM으로 확정해, 실제로는 동수인데 CONFLICTED가 되지 않고 동수 안내도 나가지 않는다.
다음 응답(번복 포함)에서 정정된다. → 후속: 상황 행 비관적 잠금(`@Lock(PESSIMISTIC_WRITE)`) 검토.

### 🟢 E-3. 연결 해제·탈퇴 보호자의 표 (기존) - 후속

연결 해제된 보호자의 응답은 삭제되지 않아 표에 계속 들어간다(남은 보호자가 1:1 동수를 풀려면 그 표와 같게 답해야 한다).
탈퇴 시 FK CASCADE로 응답이 지워져도 상황 상태는 재계산되지 않는다(1:1 CONFLICTED가 1표만 남은 채 유지).
→ 후속: 연결 해제·탈퇴 시 재계산 여부와 "현재 연결된 보호자 표만 센다" 정책 여부를 결정.

### 🟢 B-1. 새 종류의 허용 채널(FCM만) 테스트 부재 - **반영 완료**

`NotificationDispatcherTest`에 `ANOMALY_REVIEW_CONFLICTED`가 문자를 켠 사용자에게도 FCM만 보내는지 고정하는 케이스 추가.

## 남은 위험

- **V52 실 PostgreSQL 미실행**(로컬 Docker 없음, Testcontainers 미도입). 육안 검토만 PASS - dev 머지 시 CD Flyway가 첫 실행이다.

## 검증

- `./gradlew test` 593 tests / 0 failures / 0 errors (skipped 1) - E-1·B-1 반영 후.
