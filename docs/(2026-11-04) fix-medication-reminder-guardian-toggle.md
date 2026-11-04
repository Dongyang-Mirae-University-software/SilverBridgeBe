# 복약 시각 알림 - 보호자 "복약 알림" 설정 연동 (2026-11-04)

## 배경

- 보호자가 환경설정에서 "복약 알림"과 "문자"를 껐는데, 피보호자 번호로 "약 드실 시간이에요" 문자가 매일 08·12·19시에 계속 왔다.
- 그 문자는 피보호자 본인에게 가는 `MEDICATION_REMINDER`였고, 피보호자 계정 채널 설정(문자 ON)을 따랐다.
- 보호자 "복약 알림" 토글(V59 `medication_enabled`)은 보호자가 받는 미복용 요약(`MEDICATION_MISSED`)만 막았고, 피보호자 화면에는 알림 설정 UI가 없어 끌 방법이 없었다.

## 결정 (사용자 승인)

| | 결정 |
|---|---|
| Q1 보호자 여러 명 | 한 명이라도 켜 두면 보낸다 |
| Q2 판단 기준 | 계정 토글 AND 피보호자별 토글(미복용 요약과 같음), ACTIVE 연결 + 이용 중 보호자만 센다 |
| Q3 채널 | FCM만 (`MEDICATION_REMINDER` 허용 채널 선언), 강제 정책 무변경 |
| Q4 기본값 | 행이 없으면 켜짐 - gosky 데이터상 의도치 않게 끊기는 피보호자 없음 |
| Q5 데이터 | 마이그레이션 없음 - 피보호자 문자 설정은 허용 채널로 막힌다 |

## 구현

- `MedicationReminderPlanner.withAlertingGuardian` - 최초·재알림 모두. 연결 1회 + `userRepository.findAllById` + `medicationDisabledGuardians()` + `GuardianMedicationSettingRepository.findByWardIdInAndMissedAlertEnabledFalse` = 실행당 고정 4회. 꺼져 있으면 선점 기록을 만들지 않는다.
- `NotificationType.MEDICATION_REMINDER(SETTINGS_ONLY, EnumSet.of(FCM))`.
- `GuardianNotificationPreferenceUsageGuardTest` 허용 목록에 플래너 추가(디스패처는 여전히 이 설정을 모른다).

## 바뀌지 않은 것

미복용 요약 동작, `MEDICATION_STOPPED`, 선점 후 발송·UNIQUE, 다른 알림 종류, 보호자 없는 피보호자 제외(MED-G01), 유예 창·재알림 시각.

## 배포 영향 (gosky dev, 2026-11-04 조회 - 개수만)

- 복약 중인 피보호자 4명. 켠 보호자 0명 = 1명(신고 건) → 복약 시각 알림이 멈춘다(의도).
- 문자 ON 2명 중 1명은 위 신고 건, 1명은 문자만 끊기고 푸시는 그대로 받는다.
- FCM 토큰이 없는 피보호자 2명은 지금도 푸시 실패였고 변화 없음(수용한 한계 - 문자를 켜지 않았으면 원래 받지 못했다).

## FE 전달

보호자 환경설정 "복약 알림" 설명 문구: "약 복용 체크가 안 된 날 알려 드려요" → "피보호자의 약 먹을 시간 알림과, 체크가 안 된 날 보호자 알림을 함께 켜고 꺼요" (안).
