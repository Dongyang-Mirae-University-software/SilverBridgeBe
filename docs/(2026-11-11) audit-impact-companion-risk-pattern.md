# 영향 범위 점검 - `NotificationType.COMPANION_RISK_PATTERN` 추가 (2026-11-11, 템플릿 C)

- 변경: BE #469 `47e8569f` (말벗 여러 날 위험 누적 알림). 기준 커밋 `ee8fbc34`.
- 범위: 새 알림 종류를 쓰거나 종류 목록·분기를 가진 전 도메인 코드(`domain/**`·`global/**`)와 FE 서비스 워커(`SilverBridgeFe` origin/develop `5e55922`, 읽기만). 점검만 했고 코드는 고치지 않았다.

## 불변식

> `COMPANION_RISK_PATTERN`은 모든 공용 경로에서 `COMPANION_RISK`와 똑같이 다뤄진다(강제 FCM + 대체 문자, 긴급, 필수, URGENT, 정지 수신자 차단). 문구·로그·이력에는 원문과 확률이 없다.

## 사용처별 판정

| 영역 | 위치 | 판정 | 근거 |
|---|---|---|---|
| 채널 정책 | `NotificationType` | PASS | `FORCED_PUSH_PLUS_SETTINGS`·FCM·SMS, `COMPANION_RISK`와 같음(`NotificationDispatcherTest` 정책 가드) |
| 이메일 제외 | `NotificationType.allowedChannels` | PASS | `Email.ALLOWED` 없음 → 메일로 나가지 않음 |
| 알림톡 제외 | `KakaoAlimtalkNotificationChannel`·yaml `templates` | PASS | 매핑 없음 → 건너뜀(승인 템플릿 없음) |
| 대체 문자 시간당 상한 | `SmsFallbackLimiter` | PASS | 키가 `type.name()`이라 위험 알림과 따로 30건 |
| 정지·탈퇴 진행 수신자 차단 | `NotificationDispatcher.dispatch` | PASS | 전 종류 × 수신자 상태 가드가 새 종류도 포함 |
| 설정 조회 실패 격리 | `NotificationDispatcher` | PASS | 종류와 무관하게 강제 FCM 유지 |
| 웹푸시 Urgency high | `FcmMessageProperties`·yaml 기본 목록 | PASS | 12종에 포함(`FcmServiceMessageTest`) |
| 사용자 알림 기록 묶음 | `UserNotificationCategory` | PASS | URGENT(default 없는 switch라 컴파일이 누락을 막음) |
| 관리자 이력 탭 | `AdminNotificationCategory` | PASS | 여집합 OTHER(`COMPANION_RISK`와 같은 탭) |
| 관리자 종류 이름 | `NotificationTypeLabel` | PASS | "안부 말벗" |
| 보호자 알림 종류 설정 | `GuardianNotificationTypeSettingController` | PASS | 따로 항목 없음, `companionRisk`(필수)에 포함 - Swagger 설명 갱신됨 |
| 관리자 말벗 집계 | `CompanionStatsService` | PASS | 오늘 발송 수에 포함(사용자 결정) |
| 역할 변경·탈퇴 정리 | `CompanionWithdrawalListener`·V70 FK | PASS | 역할 변경 때 지움, 탈퇴는 CASCADE |
| 원문 로그 금지 | `LogRawExceptionGuardTest` | PASS | 새 리스너 포함, 로그는 ID·일수·예외 클래스명뿐 |
| 동시성(7일 1번) | `CompanionRiskPatternNoticeRepository.claim` | PASS | 조건부 UPSERT 한 문장, 실 DB 동시 6회 → 1건 |
| **수신 가능 보호자만 선점** | `CompanionRiskPatternListener.handleCheck` | **FAIL (M-1)** | 아래 |
| executor 배정 고정 | `NotificationExecutorAssignmentTest` | FAIL (L-2) | 아래 |
| FE 표시·이동 | `public/push-sw-logic.js` | 부분 (L-4, FE 몫) | 아래 |

