# 영향 범위 점검 - 복약 시각 알림 보호자 설정 연동 (#441) (2026-11-04)

- 템플릿 C(영향 범위). 대상 커밋 `49e7347`(#441 머지). 점검만, 코드 수정 없음.
- 기능 문서: `docs/(2026-11-04) fix-medication-reminder-guardian-toggle.md`

## PHASE 0 - 변경점과 불변식

| 바뀐 것 | 내용 |
|---|---|
| `NotificationType.MEDICATION_REMINDER` | 허용 채널 FCM·문자·알림톡 → **FCM만** (정책 `SETTINGS_ONLY` 그대로) |
| 보호자 복약 토글 의미 | 계정 `medication_enabled`·피보호자별 `missed_alert_enabled`가 미복용 요약에 더해 **피보호자 복용 시각 알림**도 켜고 끈다 |
| `MedicationReminderPlanner` | 켠 이용 중 ACTIVE 보호자 1명 이상일 때만 선점(최초·재알림) |

불변식
1. `MEDICATION_REMINDER`는 FCM으로만 나간다.
2. 피보호자 복용 시각 알림은 "ACTIVE 연결 + 이용 중 + 계정 AND 피보호자별 토글 ON" 보호자가 1명 이상일 때만 선점·발송된다.

## PHASE A·B - 사용처별 판정

| 영역 | 위치 | 판정 | 근거 |
|---|---|---|---|
| 디스패처 | `NotificationDispatcher` | PASS | 보호자 설정을 모름(`GuardianNotificationPreferenceUsageGuardTest`), 허용 채널 교집합만 적용. 강제 정책(SOS·이상감지·말벗 위험)은 무관 |
| 미복용 요약 | `MedicationMissedAlertPlanner:114·150` | PASS | 같은 두 토글·같은 기본값(행 없음=ON). 상태 필터는 요약은 디스패처 차단, 시각 알림은 플래너 필터 - 결과 동일 |
| 이메일 정책 | `NotificationTypeEmailPolicyTest` | PASS | `MEDICATION_MISSED` 이메일 허용 그대로, `MEDICATION_REMINDER` 이메일 없음 |
| 알림톡 | `application.yaml` templates | PASS | 매핑 없음 그대로(복약 불변 규칙 ⑤), 허용 채널에서도 빠짐 |
| 웹푸시 긴급도 | `FcmMessageProperties:41`·`application.yaml:305` | PASS | FCM 속성이라 영향 없음 |
| 관리자 알림 이력 | `AdminNotificationCategory:21`·`NotificationTypeLabel:20` | PASS | 분류·라벨만. 끈 보호자의 피보호자는 선점이 없어 이력 행도 없음(미복용 요약과 같은 처리) |
| 선점 후 발송·UNIQUE | `MedicationReminderPlanner.claim` | PASS | 게이트가 선점 앞, 순서 무변경 |
| MED-G01 | `wardIdsWithAlertingGuardian` | PASS | 연결 없음 → 설정 조회 없이 제외(테스트 고정) |
| 킬 스위치 | `medication.reminder.enabled`·`missed-alert.enabled` | PASS | 독립 그대로 |
| 탈퇴 | 보호자 INACTIVE | PASS | 상태 필터로 제외, purge 뒤 연결·설정 CASCADE |
| 이용 제한 | 보호자 RESTRICTED | PASS(동작 변경, 승인됨) | 유일한 보호자가 정지되면 피보호자 시각 알림도 멈춘다 - Q2 결정. 아래 L-3 |
| 역할 변경 | `AdminUserService`·`MedicationRoleChangeService` | PASS | 연결 정리로 제외. 옛 피보호자가 보호자가 돼도 `wardId` 재필터로 섞이지 않음 |
| 강제 연결 | `AdminConnectionService.forceConnect` | PASS | 새 보호자는 설정 행 없음 = ON → 알림 재개 |
| 연결 해제·재연결 | `guardian_medication_setting` | PASS | (보호자, 피보호자) OFF 행이 남아 재연결 시 유지 - 미복용 요약과 같음 |
| 성능 | 실행당 고정 4회 | PASS | `idx_guardian_medication_setting_ward` 존재, `guardian_notification_preference`는 끈 사람만 행 |
| 경합 | 선점 커밋 ~ 발송 사이 토글 변경 | PASS(수용) | 최대 1회분 더 나갈 수 있음 - 미복용 요약과 같음 |
| Swagger 계약 | 아래 M-1 | **FAIL** | 토글 설명이 "미복용 요약만" |
| 코드 주석 | 아래 L-1 | FAIL(문서) | "FCM과 문자뿐"·"늘 보낸다" 잔존 |

## 이슈

🔴 0 · 🟠 0 · 🟡 1 · 🟢 4

- **🟡 M-1 Swagger가 토글의 새 의미를 말하지 않는다** - FE는 Swagger를 계약으로 읽는다. 보호자가 끄면 피보호자 복용 시각 알림까지 멈춘다는 사실이 빠져 있어, FE가 피보호자별 토글을 "내 요약만"으로 다시 붙이면 사용자가 모르게 어르신 알림을 끈다.
  - `GuardianNotificationTypeSettingController.java:35·61`, `GuardianNotificationTypeSettingResponse.java:11`, `GuardianNotificationTypeSettingRequest.java:12`
  - `GuardianMedicationController.java:265·270·291·297`(medication-alert-setting 조회·변경), `WardMedicationSummary.java:40`
  - 조치안: 설명 문구만 교체(동작·응답 무변경). 문구 고정 테스트는 기존 `CameraIsActiveApiDocTest` 형태로 1건.
- **🟢 L-1 코드 주석 drift** - `MedicationReminderService.java:24·26`("FCM·SMS"·"채널은 FCM과 문자뿐"), `MedicationPreference.java:6`·`MedicationSettingService.java:15`("늘 보낸다"). 조치안: 주석 교체.
- **🟢 L-2 FE 주석·전달 범위** - FE `WardMedicationCard.tsx:375` 주석이 "어르신 기기 알림은 늘 보낸다"(10-24). FE는 피보호자별 토글에 `true`만 써서(시각 선택 시) 실제 OFF 경로는 없다(gosky OFF 행 0건). FE 전달 문서에 "환경설정 복약 알림 문구"와 함께 이 주석 갱신을 넣는다.
- **🟢 L-3 정지된 보호자만 있는 피보호자** - 시각 알림이 멈춘다(승인된 Q2). 관리자 대시보드 `wardsWithoutReachableGuardian`이 이미 이런 피보호자를 센다. 조치 없음(기록).
- **🟢 L-4 FCM 토큰이 없는 피보호자** - 문자 경로가 닫혀 받을 길이 없다(gosky 2명, 이전에도 문자를 켜지 않아 실패 중). 조치 없음(수용, 기능 문서에 기록됨).

## PHASE C - 테스트로 고정할 수 있는가

- 불변식 1: `NotificationTypeEmailPolicyTest.다른_채널_무변경`·`NotificationDispatcherTest.복용시각알림_문자설정무시`로 고정됨.
- 불변식 2: `MedicationReminderPlannerTest` gate 10건으로 고정됨. 파생 쿼리 `findByWardIdInAndMissedAlertEnabledFalse`는 두 서버 기동 시 이름 파싱이 검증됐고 SQL이 단순해 통합 테스트는 추가하지 않는다.
- M-1 조치 시 Swagger 문구 테스트 1건 추가 제안.

## 종합

**조건부 PASS** - 동작 불변식은 모두 지켜진다. 남은 것은 계약 문서(Swagger)·주석 drift(M-1·L-1)이며 동작 변경 없는 문구 PR 하나로 닫을 수 있다.

수정 커밋 초안: `comment: 복약 알림 토글 Swagger·주석을 피보호자 시각 알림 연동에 맞춤` (Swagger는 코드 파일이라 PR 경유)
