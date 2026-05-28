# 연결 거절 시 보호자 실시간 알림 추가

- **작업 일자**: 2026-05-28
- **도메인**: `connection`
- **브랜치(예정)**: `feature/connection-refused-notification`
- **유형**: 기능 추가 (수락/해제 흐름과의 비대칭 해소)
- **DB 마이그레이션**: 불필요 (상태 전이 변경 없음, 이벤트 발행만 추가)

---

## 1. 진단 결과 (확정)

피보호자가 연결 요청을 거절(refuse)해도 보호자에게 이벤트/알림이 발송되지 않아, 보호자 웹이 **새로고침 전까지 "요청중" 상태로 멈춰** 있었다.

수락·연결해제는 이벤트를 발행하는데 **거절만 누락**되어 있던 비대칭이 원인.

| 액션 | 이벤트 | 알림 대상 | 변경 전 |
|------|--------|-----------|---------|
| 요청 | `ConnectionRequestedEvent` | 피보호자 | ✓ |
| 수락 | `ConnectionAcceptedEvent` | 보호자 | ✓ |
| 연결해제 | `ConnectionDisconnectedEvent` | 상대 | ✓ |
| **거절** | **없음** | — | ✗ ← 이번에 추가 |

- **원인 코드**: `ConnectionService.refuseConnectionAsWard()`가 `connection.refuse()`만 호출하고 이벤트를 발행하지 않음.

---

## 2. 구현 내용

수락(`handleAccepted`) 흐름을 **100% 미러링**했다. 새 패턴을 만들지 않음.

### 2-1. 이벤트 — `ConnectionRefusedEvent` (신규)

`domain/connection/event/ConnectionRefusedEvent.java`

```java
public record ConnectionRefusedEvent(
        Long connectionId,
        String guardianId   // 알림 대상 = 요청을 보낸 보호자
) {}
```

`ConnectionAcceptedEvent`와 동일한 2-필드 record 구조.

### 2-2. 서비스 — `ConnectionService.refuseConnectionAsWard()`

`connection.refuse()` 커밋 후 이벤트 발행 추가 (수락과 동일한 `eventPublisher.publishEvent(...)` 방식):

```java
connection.refuse();

eventPublisher.publishEvent(new ConnectionRefusedEvent(
        connectionId, connection.getGuardianId()
));
log.info("연결 거절(피보호자): connectionId={}, wardId={}, guardianId={}",
        connectionId, wardId, connection.getGuardianId());
```

### 2-3. 리스너 — `ConnectionNotificationListener.handleRefused()` (신규)

`handleAccepted`와 대칭. `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("notificationExecutor")`로 **DB 커밋 후 별도 스레드에서 발송**(롤백 시 미발송, HTTP 응답 지연 없음):

```java
@Async("notificationExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleRefused(ConnectionRefusedEvent event) {
    webSocketEventPublisher.sendToUser(event.guardianId(), "connection-refused",
            Map.of("connectionId", event.connectionId()));

    fcmService.sendToUser(event.guardianId(), "연결 거절",
            "연결 요청이 거절되었습니다.",
            Map.of("type", "CONNECTION_REFUSED",
                    "connectionId", String.valueOf(event.connectionId())));
}
```

### 2-4. WebSocket 토픽 인가 — 변경 없음

`StompSubscriptionAuthorizationInterceptor`는 이벤트명 화이트리스트가 아니라 `/topic/{userId}/...`의 `{userId}` 세그먼트가 인증 세션의 userId와 일치하는지만 검증한다. 보호자는 자신의 `connection-refused` 토픽을 정상 구독하고 타인 토픽은 차단된다 → **별도 등록 불필요**(수락 `connection-accepted`도 동일하게 자동 동작 중).

---

## 3. FCM 문구 결정

- **확정 문구**: **"연결 요청이 거절되었습니다."**
- **근거**: 사용자가 시니어/4050 타겟이라 **직관적·명확한 안내 우선**. "종료되었습니다" 같은 모호한 표현은 혼란을 줄 수 있어 회피.
- 문구는 다른 연결 알림(수락 "피보호자가 연결 요청을 수락했습니다." 등)과 마찬가지로 **리스너에 하드코딩**(상수/설정 아님) — 기존 방식 그대로 미러링.

