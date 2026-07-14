# 이상감지 알림 2단계 — 보호자·본인 발송 (FCM 고정 + SMS 선택)

> 작업 2026-07-14 · 대상 저장소 SilverBridgeBe · 상태 **구현 완료(PR 대기)**
> 설계: `docs/(2026-07-13) design-anomaly-notification.md` (§-1 정정본 기준)
> 선행: 1단계(수신·판정·이력, 2026-07-13) — `AiLiveStreamSubscriber` → `AnomalyJudge` → `anomaly_event`

---

## 1. 구현 범위

**이번 PR**: 이력이 적재된 이상감지를 **연결된 보호자 전원 + 피보호자 본인**에게 발송한다.

| | 이번 PR |
|---|---|
| 판정·이력 (1단계) | 변경 없음 |
| 알림 이벤트 발행 + AFTER_COMMIT 리스너 | ✅ 신규 |
| 채널 정책(`NotificationType.Policy`) + 디스패처 3분기 | ✅ 신규 |
| 알림 쿨다운(보호자 5분 / 본인 1분) | ✅ 신규 |
| **카카오 알림톡** | ❌ **2차 PR**(템플릿 심사·발신 프로필 선행) |
| 마이그레이션 | **없음** (V31이 최신, `anomaly_event` 스키마 그대로) |

## 2. 정책 (설계 §7 결정)

| # | 결정 | 구현 |
|---|---|---|
| D-1 | **피보호자 본인에게도 발송** | 수신자 = `getActiveGuardianIds(wardId)` + `wardId`. 본인 문구는 대피 안내 포함, 쿨다운 **1분**(보호자 5분) |
| D-2 | **FCM 전달 실패 시 SMS 강제 폴백 없음** | 문자는 사용자 선택(과금·수신 동의) — 폴백은 그 선택을 뒤집는다. 미전달은 `[NOTIFY-UNDELIVERED]` WARN |
| D-3 | 문구는 **"감지되었습니다"** | 시니어 대상 완곡어법 금지. 오탐률 확인 후 재검토 |
| D-4 | 알림톡 2차 분리 | enum 값만 존재 → 사용자가 켜도 디스패처가 조용히 스킵(기존 동작) |
| 트리거 | **DANGER 모드 유지** | AI 팀 합의(2026-07-14): 라이브 경로에서 **`confidence >= 0.6` → `danger=true`** 로 채운다. 배포되면 DANGER 모드가 곧바로 실동작 |

### 채널 정책 — `NotificationType.Policy`

```
SETTINGS_ONLY               사용자 설정 활성 채널로만            (연결 4종·문의 답변)
FORCED_PUSH_WITH_SMS_FALLBACK  FCM 강제 + 미전달 시 SMS 폴백     (WARD_SOS — 기존 동작 그대로)
FORCED_PUSH_PLUS_SETTINGS   FCM 항상 + 나머지는 설정대로, 폴백 X (ANOMALY_DETECTED — 신규)
```

`FORCED_PUSH_PLUS_SETTINGS` 대상 = `{FCM} ∪ 사용자 활성 채널`. FCM은 사용자가 꺼도 발송하고, SMS·알림톡은 켠 경우에만 추가된다.

> **기존 6타입 런타임 동작은 100% 보존**된다. `WARD_SOS`의 결과 기반 SMS 폴백(M-S2-1) 로직은 한 줄도 바뀌지 않았고, 연결·문의 알림은 그대로 사용자 설정을 따른다. 바뀐 건 분류를 표현하는 방식(`isMandatory()` → `policy()`)뿐이다.

## 3. 변경 파일

**신규**
- `domain/anomaly/event/AnomalyDetectedEvent.java` — 알림 문구에 필요한 값(피보호자 이름·방 이름)을 실어 보낸다
- `domain/anomaly/service/AnomalyNotificationCooldown.java` — Redis, 키 `anomaly:notify:{userId}:{sessionId}:{type}`. 수신자별 간격(본인 1분/보호자 5분), fail-open
- `domain/anomaly/listener/AnomalyNotificationListener.java` — AFTER_COMMIT + `@Async("notificationExecutor")`, WebSocket(`anomaly-detected`) + 디스패처, 수신자별 try/catch 격리
- `domain/camera/dto/CameraOwner.java` — sessionId → (wardId, label) 뷰