## 이슈

### 🟡 M-1 이용 제한·탈퇴 진행 보호자도 대상으로 세어 7일 선점을 소진한다

- 위치: `CompanionRiskPatternListener.handleCheck` - `connectionService.getActiveGuardianIds`는 연결 상태만 본다. 계정이 `RESTRICTED`·탈퇴 진행인 보호자도 `targets`에 들어간다.
- 결과: 수신 가능한 보호자가 정지 계정뿐이면 선점(`last_notified_date = 오늘`)은 되는데 디스패처가 모두 막는다(`NOT_SENT`). 7일 안에 제한이 풀리거나 다른 보호자가 연결돼도 그 기간에는 다시 판정되지 않는다. "받을 보호자가 없으면 선점하지 않는다"는 이 기능 규칙과 어긋난다.
- 비교: 이상감지 재촉(`AnomalyReviewReminderPlanner.activeGuardiansByWard`, 10/31 L-12)과 복약 미복용 요약(`MedicationMissedAlertPlanner`)은 `userRepository.findIdsByIdInAndStatus(..., ACTIVE)`로 선점 전에 거른다.
- 같은 틈이 기존 주의 알림(`CompanionNoticeListener`)에도 있다. 다만 그쪽 선점은 하루 단위라 다음 날 다시 판정된다(🟢로 함께 고칠 것을 권함).
- 수정안: 두 리스너 모두 `targets`를 만들기 전에 ACTIVE 계정만 남긴다(재촉과 같은 쿼리). 디스패처의 차단은 그대로 둔다(2중 방어).
- 테스트: 정지 보호자만 있으면 `claim`을 부르지 않는다 / 정지 + 이용 중 보호자면 이용 중 보호자만 대상(리스너 단위 테스트).

### 🟢 L-1 같은 턴에서 위험 알림과 누적 알림이 함께 나갈 수 있다 (결정과 일치 - 확인만)

- 0.995 같은 턴은 `riskFlag` 위험 알림을 받고, 지난 6일 안에 0.97 이상인 날(위험 알림 없음)이 있으면 같은 순간 누적 알림도 나간다. 보호자는 몇 초 사이에 두 건(푸시 2 + 대체 문자 최대 2)을 받는다.
- 사용자 결정("위험 알림이 안 나간 날 1일 이상")의 의도된 결과다. 줄이려면 "판정을 부른 턴이 이번에 위험 알림을 받았으면 그 판정은 건너뛴다"를 더하는 방법이 있다(그 경우 다음 높은 턴에서 다시 판정된다). **결정이 필요하다.**

### 🟢 L-2 새 리스너의 긴급 풀 배정이 테스트로 고정돼 있지 않다

- `NotificationExecutorAssignmentTest`는 `CompanionRiskNotificationListener`(URGENT)·`CompanionNoticeListener`(일반)를 고정하지만 `CompanionRiskPatternListener`는 없다. 정책("새 `@Async` 리스너는 executor 이름을 명시", 긴급 리스너를 일반 풀로 되돌리지 말 것)을 지키는지 기계적으로 막히지 않는다.
- 수정안: `asyncQualifiers(CompanionRiskPatternListener.class)).containsExactly(URGENT)` 한 줄.

### 🟢 L-3 발송이 모두 실패해도 7일 동안 다시 보내지 않는다 (수용 권장)

- 선점 후 발송이라 FCM·문자가 모두 실패해도 선점은 남는다. `COMPANION_RISK`(대화당 `risk_notified_at`)·주의 알림과 같은 방식이고, 이력에는 `FAILED`로 남는다. 위험 알림 계열에서 일관돼 있어 현행 유지를 권한다.

### 🟢 L-4 FE가 새 종류를 따로 다루지 않는다 (FE 몫 - 보고만)

