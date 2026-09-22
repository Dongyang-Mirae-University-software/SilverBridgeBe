# 관리자 알림 이력 (2026-09-22)

> 브랜치 `feature/admin-notification-history` · 마이그레이션 **V54** · 근거: Claude Design 프로토타입 최종본(2026-09-22)

## 1. 왜

알림이 실제로 전달됐는지, 실패했다면 왜인지를 관리자가 볼 곳이 없었다.

- 발송 결과는 서버 stdout 로그(`[NOTIFY-UNDELIVERED]` 등)에만 있어 조회할 수 없었다.
- 채널 `send()`가 `boolean`만 돌려줘 **실패 사유가 디스패처까지 오지 않았다**.
- 기존 `*_reminder_log`는 중복 발송을 막으려고 **보내기 전에** 적는 선점 기록이라 결과가 없다.

## 2. 범위

| 포함 | 제외 |
|---|---|
| 디스패처를 거치는 알림 13종 전부(SOS·이상감지·판정 요청·복약·연결·문의) | WebSocket 실시간 이벤트(디스패처 밖) |
| 채널별 시도 결과와 실패 사유(고정 코드) | 인증번호 문자(디스패처 미경유) |
| 관리자 목록·요약 API(조회 전용) | Solapi 최종 도달 결과(통신사 단계 실패) |
| 보관 정리 스케줄러(기본 90일) | 도입 이전 발송(소급 불가) |
| | 정서 상태 분석(AI·타 백엔드 영역) |

## 3. 설계

### 3-1. 채널 결과 코드

`NotificationChannel.send()` 반환형 `boolean` → `ChannelResult(status, reason)`.

| status | 뜻 | 이력 |
|---|---|---|
| `DELIVERED` | 발송 서버(FCM·Solapi)가 접수 | 기록 |
| `FAILED` | 시도했으나 실패, `reason` 필수 | 기록 |
| `NOT_APPLICABLE` | 원래 이 채널 대상이 아님(알림톡 템플릿 미매핑·알림톡 OFF·미구현 채널) | **기록 안 함** |

실패 사유(`ChannelFailureReason`) - **서버가 코드로 구분할 수 있는 것만** 둔다:

| 코드 | 화면 문구 | 판정 위치 |
|---|---|---|
| `NO_DEVICE` | 등록된 기기 없음 | `FcmService.sendToUser` 토큰 0개 |
| `ALL_TOKENS_EXPIRED` | 모든 기기의 알림 등록이 만료됨 | 전 토큰이 `UNREGISTERED`·`INVALID_ARGUMENT` (토큰은 이때 삭제) |
| `PUSH_SERVER_ERROR` | 푸시 서버 오류 | FCM 예외, 또는 만료가 아닌 사유가 섞여 전부 실패 |
| `NO_PHONE` | 등록된 전화번호 없음 | 문자·알림톡 |
| `PROVIDER_REJECTED` | 발송사가 접수를 거부함 | Solapi `SolapiMessageNotReceivedException` |
| `PROVIDER_ERROR` | 발송 서버 오류 | Solapi 빈 응답·알 수 없는 오류 |
| `UNEXPECTED_ERROR` | 처리 중 오류 | 채널이 예외를 던지거나 null을 반환(디스패처가 격리) |

- **예외 원문은 저장하지 않는다** - 토큰·전화번호가 섞일 수 있다.
- "기기가 응답하지 않음" 같은 사유는 서버가 알 수 없다(FCM·Solapi는 접수 여부까지만 알려준다).
- `SmsSender`는 인증번호와 공용이라 반환형을 바꾸지 않고 `trySend()`(결과 반환)를 추가했다. 기존 `send()`는 `trySend()`를 호출해 실패 시 **그대로 `SMS_SEND_FAILED` 예외**를 던진다(auth 동작 불변).
- 알림톡 채널은 **템플릿 검사를 전화번호 검사보다 앞으로** 옮겼다. 대상이 아닌 알림(복약 등)을 번호가 없다는 이유로 "실패"로 기록하지 않기 위해서다. 발송 동작은 같다.

### 3-2. 결과 판정 (수신자 1명 = 1행)

| 결과 | 조건 |
|---|---|
| `DELIVERED` | 한 채널 이상 접수 |
| `SMS_FALLBACK` | SOS 푸시 실패 → 문자 접수(**전달된 것으로 센다**) |
| `FAILED` | 시도한 채널 전부 실패 |
| `NOT_SENT` + `RESTRICTED_ACCOUNT` | 수신자 이용 제한 |
| `NOT_SENT` + `WITHDRAWING_ACCOUNT` | 수신자 탈퇴 처리 중 |
| `NOT_SENT` + `NO_ENABLED_CHANNEL` | 설정 기반 알림인데 켠 채널이 없거나 모두 대상 아님 |