**수정**
- `notification/dispatch/NotificationType.java` — `Policy` 도입, `ANOMALY_DETECTED` 추가, `isMandatory()` → `policy()`
- `notification/dispatch/NotificationDispatcher.java` — `switch (type.policy())` 3분기 + `[NOTIFY-UNDELIVERED]` WARN
- `domain/anomaly/service/AnomalyDetectionService.java` — 이력 저장 직후 이벤트 발행(+피보호자 이름 조회, 빈 이름은 `보호 대상자` 폴백)
- `domain/camera/service/CameraService.java` — `findWardIdBySessionId` → `findOwnerBySessionId`(방 이름 동반 조회). 호출부는 `AiLiveStreamSubscriber`·`AnomalyDetectionService` 둘뿐
- `domain/anomaly/config/AnomalyProperties.java` + `application.yaml` — `notify-cooldown-minutes: 5`, `notify-self-cooldown-minutes: 1`

## 4. 알림 문구

| 수신자 | 문구 |
|---|---|
| 보호자 | **이상 상황 감지** / `김순자님 댁 거실에서 화재가 감지되었습니다.` |
| 피보호자 본인 | **이상 상황 감지** / `거실에서 화재가 감지되었습니다. 안전한 곳으로 대피해 주세요.` |

FCM data · WebSocket payload: `{type: ANOMALY_DETECTED, wardId, sessionId, detectedType, anomalyEventId}` (FE 딥링크용).
WebSocket 토픽 `/topic/{userId}/anomaly-detected`는 `StompSubscriptionAuthorizationInterceptor`의 범용 `{userId}==세션` 검증으로 자동 보호된다(이벤트명 등록 불필요).

## 5. 쿨다운 2층 구조 (혼동 주의)

| 쿨다운 | 키 | 기본값 | 억제 대상 |
|---|---|---|---|
| `AnomalyEventCooldown` (1단계) | `(sessionId, detectedType)` | 5분 | **이력 적재** — 매 프레임 broadcast 폭주 방지 |
| `AnomalyNotificationCooldown` (2단계) | `(userId, sessionId, detectedType)` | 보호자 5분 / 본인 1분 | **알림 발송** — alarm fatigue 방지 |

알림은 **이력이 적재된 건에서만** 발행되므로, 이력 쿨다운이 1차 방어이고 알림 쿨다운이 그 위에 얹힌다(수신자별로 간격이 달라 하나로 합칠 수 없다). 둘 다 Redis 장애 시 **fail-open**(긴급 우선).

## 6. 검증 가이드

1. **AI danger 배포 전**: DANGER 모드라 이력·알림 모두 0건이 정상. `[ANOMALY-DANGER-MISMATCH]` WARN이 뜨면 AI가 아직 danger를 안 채우고 있다는 뜻.
2. **강제 재현**(AI 미배포 상태에서 확인하려면): `ANOMALY_TRIGGER_MODE=CONFIDENCE`로 임시 전환 → confidence ≥ 0.6 신호에서 이력 적재 + 알림 발송 확인 후 되돌린다.
3. **로그 확인**: `[ANOMALY] 이상감지 이력 적재` → `[ANOMALY] 이상감지 알림 발송: … 대상=N명, 발송=N건`
4. **채널 확인**: 보호자 알림 설정에서 SMS를 켠 계정만 문자가 오고, FCM을 꺼도 푸시는 온다. FCM 토큰이 없으면 `[NOTIFY-UNDELIVERED]` WARN이 남고 **문자는 오지 않는다**(D-2 의도).

## 7. 테스트 결과

`./gradlew test` — **278건 전부 통과**(실패 0). 추가·수정한 케이스:

- `NotificationDispatcherTest` — 이상감지: FCM OFF여도 FCM 발송 / SMS OFF면 미발송 / SMS ON이면 FCM+SMS / **FCM 미전달이어도 SMS 폴백 없음**(D-2 회귀 방지) / 정책 가드(강제 정책 타입은 `WARD_SOS`·`ANOMALY_DETECTED` 둘뿐)
- `AnomalyNotificationListenerTest`(신규) — 보호자 전원+본인 발송 / 수신자별 문구 구분 / 쿨다운 수신자만 생략 / 한 명 실패 시 격리 / 보호자 0명이어도 본인 발송
- `AnomalyDetectionServiceTest` — 이력 적재 시 이벤트 발행(이름·방 이름 포함), 판정·쿨다운·미등록 세션에서 걸리면 **미발행**
- `SosNotificationListenerTest` — 정책 단언을 `policy()`로 갱신(동작 변화 없음)

## 8. 다음 (2차 PR — 카카오 알림톡)

`KakaoAlimtalkNotificationChannel` 빈 하나만 추가하면 디스패처가 자동 수집한다(전략 패턴 — 디스패처 수정 불필요). 필요한 값:

`pfId`(발신 프로필) · `templateId` · **승인된 템플릿 원문**(변수·버튼 포함, 한 글자라도 다르면 발송 거부) · 발신번호 · **Solapi 대체발송(SMS) OFF 확인**(켜져 있으면 문자를 선택하지 않은 사용자에게 문자가 나가 D-2에 위배).
