# 보호자 알림 수신자 계산 - D-1 일관성 영향 범위 점검 (템플릿 C)

- 점검일: 2026-11-09 / 기준: dev `7bcd6f3` (#456 말벗 주의 알림 D-1 반영 직후)
- 변경점: #456으로 "피보호자 기록을 모아 보호자에게 보내는 알림"이 보호자별 연결 시점(D-1, 2026-10-31 사용자 결정)을 따르게 됐다.
- 불변식: **보호자에게 가는 알림은 그 보호자 화면이 보여 주는 것보다 많은 과거 기록(건수·일수·상황)을 드러내지 않는다.**
- 방법: 수신자 계산(`getActiveGuardianIds`·`findActiveConnectedAt`·`isVisibleSinceConnection`) 사용처 전수 + 알림 종류 18종 분류 + 결함 주입 3종(임시 worktree, 원복). 코드 수정 없음.
- 종합: **PASS** · 🔴 0 · 🟠 0 · 🟡 0 · 🟢 1 → **L-1 조치**(아래 6절)

## 1. 알림 종류 분류

| 종류 | 성격 | D-1 대상 | 판정 |
|---|---|---|---|
| `WARD_SOS`·`ANOMALY_DETECTED(_SELF)`·`COMPANION_RISK` | 지금 일어난 사건 | 아님(실시간 안전 알림) | 해당 없음 |
| `CAMERA_LINK_LOST` | 지금 상태("영상이 확인되지 않아…") | 아님 | 해당 없음 |
| `MEDICATION_REMINDER`·`COMPANION_PROMPT` | 피보호자 본인 수신 | 아님 | 해당 없음 |
| `MEDICATION_STOPPED` | 지금 중지된 약 건수 | 아님 | 해당 없음 |
| `CONNECTION_*`·`INQUIRY_ANSWERED` | 당사자 이벤트 | 아님 | 해당 없음 |
| `ANOMALY_REVIEW_REQUIRED`(재촉·미응답 요약)·`ANOMALY_REVIEW_CONFLICTED`(동수 안내) | 지난 상황 모음 | **대상** | PASS (§2) |
| `MEDICATION_MISSED`(미복용 요약) | 오늘 복약 상태 모음 | 화면 기준과 비교 | PASS (§3) |
| `COMPANION_NOTICE`(주의 알림) | 지난 기분 일수 | **대상** | PASS (#456) |

## 2. 이상감지 재촉·요약·동수 안내

| 경로 | 위치 | 판정 | 근거 |
|---|---|---|---|
| 건별 재촉 | `AnomalyReviewReminderPlanner.java:127` | PASS | `visibleTo` → `isVisibleSinceConnection(lastDetectedAt)` |
| 미응답 요약 | `:205` | PASS | 같은 조건 |
| 동수 안내 | `:294` | PASS | 같은 조건 |
| 화면과 기준 | `GuardianAnomalyService.java:256` | 같음 | 화면도 `lastDetectedAt` 기준 |

- 결함 주입: 재촉 조건 무력화 → `AnomalyReviewReminderPlannerTest` 실패(검출). **요약·동수 조건 무력화 → 이상감지 단위 테스트 전체 통과(미검출) → L-1.** 통합 `HistorySinceConnectedIntegrationTest`의 "요약"은 보호자 화면 요약 쿼리라 이 경로가 아니다.

## 3. 복약 미복용 요약

- `MedicationMissedAlertPlanner`는 연결 시점과 무관하게 그날 발송 시각까지 체크되지 않은 약을 센다.
- 보호자 복약 카드·대시보드도 연결 시점 조건이 없다(`domain/medication`·`domain/dashboard`에 `connectedAt` 사용 0건) - 복약은 정책 ②대로 "지금 ACTIVE 연결이면 오늘 상태를 본다"는 **현재 상태 기준**이라 D-1(SOS·이상감지 기록 열람) 대상이 아니다. 알림과 화면이 같은 숫자를 보여 불변식을 지킨다.
- 바꾸려면 D-1 범위를 복약으로 넓히는 사용자 결정이 먼저다(카드·요약·대시보드를 함께).

## 4. 이슈

### 🟢 L-1 이상감지 미응답 요약·동수 안내의 D-1 조건이 테스트로 고정되지 않았다

- 위치: `AnomalyReviewReminderPlanner.java:205`(요약), `:294`(동수 안내). 코드는 맞다.
- 결함 주입: 두 조건을 `false && !visibleTo(…)`로 무력화해도 `kr.silverbridge.main.domain.anomaly.*` 단위 테스트 전체가 통과.
- 수정: `AnomalyReviewReminderPlannerTest`에 "연결 전 상황은 요약에 넣지 않는다"·"연결 전 상황은 동수 안내하지 않는다" 두 건(기존 `incidentBeforeConnectionIsNotClaimed`와 같은 형태, `isVisibleSinceConnection` false stub).

## 5. 참고 (이슈 아님)

- `COMPANION_RISK`는 대화 중에 연결이 맺어지면 연결 전에 시작한 대화의 위험도 새 보호자에게 간다 - 지금 일어난 안전 신호라 SOS·화재와 같이 보낸다(수용).
- 결함 주입 첫 시도는 치환이 실패해 무효였고(코드 무변경), 방식을 바꿔 다시 돌린 결과만 위에 적었다.

## 6. 조치 (2026-11-09)

- L-1: `AnomalyReviewReminderPlannerTest`에 `incidentBeforeConnectionIsNotSummarized`(하루 요약)·`incidentBeforeConnectionIsNotConflictNotified`(동수 안내) 추가 - 기존 재촉 테스트와 같은 `isVisibleSinceConnection` false stub 형태.
- 검증: 같은 결함 주입(`:205`·`:294` 조건 무력화)을 다시 돌려 각각 새 테스트 1건이 실패함(검출)을 확인, 원복. 플래너 테스트 28건 통과. 운영 코드 변경 없음.

