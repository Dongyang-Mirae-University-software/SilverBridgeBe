# 알림 채널 추상화 인프라 구축 (1단계)

- **작업 일자**: 2026-05-31
- **브랜치**: `feature/notification-channel-infra` (dev 분기)
- **범위**: 1단계 — 알림 설정 인프라 + 채널 추상화(FCM/SMS 포함). 카카오 알림톡·이메일은 **enum만 정의, 구현 제외**.

---

## 1. 전체 그림 (3단계 중 1단계)

```
현재(이전): 연결 이벤트 → ConnectionNotificationListener → FcmService.sendToUser + WebSocket 직접 호출
목표:        이벤트 → NotificationDispatcher → 사용자 설정에 따라 활성 채널(FCM/SMS/카카오알림톡/이메일)
```

| 단계 | 내용 | 상태 |
|---|---|---|
| 1단계 (이번) | 알림 설정 인프라 + 채널 추상화 (FCM/SMS) | ✅ 완료 |
| 2단계 (다음) | 카카오 알림톡 채널 추가 | 인계 (아래 §8) |
| 3단계 (다음) | 이메일 알림 채널 추가 | 인계 (아래 §8) |

---

## 2. PHASE 0 — 작업 전 현재 구조

> ⚠️ 프롬프트 전제("이벤트 → FCM + SMS 고정 발송")와 실제가 달랐다.

- **알림 발송**: 연결 이벤트는 **FCM + WebSocket** 두 채널로 발송됐고, **SMS는 알림에 쓰이지 않았다**.
- **SMS**: 순수 인증 전용(회원가입 인증코드 / 비밀번호 재설정). `SmsSender`(Solapi 래퍼) → `SmsVerificationService`/`PasswordResetService`에서 호출. 실패 시 `CustomException(SMS_SEND_FAILED)`.
- **공통 인터페이스 없음**: `ConnectionNotificationListener`가 `FcmService.sendToUser(...)`를 직접 호출.
- **알림 발생 이벤트(전부 ConnectionService에서 발행, AFTER_COMMIT @Async 리스너 수신)**:
  - `ConnectionRequestedEvent`(요청 → 피보호자)
  - `ConnectionAcceptedEvent`(수락 → 보호자)
  - `ConnectionRefusedEvent`(거절 → 보호자)
  - `ConnectionDisconnectedEvent`(해제 → 상대; 보호자/피보호자/탈퇴 3경로)
- **User 엔티티**: 알림 설정 필드 없음. **알림 설정 API도 없음**.

→ 시사점: SMS는 알림 채널로는 **신규 추가**(회귀 아님). WebSocket도 사실상 알림 채널이나 실시간 동기화 목적이라 추상화에서 제외하기로 결정(사용자와 협의).

---

## 3. 채널 추상화 설계

```
domain/notification/channel/
├─ NotificationChannelType (enum)  FCM, SMS, KAKAO_ALIMTALK, EMAIL
├─ NotificationChannel     (interface)  getType() / send(recipient, content)
├─ NotificationContent     (record)     title, body, Map<String,String> data
├─ NotificationRecipient   (record)     userId, phone, email
├─ FcmNotificationChannel               → FcmService.sendToUser 위임
└─ SmsNotificationChannel               → SmsSender.send 위임 ("[제목] 본문")

domain/notification/dispatch/
├─ NotificationType            (enum)   알림 종류 + mandatory(필수) 플래그
├─ NotificationRecipientResolver        userId → User 조회 → (userId, phone, email)
└─ NotificationDispatcher               설정 조회 → 활성 채널 발송 / 실패 격리 / 미구현 채널 skip
```

- **전략 패턴**: 채널 구현체를 `@Component`로 등록하면 `NotificationDispatcher`가 `List<NotificationChannel>` 주입으로 자동 수집(`EnumMap<Type, Channel>`). **새 채널 = 구현체 하나 추가**로 끝.
- **KAKAO_ALIMTALK / EMAIL**: enum 값만 존재, 구현체 없음 → 설정상 켜져 있어도 디스패처가 조용히 건너뜀(`log.debug`).
- **WebSocket**: 추상화 밖, 리스너에서 직접·항상 발송(온라인 실시간 동기화).
- **결합 주의**: `SmsNotificationChannel`(notification)이 `SmsSender`(auth)를 주입 → notification→auth 약결합. `SmsSender`는 무상태 인프라 래퍼라 v1에서는 그대로 두고, global/infra 추출은 후속 정리로 인계.

---

## 4. 사용자 알림 설정 구조

**신규 테이블 `user_notification_settings` (V25)** — (user_id, channel_type) 한 쌍당 한 행.

```sql
CREATE TABLE user_notification_settings (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(6) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel_type VARCHAR(20) NOT NULL,   -- FCM / SMS / KAKAO_ALIMTALK / EMAIL
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_notif_channel UNIQUE (user_id, channel_type)
);
CREATE INDEX idx_user_notif_user ON user_notification_settings (user_id);
```

- **기본값 정책**: 행이 없는 채널은 기본값 — **FCM ON, 그 외 OFF**. → 기존 동작(연결 알림=FCM) 보존 + **백필 마이그레이션 불필요**.
- **세분화 수준**: v1은 **채널 단위 ON/OFF**. "알림 종류(connection/announcement…)별" 매트릭스는 `notification_type` 컬럼 추가로 후속 확장 가능(인계).
- **탈퇴 정리**: users FK `ON DELETE CASCADE` → 회원 탈퇴(hard delete) 시 자동 삭제(별도 코드 불요).

---