---

## 4. 알림 비대칭 정책 (의도된 설계)

같은 PENDING 종료라도 알림 대상이 다르며, 이는 **의도된 것**이다. 향후 "일관성" 명목으로 ②③에 알림을 추가하지 말 것:

| 시나리오 | 처리 | 알림 |
|----------|------|------|
| ① 피보호자 거절 (`refuse`) | PENDING → REFUSED | **보호자에게 발송 O** (본인의 명시적 거부 행위) |
| ② 보호자 요청 취소 (`cancel`) | PENDING → CANCELLED | 무알림 |
| ③ 회원 탈퇴 시 PENDING (`tearDownConnectionsOnWithdrawal`) | PENDING → CANCELLED | 무알림 |

거절만 상대에게 명시적 통지가 필요한 "사용자가 직접 거절한 액션"이기 때문.

---

## 5. 프론트 인계 정보

| 항목 | 값 |
|------|-----|
| WebSocket 이벤트 | `connection-refused` |
| 구독 토픽 | `/topic/{guardianId}/connection-refused` (본인 userId만 구독 가능) |
| payload | `{ "connectionId": <number> }` |
| FCM data | `{ "type": "CONNECTION_REFUSED", "connectionId": "<id>" }` |
| FCM 표시 | 제목 "연결 거절" / 본문 "연결 요청이 거절되었습니다." |

**프론트 작업**:
- `connection-refused` 수신 시 → 보호자의 "요청 내역"에서 해당 `connectionId` 항목을 **요청중 → 거절됨**으로 갱신(또는 목록에서 제거).
- 기존 `connection-accepted` 구독과 동일한 패턴으로 핸들러 추가하면 됨.
- FCM `CONNECTION_REFUSED` 푸시 수신 처리.

> 참고: `connection-cancelled` 토픽은 이제 **연결 해제(disconnect) 전용**이다. 거절은 별도 `connection-refused`로 분리됨(기존 문서가 cancelled를 "해제/거절"로 표기했으나 거절은 이벤트가 아예 없었음).

---

## 6. 테스트 결과

### 변경/추가한 테스트

- **`ConnectionServiceTest`** (의도 반전 — 사용자 승인)
  - 기존 `정상거절_REFUSED_이벤트없음`(이벤트 없음 검증) → `정상거절_REFUSED전환_및_이벤트`로 수정: `ConnectionRefusedEvent` 발행 + `connectionId`/`guardianId` 검증.
  - 비-PENDING 거절 케이스에 `verify(eventPublisher, never()).publishEvent(any())` + 상태 불변 검증 추가(가드 실패 시 미발행 불변식 — 프로젝트의 롤백-등가 단위테스트 패턴).
- **`ConnectionNotificationListenerTest`** (추가)
  - `handleRefused_보호자에게_거절알림`: WS `connection-refused`(보호자) + FCM "연결 거절"/"연결 요청이 거절되었습니다." 발송 검증.

### 실행 결과

```
./gradlew test --tests ConnectionServiceTest --tests ConnectionNotificationListenerTest
→ BUILD SUCCESSFUL

./gradlew build --no-daemon   (전체 컴파일 + 전체 테스트)
→ BUILD SUCCESSFUL
```

---

## 7. 변경 파일 요약

| 파일 | 변경 |
|------|------|
| `event/ConnectionRefusedEvent.java` | 신규 |
| `service/ConnectionService.java` | import + `refuseConnectionAsWard()` 이벤트 발행·로그 |
| `listener/ConnectionNotificationListener.java` | import + `handleRefused()` |
| `test/.../ConnectionServiceTest.java` | 거절 테스트 의도 반전 + 비PENDING 미발행 검증 |
| `test/.../ConnectionNotificationListenerTest.java` | `handleRefused` 테스트 추가 |
| `프로젝트_설명.txt` | 3-6 연결 상태 흐름·엔드포인트, 4. WebSocket 토픽 목록, 패키지 구조 |
| `CLAUDE.md` | §9 도메인 정책 메모 + footer |
| `docs/progress.md` | 2026-05-28 항목 |