- 문구: data의 `title`·`body`를 먼저 쓰므로 BE 문구 그대로 보인다(PASS).
- 누르면 기본 경로 `/guardian`으로 간다. `COMPANION_RISK`·`COMPANION_NOTICE`는 `/guardian/emotion`이다.
- `renotify`(같은 태그 알림을 소리와 함께 다시 알림) 대상이 아니다. 태그는 `COMPANION_RISK_PATTERN:<wardId>`이고 7일에 1번이라 영향은 작다.
- FE 전달 문서는 만들지 않았다(팀이 직접 작성).

### 🟢 L-5 스위치를 켜면 시험 계정 대화도 실제 강제 푸시·문자로 이어진다 (운영 메모)

- 테스트 대화도 똑같이 센다는 정책대로다. gosky에서 켤 때는 시험 피보호자가 같은 시간대를 반복해 높은 대답을 내면 연결된 시험 보호자에게 대체 문자(과금)가 갈 수 있음을 알고 켠다. 지금은 두 서버 모두 꺼져 있다.

## 테스트로 고정할 것 (PHASE C)

1. M-1 수정과 함께: 정지 보호자만 있으면 선점하지 않는 리스너 테스트(누적·주의 알림 둘 다).
2. L-2: `NotificationExecutorAssignmentTest`에 한 줄.
3. 종류 짝 맞춤 가드: `COMPANION_RISK_PATTERN`이 `COMPANION_RISK`와 정책·허용 채널·사용자 묶음·웹푸시 high·알림톡 템플릿 없음이 같은지 한 테스트에서 비교한다(지금은 가드 4곳에 흩어져 있다).

## 조치 (2026-11-11, 사용자 "이슈 전부 반영해줘")

| 이슈 | 조치 |
|---|---|
| M-1 | `CompanionRiskPatternListener`·`CompanionNoticeListener`가 선점 전에 계정 ACTIVE 보호자만 남긴다. 테스트: 정지 보호자만이면 선점 없음, 섞이면 이용 중 보호자만(두 리스너) |
| L-1 | 제안안 채택 - `CompanionSessionWriter.recordTurn`이 그 턴의 위험 알림 선점에 이겼으면 누적 판정 이벤트를 내지 않는다. 테스트: 이긴 턴은 건너뜀, 진 턴(이미 보낸 대화)은 판정 |
| L-2 | `NotificationExecutorAssignmentTest`에 긴급 풀 고정 |
| C-3 | `CompanionRiskTypeParityTest` 추가 |
| L-3 | 현행 유지(수용), 리스너 주석·정책 문서에 기록 |
| L-4 | FE PR로 이동 경로 `/guardian/emotion`·다시 알림 대상 추가 |
| L-5 | 정책 문서에 운영 메모 기록 |

## 종합

> **조치 후 (2026-11-11)**: BE #470 `1ac99321`·FE #245 `c5099a8`로 반영, 대장 상태 ✅.

⚠️ - 공용 알림 경로(디스패처·채널·이력·묶음·우선순위)는 모두 불변식을 지킨다. 남은 것은 리스너의 선점 대상 계산(M-1)과 테스트 고정(L-2)이다. 스위치가 꺼져 있어 지금 운영 영향은 없다. **켜기 전에 M-1을 고칠 것을 권한다.**

## 수정용 커밋 메시지 초안

```
fix: 말벗 누적·주의 알림이 이용 제한 보호자만 있을 때 선점하지 않게 - 계정 ACTIVE 보호자만 대상

- CompanionRiskPatternListener·CompanionNoticeListener: 선점 전 findIdsByIdInAndStatus(ACTIVE)로 거름(이상감지 재촉 L-12와 같음)
- NotificationExecutorAssignmentTest: CompanionRiskPatternListener 긴급 풀 고정
- 종류 짝 맞춤 가드: COMPANION_RISK_PATTERN ↔ COMPANION_RISK
```
