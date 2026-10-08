# 보호자 알림 종류 설정 API (2026-10-08)

보호자 환경설정 "알림 종류" 탭이 종류별 수신 on/off 를 저장·조회하는 API. **BE 가 실제로 보내는 알림만** 다룬다.

## 범위

| 항목 | 처리 |
|---|---|
| SOS | 필수. `required: true`, 항상 켜짐. 변경 경로 없음 |
| 이상감지 발생 | 필수. `required: true`, 항상 켜짐. 푸시는 `FORCED_PUSH_PLUS_SETTINGS` 라 어떤 설정으로도 줄지 않는다 |
| 판정 재촉(`ANOMALY_REVIEW_*`) | 끌 수 있음. 저장은 기존 `guardian_anomaly_setting` 에 위임 (`/api/guardian/anomaly/reminder-setting` 과 같은 값) |
| 복약 미복용 요약(`MEDICATION_MISSED`) | 끌 수 있음. 새 테이블 `guardian_notification_preference` (V59) |
| 정서 변화 · 병원 예약 | **만들지 않음** - BE 가 발송하지 않는다. FE 는 숨기거나 "준비 중" 표시 |
| 알림 방법(푸시·문자·알림톡·이메일) | 기존 `GET·PUT /api/user/me/notification-settings`. 신규 API 없음 (이메일은 발송 미구현) |

## API

`GET · PUT /api/guardian/notification-type-setting` (GUARDIAN 전용, 보호자 ID 는 토큰에서만)

```json
{ "sos":                   { "enabled": true, "required": true  },
  "anomalyDetection":      { "enabled": true, "required": true  },
  "anomalyReviewReminder": { "enabled": true, "required": false },
  "medication":            { "enabled": true, "required": false } }
```

- PUT 본문: `{ "anomalyReviewReminder": bool?, "medication": bool? }`, null/생략 = 변경 안 함. 필수 알림이나 알 수 없는 키를 보내도 무시(저장 안 함).
- 응답으로 변경 후 전체를 돌려준다. 행이 없으면 기본값 ON.
- 요청서 초안의 `/api/guardian/settings/notifications`(PATCH)는 BE 컨벤션(`…-setting`, PUT 부분 수정)에 맞춰 위 경로로 바꿨다.

## 게이트 위치 (디스패처가 아니다)

- `MedicationMissedAlertPlanner`: 피보호자별 `findSettings` **앞에서** 계정 토글 OFF 보호자를 `pending` 에서 뺀다 → 선점 로그(`medication_missed_alert_log`)도 만들지 않는다 (선점 후 발송 · UNIQUE 불변). 효과값 = `계정 토글 AND 피보호자별 missedAlertEnabled`, 시각 설정은 그대로.
- `MEDICATION_STOPPED`(등록 보호자 탈퇴로 약 중지 안내)는 이 토글로 게이트하지 않는다 - 조치가 필요한 안내.
- `NotificationDispatcher` 는 이 설정을 모른다 (`GuardianNotificationPreferenceUsageGuardTest` 가 참조처를 고정).

## 변경 파일

- 신규: `V59__create_guardian_notification_preference.sql`, `GuardianNotificationPreference`(엔티티), `…Repository`(`insertIfAbsent` = `ON CONFLICT DO NOTHING`), `GuardianNotificationPreferenceService`, `GuardianNotificationTypeSettingController`, 요청/응답 DTO
- 수정: `MedicationMissedAlertPlanner`
- 테스트: 서비스 단위 6 · 컨트롤러 보안 3 · 참조처 가드 2 · 플래너 +3 · 통합(`GuardianNotificationPreferenceIntegrationTest`: 기본값 왕복·동시 최초 저장 20회)

## 바뀌지 않는 것

채널 설정 API, SOS 강제 발송, 이상감지 발생 알림의 푸시/문자 대체, 재촉 로직, `NotificationDispatcher`·`NotificationType`.

## 마이그레이션

V59 는 테이블 추가만 (비가역 DDL 없음, 백필 없음). users FK `ON DELETE CASCADE`.

## 검증

- `./gradlew test` 통과, `./gradlew build -x test` 통과, 통합 테스트 컴파일 통과.
- vkcs 통합 테스트 · 두 서버 배포 결과는 PR 본문에 기록.
