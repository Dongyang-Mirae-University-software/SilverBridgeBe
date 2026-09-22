# 영향 범위 점검 - 알림 채널 결과 계약·발송 이력 (템플릿 C, 2026-09-22)

> 대상: PR #257 (`654620c`, 머지 `06623fe`) · V54 · 점검만(코드 수정 없음)
> 기능 문서: `docs/(2026-09-22) feature-admin-notification-history.md`

## PHASE -1. 환경

| 항목 | 결과 |
|---|---|
| 기준 커밋 | `06623fe` (origin/dev) |
| 마이그레이션 | V54 (vkcs 적용 확인: `Successfully applied 1 migration ... now at version v54`) |
| 빌드·테스트 | 같은 커밋에서 단위 653 / 0 실패, CD 배포 전 통합 테스트 통과, vkcs `healthy` |

## PHASE 0. 변경점과 불변식

**바뀐 것**
1. `NotificationChannel.send()` 반환 `boolean` → `ChannelResult(status, reason)` (+ `NOT_APPLICABLE`)
2. `FcmService.sendToUser`·`AlimtalkSender.send` 반환형, `SmsSender.trySend()` 추가
3. `NotificationDispatcher` - 결과 수집 후 `notification_log` 기록(`REQUIRES_NEW`), 4인자 `dispatch`, null 결과 방어
4. 알림톡 채널 - 템플릿 검사를 전화번호 검사 앞으로

**불변식**
- ① 발송 동작(누구에게·어느 채널로·폴백 여부)은 이력 기록과 무관하게 변경 전과 같다.
- ② 이력의 "실패"는 실제로 시도해 실패한 것뿐이다(대상 아님·차단·채널 꺼 둠은 실패가 아니다).
- ③ 이력 기록은 발송을 막지도 늦추지도 않는다.

## PHASE A. 사용처 전수

| 대상 | 사용처 | 비고 |
|---|---|---|
| `NotificationChannel` 구현체 | FCM · SMS · KAKAO_ALIMTALK (3) | EMAIL 구현체 없음 |
| `channel.send()` 호출 | `NotificationDispatcher`만 | 디스패처 밖 직접 호출 0 |
| `FcmService.sendToUser` | `FcmNotificationChannel`만 | |
| `AlimtalkSender.send` | `KakaoAlimtalkNotificationChannel`만 | |
| `SmsSender` | `SmsNotificationChannel`(`trySend`) · `SmsVerificationService`(`send`, 인증번호) | 공용 |
| `dispatch()` | 14곳 - 4인자 9 / 3인자 5 | 아래 표 |

`dispatch()` 호출처와 실행 맥락:

| 호출처 | 인자 | wardId | 실행 맥락 |
|---|---|---|---|
| `SosNotificationListener` | 4 | `event.wardId()` | `@Async` AFTER_COMMIT |
| `AnomalyNotificationListener` (보호자·본인) | 4 | `event.wardId()` | `@Async` AFTER_COMMIT |
| `AnomalyReviewReminderService` 건별·동수 | 4 | `target.wardId()` | 스케줄러, 트랜잭션 없음 |
| `AnomalyReviewReminderService` 요약 | 3 | - (여러 피보호자) | 〃 |
| `MedicationReminderService` | 4 | 수신자 = 피보호자 | 스케줄러, Planner 커밋 후 |
| `MedicationMissedAlertService` | 4 | `target.wardId()` | 〃 |
| `MedicationWithdrawalListener` | 4 | `wardId` | **동기 AFTER_COMMIT** (탈퇴 purge 전) |
| `ConnectionNotificationListener` 요청·강제(양쪽) | 4 | `event.wardId()` | `@Async` AFTER_COMMIT |
| 〃 수락·거절·해제 | 3 | - (이벤트에 wardId 없음) | 〃 |
| `InquiryNotificationListener` | 3 | - | 〃 |

## PHASE B. 사용처별 판정

