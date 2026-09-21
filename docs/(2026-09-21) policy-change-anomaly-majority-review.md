# 이상감지 판정 - 다수결 전환 · 동수 재확인 안내 · 관리자 정정 폐지

> 2026-09-21 · 정책 변경 · 마이그레이션 V52(⚠️ 기존 판정 재계산 포함, 비가역)

## 왜

2026-09-01에 판정 규칙을 "다수결 금지"로 잡았다. 보호자 응답이 하나라도 갈리면 `CONFLICTED`로 두고,
관리자가 로그 화면에서 보고 정정(`PATCH /api/admin/anomaly/{incidentId}/review`, 2026-09-02)하는 구조였다.
불일치 자체를 관리자에게 보여 줄 정보로 보존하려는 의도였다.

관리자 콘솔 시안을 다듬으면서 이 구조를 다시 봤다. 관리자는 현장을 모른다. 가족 셋 중 둘이 "요리 연기였다"고
한 건을 운영자가 대신 확정하는 것도, 가족끼리 갈린 판정을 운영자가 한쪽으로 정하는 것도 맞지 않다.
그래서 **판정은 가족이 정하고, 갈리면 가족끼리 다시 확인해 합의하는 것**으로 바꿨다(사용자 결정).

## 무엇이 바뀌었나

| 항목 | 이전 (09-01 ~ 09-21) | 이후 |
|---|---|---|
| 판정 규칙 | 응답이 하나라도 다르면 `CONFLICTED` | **응답한 보호자의 다수결**. 동수(1:1, 2:2)만 `CONFLICTED` |
| 동수(갈림) 해소 | 관리자 정정 | **보호자 재응답(번복)으로 합의** |
| 동수 알림 | 없음 | **재확인 안내** `ANOMALY_REVIEW_CONFLICTED` (응답한 보호자에게 보호자당 1회) |
| 관리자 정정 API | `PATCH .../review` | **제거** - 관리자 화면은 조회 전용 |
| 관리자 확정 후 보호자 응답 | 409 `ANOMALY_ALREADY_RESOLVED` | 해당 없음(에러 코드 제거) |

### 판정 표

| 응답 | 결과 |
|---|---|
| 없음 | `PENDING` |
| REAL 1 | `REAL` |
| REAL 2 : FALSE_ALARM 1 | `REAL` |
| REAL 1 : FALSE_ALARM 2 | `FALSE_ALARM` |
| REAL 1 : FALSE_ALARM 1 | `CONFLICTED` |
| REAL 2 : FALSE_ALARM 2 | `CONFLICTED` |

미응답 보호자는 표에 넣지 않는다. 번복할 때마다 다시 센다.

## 동수 재확인 안내

- **대상**: 그 상황에 **이미 응답한** 보호자 중 ACTIVE 연결이고 재촉 수신 설정을 끄지 않은 사람.
  - **방금 답해 동수를 만든 보호자는 제외** - 응답 API가 이미 `CONFLICTED`를 돌려줬다. 응답 트랜잭션이
    `anomaly_review_conflict_log`에 `sent=false` 행을 남겨 스케줄러가 건너뛴다.
  - 미응답 보호자는 대상이 아니다(건별 재촉이 따로 간다).
- **횟수**: 상황당 보호자당 **1회**(`UNIQUE (incident_id, guardian_id)`). 번복으로 동수↔다수를 오가도 다시 보내지 않는다.
  동수가 풀리지 않아도 추가 재촉은 없고, 마감(상황 시작 + 3일)이 지나면 대상에서 빠진다.
- **발송 경로**: 기존 판정 재촉 스케줄러(5분 주기)에 얹었다. 야간(22:00~08:00 KST)은 아침으로 미루고,
  선점 후 발송(기록 먼저 커밋)이며, 킬 스위치 `anomaly.review-reminder.enabled`를 공유한다.
  응답 직후부터 최대 5분 늦게 도착한다.
- **채널**: FCM만(`SETTINGS_ONLY` + 허용 채널 FCM). 알림톡 매핑 없음(다발성 - 승인 템플릿 없이 금지).
- **문구**: 제목 `판정 확인 요청` / 본문 `9월 21일 14:05 · 김영희님 거실의 화재 감지에 대해 다른 보호자와 판정이 다릅니다. 다시 확인해 주세요.`
  - **누가 무엇이라 답했는지는 싣지 않는다** - 가족 사이의 의견 차이를 푸시로 노출하지 않는다.
- **FCM data**: `type=ANOMALY_REVIEW_CONFLICTED`, `incidentId`, `wardId`, `wardName`, `detectedType`, `detectedTypeLabel`
  (건별 재촉 `ANOMALY_REVIEW_REQUIRED`와 같은 키 구성).

## 수용한 대가