- 이상감지에서 푸시 실패 + 켜 둔 문자 접수는 `SMS_FALLBACK`이 아니라 `DELIVERED`다 - 폴백이 아니라 원래 보낼 채널이다.
- `NOT_SENT`는 실패가 아니다. 요약 카드에서도 실패와 따로 센다.

### 3-3. 기록 지점 = `NotificationDispatcher` 한 곳

- 발송이 **끝난 뒤** 한 번 기록한다. 기록 실패는 삼키고 `[NOTIFY-LOG-FAILED]` WARN(userId·type·result·예외 클래스명만, 본문·예외 메시지 없음).
- `NotificationLogService.record()`는 **`REQUIRES_NEW`** - 디스패처는 AFTER_COMMIT 리스너 안에서도 불린다(`MedicationWithdrawalListener`는 동기 AFTER_COMMIT). 기본 전파로 저장하면 이미 커밋된 트랜잭션에 합류해 **기록이 조용히 사라진다**.
- 관련 피보호자는 `dispatch(userId, wardId, type, content)`로 받는다(표시 전용). `content.data()["wardId"]`는 FE 계약 값이라 근거로 쓰지 않는다.
  - 넘기는 곳: SOS·이상감지(보호자·본인)·판정 재촉·동수 안내·복약 3종·연결 요청·강제 연결(양쪽)
  - null(피보호자 특정 불가): 연결 수락·거절·해제(이벤트에 wardId 없음), 문의 답변, 판정 요약(여러 피보호자)

**기존 동작 보존** - 강제 FCM 보장, 정지 수신자 차단, 종류별 허용 채널 교집합, SOS 결과 기반 SMS 폴백, 설정 채널 0건이면 수신자 조회 생략, 채널 실패 격리, `[NOTIFY-BLOCKED]`·`[NOTIFY-UNDELIVERED]` 로그 모두 그대로다. 로그 추가: SOS 푸시 미전달 시 사유 WARN, 폴백 실패 시 사유 WARN.

### 3-4. 스키마 (V54 `notification_log`)

```
id · created_at · type(NotificationType, CHECK 없음) · recipient_id FK CASCADE · ward_id FK CASCADE NULL
title · body · result(CHECK 4값) · not_sent_reason(CHECK 3값) · channel_results JSONB
CHECK (result = 'NOT_SENT') = (not_sent_reason IS NOT NULL)
인덱스: created_at DESC / (result, created_at DESC) / recipient_id / ward_id
```

- `type`에 CHECK를 걸지 않은 이유: 알림 종류는 계속 늘어나 값 추가마다 재정의 마이그레이션이 필요해진다.
- `result`·`not_sent_reason`은 이력 전용이라 CHECK를 걸고 `NotificationLogCheckSyncTest`로 enum과 동기화를 고정했다. 이 테이블은 기록 실패를 삼키므로 CHECK 불일치가 500이 아니라 **이력이 조용히 빠지는** 형태로 나타나 더 늦게 발견된다.
- 탈퇴 시 CASCADE - "탈퇴자 데이터를 붙들지 않는다"(이상감지 이력과 같은 판단).

## 4. API (조회 전용, ADMIN, 감사 로그 미기록)

### `GET /api/admin/notification`

| 파라미터 | 값 | 기본 |
|---|---|---|
| `category` | `SOS` · `ANOMALY` · `MEDICATION` · `OTHER`(판정 요청·연결·문의 = 나머지 전부) | 전체 |
| `result` | `DELIVERED` · `SMS_FALLBACK` · `FAILED` · `NOT_SENT` ("전송 실패" 탭 = `FAILED`) | 전체 |
| `period` | `TODAY` · `LAST_7_DAYS` · `LAST_30_DAYS` (KST, 오늘 포함) | `LAST_7_DAYS` |
| `keyword` | 피보호자 또는 수신자 이름 부분일치 | - |
| `page`·`size` | size 최대 50 | 0 · 20 |

잘못된 enum 값은 400(빈 목록으로 오독되지 않게). `ALL` 기간이 없는 이유: 보관 기간이 지나면 지워져 "전체"가 곧 "최근 90일"이라 뜻이 흐려진다.

항목 필드: `id` · `sentAt` · `type` · `typeLabel` · `category` · `wardId` · `wardName` · `title` · `body` · `recipientId` · `recipientName` · `recipientRole` · `recipientIsWard`(화면 "본인") · `relation`(현재 ACTIVE 연결 라벨, 없으면 null) · `deliveredChannels`(**전송 완료된 채널만**, 비면 "-") · `result` · `notSentReason` · `notSentReasonLabel` · `channelResults[{channel, status, reason, reasonLabel}]`(팝업용).

### `GET /api/admin/notification/summary`

`category`·`period`·`keyword`(result 없음) → `total` · `delivered`(문자 대체 포함) · `smsFallback` · `failed` · `notSent`. `total = delivered + failed + notSent`.

### 화면 매핑