| # | 확인 항목 | 위치 | 판정 | 근거 |
|---|---|---|---|---|
| B-1 | SOS 결과 기반 SMS 폴백 조건 | `NotificationDispatcher.dispatchMandatory` | PASS | 푸시 `isDelivered()`가 false면 폴백 - 토큰 없음·전 토큰 만료·예외·null 모두 false로 수렴(변경 전 boolean false와 동일). 푸시 채널 빈이 없을 때도 폴백으로 진행 |
| B-2 | 강제 FCM이 설정·허용 채널로 줄지 않음 | `dispatchForcedPushPlusSettings` | PASS | `EnumSet.of(FORCED_PUSH)` + 설정 채널, 교집합은 설정 집합에만 |
| B-3 | 정지·탈퇴 진행 수신자 차단(강제 포함) | `withReceivableRecipient` | PASS | 세 정책 모두 이 경로. 차단 시 `NOT_SENT` 기록만 하고 채널 호출 0 |
| B-4 | 설정 채널 0건이면 수신자 조회 생략 | `dispatchBySettings` | PASS | 채널 계산 → 조회 순서 유지. 단 이제 `NOT_SENT` 기록 INSERT가 1건 생긴다(L-1) |
| B-5 | 인증번호 문자 동작 | `SmsSender.send` → `trySend` | PASS | 실패 시 같은 로그 + `SMS_SEND_FAILED` 예외. Solapi 외 런타임 예외는 변경 전처럼 전파 |
| B-6 | 문자 채널 실패의 전달 방식 | `SmsNotificationChannel` | PASS | 변경 전: 예외 → 디스패처 catch. 변경 후: 결과값. 발송·폴백 판단 동일, ERROR 스택 로그만 사라지고 `SmsSender`의 ERROR는 남음 |
| B-7 | 알림톡 OFF 서버에서 가짜 실패 | `AlimtalkProperties.templateFor` | PASS | `enabled=false`면 null → `NOT_APPLICABLE`(기록 안 함). 로컬 기본값 OFF에서도 실패가 쌓이지 않음 |
| B-8 | 알림톡 검사 순서 변경 | `KakaoAlimtalkNotificationChannel` | PASS | 번호 없음·템플릿 없음 모두 미발송이던 것 그대로. 대상 아닌 종류가 `NO_PHONE` 실패로 기록되지 않게 됨 |
| B-9 | AFTER_COMMIT 동기 경로 기록 | `MedicationWithdrawalListener` → `NotificationLogService.record` | PASS(설계) / **실서버 미확인** | `REQUIRES_NEW`라 커밋된 트랜잭션에 합류하지 않음. 수신자는 남은 보호자라 purge CASCADE 대상 아님. 테스트는 mock이라 실제 커밋은 미검증(M-1) |
| B-10 | 트랜잭션 안에서 기록 → 커넥션 2개 | 전 호출처 | PASS | 비동기 리스너·스케줄러 경로는 바깥 트랜잭션 없음(Planner는 선점 커밋 후 반환). 동기 AFTER_COMMIT 1곳만 탈퇴 시 순간 2개 |
| B-11 | 기록 실패가 발송을 막는가 | `NotificationDispatcher.record` | PASS | 발송 후 호출, `RuntimeException` 삼킴 + WARN(본문·예외 메시지 없음). 테스트 `이력_기록실패_발송무영향` |
| B-12 | wardId 누락 호출처 | 3인자 5곳 | PASS(설계) | 모두 피보호자를 특정할 수 없는 경로(L-3) |
| B-13 | 수신자·피보호자 행 부재 시 | FK | PASS(수용) | 상태 불명 수신자는 발송되고 기록만 FK로 실패 → WARN. 발송 무영향 |
| B-14 | 관리자 인가 | `/api/admin/**` + 클래스 `@PreAuthorize` | PASS | 이중 게이트, GUARDIAN·WARD 403·쓰기 매핑 없음 테스트 |
| B-15 | PII | 이력 본문·로그 | PASS | 본문은 DB에만, `[NOTIFY-LOG-FAILED]`는 userId·type·result·예외 클래스명만. 사유는 고정 코드 |
| B-16 | 운영 스케줄러 | `@Scheduled` 6개 / 풀 3 | PASS(수용) | 추가된 것은 04:30 일일 단건 DELETE(L-2) |
| B-17 | 호출처 테스트 검증력 | 테스트 7개 파일 | PASS | 3인자 `never()`를 4인자로 전부 전환. 3인자로 남은 검증(수락·거절·해제·문의)은 호출도 3인자라 유효 |