1. **2:1은 소수 의견이 상태값에서 사라진다.** 보호자 응답 원본은 그대로라 관리자 목록의 `feedbacks`에서는 보인다.
2. **합의되지 않은 동수는 영구 `CONFLICTED`로 남는다.** 관리자가 풀어 줄 수단이 없다 - 의도된 결과다.
3. 되돌리지 말 것: "소수 의견이 사라진다"며 다수결 금지로, "동수가 안 풀린다"며 관리자 정정으로 되돌리는 것은
   이번에 명시적으로 버린 선택이다.

## 데이터·스키마 (V52)

- `anomaly_review_conflict_log` 신설 - `sent`(보냈는가 / 보내지 않고 처리했는가), `UNIQUE (incident_id, guardian_id)`, CASCADE FK.
- **기존 판정 재계산(비가역)**: 관리자 정정 건 포함, 보호자 응답만으로 다시 센다. 응답이 없는데 PENDING이 아닌 건
  (관리자가 정정만 한 건)은 PENDING으로 되돌린다.
- 기존 `CONFLICTED`(재계산 후 동수) 건은 응답자 전원을 `sent=false`로 기록해 **안내하지 않는다** - 배포 직후 과거 건으로
  푸시가 몰리지 않게.
- **남긴 것**: `anomaly_incident.resolved_by`·`resolved_at`·`review_note` 컬럼(엔티티 매핑만 해제 - DROP은 비가역이라 별도 후속),
  `AdminAuditAction.ANOMALY_REVIEW_RESOLVE`와 `chk_admin_audit_action`(폐지 전 감사 로그 행 보존).
- 운영 DB(api.devdmu, 2026-09-21 확인): `anomaly_incident` 0행, 응답 0건 - 재계산 영향 없음. CD 서버(vkcs)는 미확인.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `GuardianAnomalyService` | `calculateStatus` 다수결, 409 제거, 동수를 만든 보호자 `sent=false` 기록 |
| `AnomalyIncident` | `resolveByAdmin`·`isAdminResolved`·재계산 잠금·정정 필드 매핑 제거 |
| `AdminAnomalyController`·`AdminAnomalyService` | `PATCH .../review`·`resolve` 제거, 조회 전용 |
| `AdminAnomalyReviewRequest` | 삭제 |
| `AnomalyIncidentItem`·`AdminAnomalyIncidentItem` | `resolvedByAdmin` / `resolvedBy`·`resolvedAt`·`reviewNote` 제거 |
| `ErrorCode` | `ANOMALY_ALREADY_RESOLVED`·`ANOMALY_INVALID_REVIEW_STATUS` 제거 |
| `NotificationType` | `ANOMALY_REVIEW_CONFLICTED(SETTINGS_ONLY, FCM)` 추가 |
| `AnomalyReviewReminderPlanner`·`Service`·`Scheduler` | `claimConflicts` / `sendConflictNotices` / 주기 호출 |
| `AnomalyReviewConflictLog`·`Repository` | 신설 |
| `V52__anomaly_majority_review.sql` | 안내 기록 테이블 + 재계산 |
| Swagger·주석 | 보호자·관리자 컨트롤러, `SwaggerConfig` 태그, enum·DTO·리포지토리 주석 |

## FE 영향 (Notion "프론트엔드 전달 내용" 갱신 대상)

- **보호자 앱**: `resolvedByAdmin` 필드와 409 처리 제거. `CONFLICTED` 표기를 "관리자 확인 대기" → "보호자 간 의견 동수 - 다시 확인"으로,
  응답 버튼은 항상 활성. FCM `data.type=ANOMALY_REVIEW_CONFLICTED` 분기 추가(탭하면 해당 상황으로 이동).
- **관리자 콘솔**: 정정 버튼·사유 입력·`PATCH` 호출·`resolved*` 표시 제거. 목록과 `feedbacks` 표시는 유지.
- 재계산으로 과거 `CONFLICTED`가 `REAL`·`FALSE_ALARM`으로 바뀔 수 있다.

## 검증

- `./gradlew test` 593 tests / 0 failures / 0 errors (skipped 1).
- 추가·변경 테스트: 판정 표 전 경우(`GuardianAnomalyServiceTest`), 동수를 만든 보호자 `sent=false`·재동수 미기록·번복으로 해소,
  `claimConflicts` 대상 선정(응답자만·처리 완료 제외·연결 해제·수신 끄기·야간), 안내 문구·data·발송 실패 격리
  (`AnomalyReviewReminderServiceTest`), 관리자 컨트롤러 쓰기 매핑 없음.
- ⚠️ **V52는 실제 PostgreSQL에서 실행해 보지 못했다**(로컬 Docker 없음, Testcontainers 미도입). dev 머지 시 CD의 Flyway가 첫 실행이다.
