# 영향 범위 점검 - 보호자 알림 종류 설정 (2026-10-08, 템플릿 C)

대상: PR #335 (`GET·PUT /api/guardian/notification-type-setting`, V59). 코드 읽기 + 실서버(gosky) 호출로 확인.

## 결론

🔴🟠 0건. 강제 알림 경로는 새 설정의 영향을 받지 않는다. 🟡 2건, 🟢 3건.

## 확인 항목

| # | 확인 | 결과 |
|---|---|---|
| 1 | 전 도메인 `SETTINGS_ONLY` 사용처 중 새 설정을 읽는 곳 | `MedicationMissedAlertPlanner` 하나뿐 (`MEDICATION_MISSED`). 연결·문의·`MEDICATION_STOPPED`·복용 알림(`MEDICATION_REMINDER`, 피보호자 대상)·재촉은 그대로 |
| 2 | 강제 경로(`WARD_SOS`·`ANOMALY_DETECTED*`)가 영향받는가 | 아니오. `NotificationDispatcher`·SOS·이상감지 리스너는 이 설정을 참조하지 않는다 (`GuardianNotificationPreferenceUsageGuardTest`로 고정). 응답도 `sos`·`anomalyDetection`은 `required:true` 고정 |
| 3 | 정지·탈퇴 진행 계정 차단 | 차단은 `dispatch()` 한 곳. 새 설정은 dispatch 이전(플래너)에서 대상을 줄일 뿐이라 상호작용 없음 |
| 4 | `notification_log` | 계정 토글 OFF 보호자는 dispatch 자체가 없어 이력 행이 생기지 않는다 (피보호자별 OFF와 동일) → M-2 |
| 5 | 선점 후 발송 · UNIQUE | 대상에서 먼저 빼므로 선점 로그도 만들지 않는다. 순서·UNIQUE 불변 |
| 6 | 탈퇴 정리 | users FK `ON DELETE CASCADE`. 리스너 불필요 |
| 7 | 재촉 토글 일관성 | 새 API와 `/api/guardian/anomaly/reminder-setting`은 같은 저장소(`guardian_anomaly_setting`). 실서버에서 새 API로 끄면 구 API도 false, 복구 후 true 확인 |

## 실서버 확인 (gosky, 테스트 보호자 계정, 2026-10-08)

| 호출 | 결과 |
|---|---|
| GET | 200, 기본값 (sos·anomalyDetection 잠금, 나머지 ON) |
| 토큰 없음 | 401 |
| PUT `{medication:false, sos:false, anomalyDetection:false, hospitalReservation:false}` | 200, `medication`만 false. 잠금·미지원 키 무시 |
| 빈 본문 `{}` | 200, 변경 없음 |
| PUT `{anomalyReviewReminder:false}` 후 구 API 조회 | `reviewReminderEnabled:false` (위임 확인) |
| PUT `{medication:"x"}` | 400 |
| 복구 (`anomalyReviewReminder:true, medication:true`) | 원래 값으로 되돌림 확인 |

- WARD 토큰 호출(403)은 피보호자 테스트 계정이 없어 실서버에서는 못 했다. `@PreAuthorize` 단위 테스트(`GuardianNotificationTypeSettingControllerSecurityTest`)로 갈음.
- 부작용: 테스트 계정에 `guardian_notification_preference` 행 1개(medication=true)가 남았다. 기본값과 같아 동작 영향 없음.

## 발견

### 🟡 M-1 - 복약 카드 설정이 계정 토글 OFF를 반영하지 않는다
`GET /api/guardian/medication` 카드의 `missedAlertEnabled`(피보호자별)는 계정 토글과 무관하게 true로 내려간다. 계정 토글을 끈 보호자는 카드에 "미복용 요약 켜짐"이 보이는데 실제로는 오지 않는다.
- 권장: FE가 알림 종류 API의 `medication.enabled`가 false면 카드 안내를 표시. BE는 응답을 바꾸지 않는다(필드 의미 변경은 하위호환 위험). Swagger `medication-alert-setting` 설명에 한 줄 보강 제안.

### 🟡 M-2 - 계정 토글 OFF 건은 관리자 알림 이력에서 "안 보낸 이유"가 안 보인다
`NOT_SENT`로 남지 않는다. 기존 피보호자별 OFF와 같은 동작이라 일관되지만 "왜 안 갔지" 문의에는 답하기 어렵다. 수용 가능 (필요 시 플래너 쪽 DEBUG 로그 추가).

### 🟢 L-1 - 동시 최초 저장 경합 (재촉 쪽)
`GuardianAnomalySettingService.updateSetting`은 조회 후 save라 행이 없는 보호자가 같은 순간 `anomalyReviewReminder`를 두 번 보내면 `uq_guardian_anomaly_setting` 위반 가능. 기존 구 API에도 있던 경합이고, 새 PUT은 복약 쓰기와 같은 트랜잭션이라 함께 롤백된다. 영향이 작아(한 사용자의 동시 이중 요청) 이번엔 수정하지 않음. 고친다면 `ON CONFLICT DO NOTHING` 패턴으로.

### 🟢 L-2 - 역할 변경 후 잔존 행
보호자 → 피보호자로 역할이 바뀌어도 `guardian_notification_preference` 행은 남는다. 동작 영향 없음(보호자로 다시 돌아오면 이전 설정이 복원됨). 기존 `guardian_anomaly_setting`도 같다.

### 🟢 L-3 - 플래너의 피보호자별 조회
계정 토글 조회가 피보호자별 루프 안에서 1회씩 실행된다(`pending`이 있을 때만). 기존 `getActiveGuardianIds`·`findSettings`와 같은 패턴이고 건수가 적어 수용.

## 후속

- M-1 FE 전달 (복약 카드 안내) + Swagger 문구 보강 여부는 사용자 판단.
- 나머지는 기록만.