**실서버 확인(2026-09-22 17:55 KST, vkcs)**: 배포 후 20분간 `NOTIFY-LOG-FAILED`·`ERROR` 0건. 다만 그 사이 발송된 알림이 없어 `notification_log`가 **0건**이라 기록 자체는 아직 확인하지 못했다(M-1).

## PHASE C. 테스트로 고정할 수 있는가

- 이미 고정: enum ↔ CHECK(`NotificationLogCheckSyncTest`), 카테고리 전수 분할(`AdminNotificationFilterTest`), 종류 라벨 누락(`NotificationTypeLabel` switch 전수 - 컴파일 오류), 쓰기 매핑 없음.
- 제안(L-4): `NotificationType` 전 값 × 수신자 상태(ACTIVE·RESTRICTED)로 "`record()`가 정확히 1번 호출된다"를 파라미터화 테스트로 고정 - 새 종류·정책이 기록을 빠뜨리지 못하게.
- AFTER_COMMIT + `REQUIRES_NEW` 실제 커밋은 `@SpringBootTest` 수준이 필요해 비용이 크다 - 실서버 확인(M-1)으로 대신한다.

## 이슈

| 등급 | ID | 내용 | 조치 |
|---|---|---|---|
| 🔴 | - | 없음 | |
| 🟠 | - | 없음 | |
| 🟡 | M-1 | 실서버 기록 미확인 - vkcs `notification_log` 0건(배포 후 발송 없음). 특히 동기 AFTER_COMMIT(`MedicationWithdrawalListener`) 경로의 `REQUIRES_NEW` 커밋은 테스트로 검증되지 않았다 | 다음 실제 발송 뒤 `SELECT type, result, channel_results FROM notification_log ORDER BY id DESC LIMIT 5` + `[NOTIFY-LOG-FAILED]` 로그 0건 확인. dev에서 연결 요청 한 번이면 SETTINGS_ONLY 경로가 확인된다 |
| 🟢 | L-1 | 설정 채널 0건 경로가 이제 INSERT 1건을 한다(이전엔 DB 접근 0). 복약 알림 등 1분 주기 경로에서 `NOT_SENT` 행이 쌓인다 | 수용 - 보관 90일, 알림 ON인 복약만 대상이라 양이 작다. 기능 문서에 명시됨 |
| 🟢 | L-2 | `@Scheduled` 6개에 풀 3(정책 B-1 "스케줄러를 추가하면 다시 본다") | 수용 - 04:30 일일 단건 DELETE, 04:00 FCM 정리와 겹치지 않음 |
| 🟢 | L-3 | 연결 수락·거절·해제 이력의 피보호자 칸 공란(이벤트에 wardId 없음) | 필요 시 이벤트에 wardId 추가 - 이상감지 E-3 선행 작업(`ConnectionDisconnectedEvent` 확장)과 같은 변경이라 함께 |
| 🟢 | L-4 | 새 알림 종류가 기록을 빠뜨리지 않게 하는 기계적 가드 없음(현재는 모든 정책이 한 `record()`를 지나 구조적으로 안전) | 파라미터화 테스트 추가 제안 |

## 종합 판정

⚠️ **PASS(잔여 확인 1건)** - 공용 계약 변경이 다른 도메인의 발송 동작을 바꾸지 않았다(불변식 ①②③ 충족). 실서버에서 이력이 실제로 쌓이는지만 첫 발송 후 확인하면 된다(M-1).