| 화면 | 필드 |
|---|---|
| 카드 "전송 완료 7건 / 문자 대체 발송 1건 포함" | `delivered` / `smsFallback` |
| 카드 "발송 안 함" | `notSent` |
| 표 "채널" | `deliveredChannels` |
| "발송 안 함" 뱃지 아래 사유 | `notSentReasonLabel` |
| 실패·대체 사유 팝업 | `channelResults` 중 `status=FAILED`의 `channel` + `reasonLabel` |
| 팝업 하단 안내 | 고정 문구 "전송 완료는 발송 서버가 접수한 시점 기준입니다" |

## 5. 보관

`NotificationLogCleanupScheduler` 매일 04:30 KST(04:00 FCM 토큰 정리와 겹치지 않게). `notification.log.retention-days`(기본 90, env `NOTIFICATION_LOG_RETENTION_DAYS`) · 킬 스위치 `notification.log.cleanup-enabled`(env `NOTIFICATION_LOG_CLEANUP_ENABLED`). 실패는 삼키고 다음 날 재시도.

## 6. 변경 파일

**신규** (notification 도메인)
- `channel/ChannelResult` · `channel/ChannelFailureReason`
- `entity/NotificationLog` · `NotificationLogResult` · `NotificationNotSentReason` · `ChannelAttempt`
- `repository/NotificationLogRepository`
- `service/NotificationLogService` · `AdminNotificationService` · `NotificationLogCleanupScheduler`
- `config/NotificationLogProperties`
- `controller/AdminNotificationController`
- `dto/AdminNotificationItem` · `AdminNotificationSummaryResponse` · `AdminNotificationCategory` · `AdminNotificationPeriod` · `NotificationTypeLabel`
- `db/migration/V54__create_notification_log.sql`

**수정**
- 계약: `NotificationChannel` · `FcmNotificationChannel` · `SmsNotificationChannel` · `KakaoAlimtalkNotificationChannel` · `FcmService` · `AlimtalkSender` · `auth/SmsSender`(`trySend` 추가)
- `NotificationDispatcher`(결과 수집·기록, 4인자 `dispatch`, null 결과 방어)
- 호출처(wardId 전달): `AnomalyNotificationListener` · `AnomalyReviewReminderService` · `ConnectionNotificationListener` · `MedicationWithdrawalListener` · `MedicationReminderService` · `MedicationMissedAlertService` · `SosNotificationListener`
- `ConnectionRepository.findByGuardianIdInAndWardIdInAndStatus`(관계 라벨 표시 전용 - 인가 근거로 쓰지 말 것)
- `application.yaml`(`notification.log.*`)

## 7. 테스트

- 단위(`./gradlew test`): **653건, 실패 0, 건너뜀 1(기존)**.
  - 신규: `NotificationDispatcherTest` 이력 12건(결과 4종·사유·SOS 대체·이상감지는 폴백 아님·기록 실패 무영향·null 결과) / `FcmServiceTest` 사유 판정(만료·혼합·예외·토큰 없음) / `SmsNotificationChannelTest` 접수 거부·오류 구분 / `KakaoAlimtalkNotificationChannelTest` 대상 아님 vs 실패 / `AdminNotificationServiceTest` 7건 / `AdminNotificationControllerSecurityTest`(GUARDIAN·WARD 403, 쓰기 매핑 없음) / `AdminNotificationFilterTest`(카테고리 전수 분할·KST 경계) / `NotificationLogCheckSyncTest` / `NotificationLogCleanupSchedulerTest`
  - 호출처 테스트 7개 파일의 `dispatch` 검증을 4인자로 갱신. 3인자 `never()` 검증은 그대로 두면 **항상 통과해 검증력이 사라지므로** 전부 바꿨다. SOS·이상감지·연결 요청은 `eq(WARD_ID)`로 실제 전달값까지 검증.
- `./gradlew build -x test`: 통과.
- 통합(`NotificationLogIntegrationTest`, JSONB 왕복·목록/요약 JPQL·CHECK 3종·CASCADE·보관 정리): **컴파일 확인, 실행은 vkcs에서 브랜치 push 후** `~/SilverBridgeBe/tools/integration-test.sh feature/admin-notification-history`. 마이그레이션·JPQL 변경이라 머지 전 필수.

## 8. 알려진 한계

- `relation`은 조회 시점의 ACTIVE 연결 라벨이다 - 연결이 끝난 과거 이력에는 비어 보인다(발송 당시 값을 저장하지 않음).
- 연결 수락·거절·해제 알림은 피보호자 칸이 비어 있다(이벤트에 wardId가 없음).
- 문자·알림톡 "전송 완료"는 Solapi 접수 기준이다. 결번 등 통신사 단계 실패는 잡히지 않는다.
- 수신자 사용자 행이 없으면(상태 불명 - 디스패처는 이때도 발송한다) FK 때문에 기록이 실패한다(`[NOTIFY-LOG-FAILED]` WARN, 발송은 영향 없음).