## 5. 필수(설정 무시) / 선택(설정 따름) 분류

| 알림 | 분류 | 경로 | 처리 |
|---|---|---|---|
| SMS 인증번호(가입·비번재설정) | **필수** | 디스패처 미경유(동기 `SmsSender`) | 변경 없음 — 설정으로 끌 수 없음이 **구조적으로 보장** |
| 연결 요청/수락/거절/해제 | **선택** | 디스패처 경유 | 사용자 설정 따름(기본 FCM) |
| (향후) 이상감지 등 긴급 | **필수** | 디스패처 `mandatory=true` | 메커니즘 마련(`MANDATORY_CHANNELS`), 현재 등록 타입 없음 |

`NotificationType`의 모든 현재 값은 `mandatory=false`. mandatory 강제발송 분기는 구현돼 있으나 활성 타입이 없어 휴면 상태(테스트는 분류 가드로 고정).

---

## 6. 구현 내용 + 변경 파일

### 신규 (15개)
- `channel/NotificationChannelType.java`, `NotificationChannel.java`, `NotificationContent.java`, `NotificationRecipient.java`
- `channel/FcmNotificationChannel.java`, `SmsNotificationChannel.java`
- `dispatch/NotificationType.java`, `NotificationRecipientResolver.java`, `NotificationDispatcher.java`
- `entity/UserNotificationSetting.java`, `repository/UserNotificationSettingRepository.java`
- `service/NotificationSettingService.java`
- `controller/NotificationSettingController.java`, `dto/NotificationSettingResponse.java`, `dto/NotificationSettingUpdateRequest.java`
- `db/migration/V25__add_user_notification_settings.sql`

### 변경 (1개)
- `connection/listener/ConnectionNotificationListener.java` — `fcmService.sendToUser(...)` 4곳 → `notificationDispatcher.dispatch(userId, TYPE, content)`. **WebSocket 호출은 그대로 유지.**

### 미변경 (회귀 방지)
- `FcmService`, `SmsSender`, 인증/비밀번호 재설정 흐름 — 손대지 않음.

### API
| Method | Path | 설명 |
|---|---|---|
| GET | `/api/user/me/notification-settings` | 전체 채널 설정 조회(기본값 병합) |
| PUT | `/api/user/me/notification-settings` | 채널 설정 일괄 upsert |

---

## 7. 테스트 결과

신규/갱신 단위 테스트(순수 Mockito + AssertJ):
- `FcmNotificationChannelTest` — 위임·getType
- `SmsNotificationChannelTest` — 위임·전화번호 없으면 미발송
- `NotificationDispatcherTest` — 켜진 채널만 발송 / 두 채널 발송 / 미구현 채널 무시 / **채널 실패 격리** / 활성 채널 없음 조기종료 / 분류 가드
- `NotificationSettingServiceTest` — 기본값(FCM) / 저장값 우선 / getSettings 병합 / **upsert**
- `ConnectionNotificationListenerTest`(갱신) — 디스패처 위임으로 검증 방식 전환(회귀: 문구·대상·타입 보존)

검증:
- `./gradlew test --no-daemon` → **EXIT 0** (전체 통과, 기존 테스트 회귀 없음)
- `./gradlew build -x test --no-daemon` → **EXIT 0**

---

## 8. 2·3단계 인계 사항

### 카카오 알림톡 (2단계)
- 사용자 제공 참고 자료(2026-05-31):
  - Solapi Java SDK 예제: `https://solapi.com/developers/sdk/java-sendingexample`
  - 비즈니스 채널 PFID: `KA01PF240930145539248iUN6bVyplGB`
  - 템플릿 ID: `KA01TP241002033713270gJH4Dh27nTM`
  - (위 PFID·템플릿 ID는 식별자로 시크릿 아님. SOLAPI_API_KEY/SECRET만 `.env`로 관리)
- **할 일**: `KakaoAlimtalkChannel implements NotificationChannel` 추가(`getType()=KAKAO_ALIMTALK`) → 디스패처 자동 편입.
- **설계 포인트**: 알림톡은 **사전 승인 템플릿 + 변수 치환** 방식이라, 자유 텍스트(`title`/`body`)와 맞지 않는다.
  → `NotificationContent`에 **템플릿 변수 맵**을 어떻게 실을지 결정 필요(예: `data`에 템플릿 변수 동봉 + 채널이 템플릿ID로 매핑, 또는 `NotificationContent`에 `templateId`/`variables` 필드 추가). 채널별 페이로드 차이를 흡수하는 방향 권장.

### 이메일 (3단계)
- `EmailChannel implements NotificationChannel`(`getType()=EMAIL`) 추가. `NotificationRecipient.email()` 이미 채워짐(SMTP 발송).

### 공통 후속(선택)
- `SmsSender`를 global/infra로 추출해 notification→auth 결합 제거.
- 알림 "종류별" 세분화 필요 시 `user_notification_settings`에 `notification_type` 컬럼 추가(신규 마이그레이션) + `enabledChannels(userId, type)` 시그니처 확장.

---

## 9. 프론트엔드 영향

**없음(회귀 0).** 추가(additive) 변경 — 기존 API 계약·FCM 수신·WebSocket·SMS 인증 모두 불변. 알림 메시지 payload(`type`/`connectionId`)도 동일. FE는 수정 없이 현재와 동일하게 동작하며, 알림 설정 화면을 만들 때만 신규 API 2개를 연동하면 된다. (단, FE가 FCM을 OFF로 토글하면 그때부터 연결 푸시가 안 옴 — 의도된 동작.)
