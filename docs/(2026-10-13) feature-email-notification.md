# 이메일 알림 채널 (2026-10-13)

## 범위

알림 채널 추상화의 EMAIL 구현. 사용자가 설정에서 이메일을 켜면 **안내성 알림만** 계정 이메일로 발송한다.
긴급 알림(SOS·이상감지)과 반복 알림(복약 알림·재촉)은 이메일로 나가지 않는다.

## 결정 사항

| | 결정 |
|---|---|
| A | 허용 종류: `CONNECTION_REQUEST/ACCEPTED/REFUSED/DISCONNECTED/FORCED`, `INQUIRY_ANSWERED`, `MEDICATION_MISSED`. `MEDICATION_STOPPED`는 제외(보수적). |
| A-2 | `allowedChannels` 기본값을 "이메일을 뺀 전 채널"로 변경, 허용 종류만 `Email.ALLOWED` 명시. 새 종류는 자동으로 메일 제외. |
| B | `WARD_SOS`·`ANOMALY_DETECTED`·`_SELF` 제외(A-2로 자동). |
| C | 카카오 가상 이메일(`^kakao_\d+@kakao\.com$`)·빈 주소는 `NO_EMAIL` 실패로 기록. |
| D | 기본 OFF. 서버 DB 확인 결과 gosky에 `EMAIL=true` 4건(vkcs 0건)이 있어 V60이 OFF로 초기화. |
| E | push와 같은 제목·본문. 제목 `[실버브릿지] {title}`, 하단에 끄는 방법 안내. |
| F | `notification.email.enabled`(`NOTIFICATION_EMAIL_ENABLED`, 기본 false). |

## 변경 파일

- 신규: `EmailNotificationChannel`, `V60__reset_email_notification_setting.sql`, 테스트 3종
  (`EmailNotificationChannelTest`, `NotificationTypeEmailPolicyTest`, `NotificationDispatcherEmailTest`)
- 수정: `ChannelFailureReason`(NO_EMAIL), `NotificationType`, `NotificationChannelType`·`NotificationChannel`(javadoc),
  `NotificationSettingController`(Swagger), `application.yaml`, `LogRawExceptionGuardTest`, CLAUDE.md §1, domain-security-policy.md

## 바뀌지 않는 것

디스패처 로직, FCM·SMS·알림톡 동작, WS, 비밀번호 재설정 메일, 정지 계정 차단, 이상감지 문자 대체 로직.

## 배포·운영 순서

1. PR 머지(마이그레이션 V60 포함 - 머지 전 vkcs 통합 테스트) → vkcs CD, gosky 수동 배포.
2. 스위치는 꺼진 채로 배포된다. 켜기는 별도 확인 후 `.env.dev`에 `NOTIFICATION_EMAIL_ENABLED=true` 추가, `up -d api`(restart 아님).
3. FE 전달: 알림 이력의 실패 사유에 `NO_EMAIL` 추가(사유 코드 매핑이 있으면 반영). 카카오 가상 이메일 계정에는 이메일 토글을 숨기거나 안내 권장.

## 검증

- 단위 테스트: 채널(정상·주소 없음·가상 이메일·MailException 원문 미노출·제목/본문 형식), 허용 집합 고정, 디스패처(허용 종류 발송·복약 알림 제외·이상감지 제외·문자 대체 유지·SOS 미사용·이용 제한 차단·채널 실패 격리).
- `./gradlew build` 통과(전체 테스트 포함).
- 서버 DB 사전 확인(읽기 전용): `user_notification_setting` EMAIL 행 gosky 4(ON)·1(OFF), vkcs 없음.
