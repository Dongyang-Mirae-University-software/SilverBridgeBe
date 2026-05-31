# 연결 해제 알림이 "거절" 문구로 보이는 문제 — 조사 및 FE 인계

- **작업 일자**: 2026-05-31
- **도메인**: `connection` (+ FE 인계)
- **유형**: 버그 조사 / 결론 = **백엔드 무결, 프론트 렌더링 + 와이어 식별자 네이밍 이슈**
- **DB 마이그레이션**: 불필요
- **백엔드 코드 변경**: 없음 (회귀 방지 테스트 단언만 보강)

---

## 1. 증상

- **실제**: 연결 해제(disconnect) 시 보호자/피보호자에게 **"피보호자가 거절하였습니다"** 류의 알림이 표시됨.
- **기대**: "피보호자가 연결을 해제했습니다." / "보호자가 연결을 해제했습니다."
- **사용자 가설**: 직전 거절(refuse) 알림 추가 작업(PR #183)이 해제 문구를 오염시켰을 것.

---

## 2. 조사 결과 (확정) — 백엔드는 정상

### 2-1. "피보호자가 거절하였습니다" 문자열은 백엔드에 존재하지 않는다
- `src/main` 전체 grep 결과, 해당 문자열 없음. 거절 본문은 **"연결 요청이 거절되었습니다."**(글자 자체가 다름), 해제 본문은 **"...연결을 해제했습니다"**.

### 2-2. 이벤트 → 리스너 매핑 정상
| 액션 | 발행 이벤트 (ConnectionService) | 수신 리스너 | FCM 본문 |
|------|--------------------------------|-------------|----------|
| 해제(보호자) | `ConnectionDisconnectedEvent(by=GUARDIAN)` | `handleDisconnected` | "보호자가 연결을 해제했습니다." |
| 해제(피보호자) | `ConnectionDisconnectedEvent(by=WARD)` | `handleDisconnected` | "피보호자가 연결을 해제했습니다." |
| 거절 | `ConnectionRefusedEvent` | `handleRefused` | "연결 요청이 거절되었습니다." |

`disconnectAsGuardian`/`disconnectAsWard`는 정확히 `ConnectionDisconnectedEvent`를, `refuseConnectionAsWard`는 `ConnectionRefusedEvent`를 발행한다. 이벤트가 뒤바뀐 곳 없음.

### 2-3. 거절 커밋이 해제 코드를 건드리지 않았다
- `git show 2a69188`(거절 알림 추가) diff: `handleRefused`를 **추가만** 함. `handleDisconnected` 본문은 한 줄도 변경되지 않음.
- 해제 본문은 `fa5f86f`(거절 전) → `2a69188`(거절 후) → `ee997f9`(현재) **세 시점 모두 동일**. 오염 이력 없음. → **사용자 가설 기각.**

### 2-4. 액터 표기 로직 정상
- 해제는 보호자·피보호자 양쪽 모두 가능. 수신자 = "해제하지 않은 반대편 당사자", 본문 주체 = `disconnectedBy`(GUARDIAN/WARD)로 정확히 분기.

---

## 3. 진짜 원인 — 와이어 식별자 네이밍 + FE 포그라운드 렌더링

FCM 발송(`FcmService.sendMulticast`)은 **`notification`(title/body) + `data` 둘 다** 전송한다.
- 앱 **백그라운드**: OS가 서버 `body`("...해제했습니다")를 그대로 표시 → 정상.
- 앱 **포그라운드**: 앱이 `data.type`을 보고 **자체 문구를 렌더** → 여기서 잘못된 문구가 나온다.

해제 이벤트의 와이어 식별자가 의미와 어긋나 있다:

| 구분 | 해제(disconnect)가 보내는 값 | 평가 |
|------|------------------------------|------|
| `NotificationType`(내부 enum) | `CONNECTION_DISCONNECTED` | 의미 일치 |
| **FCM `data.type`** | **`CONNECTION_CANCELLED`** | ⚠️ 의미 불일치 |
| **WebSocket 이벤트명** | **`connection-cancelled`** | ⚠️ 의미 불일치 |
| 상태(status) | `DISCONNECTED` | 의미 일치 |

거절 인계 문서(2026-05-28) 말미:
> "`connection-cancelled` 토픽은 이제 연결 해제 전용이다. … 기존 문서가 cancelled를 '해제/거절'로 표기했으나 거절은 이벤트가 아예 없었음."

→ 거절 이벤트 **분리 이전**, 프론트는 `CONNECTION_CANCELLED` 하나를 "해제 겸 거절"로 취급했을 가능성이 크다. 그래서 FE 포그라운드 핸들러가 `type=CONNECTION_CANCELLED`를 받아 **"피보호자가 거절하였습니다"** 같은 FE측 문구를 띄운다. 이 문구는 백엔드에 없다.

**결론**: 백엔드 본문은 정상. 표시된 "거절" 텍스트는 FE가 `data.type`/WS 이벤트명을 보고 만든 문자열이며, 혼동을 유발한 백엔드측 냄새는 **해제인데 와이어 식별자가 `CANCELLED`로 명명**된 점.

---

## 4. 4가지 알림 문구 점검표

| 액션 | 이벤트 | FCM 본문(서버) | `data.type` | WS 이벤트명 | 평가 |
|------|--------|----------------|-------------|-------------|------|
| 요청 | `ConnectionRequestedEvent` | "{관계} {이름}님이 연결을 요청했어요." / fallback | `CONNECTION_REQUEST` | `connection-request` | ✅ 일관 |
| 수락 | `ConnectionAcceptedEvent` | "피보호자가 연결 요청을 수락했습니다." | `CONNECTION_ACCEPTED` | `connection-accepted` | ✅ 일관 |
| 거절 | `ConnectionRefusedEvent` | "연결 요청이 거절되었습니다." | `CONNECTION_REFUSED` | `connection-refused` | ✅ 일관 |
| 해제 | `ConnectionDisconnectedEvent` | "보호자/피보호자가 연결을 해제했습니다." ✅ | `CONNECTION_CANCELLED` ⚠️ | `connection-cancelled` ⚠️ | 본문 정상, 식별자만 레거시 |

---

## 5. 처리 방향 — 옵션 B (와이어 호환 유지 + FE 수정)

백엔드 본문이 이미 정상이고 와이어 식별자를 바꾸면 FE 동시 배포가 필요한 breaking change이므로, **백엔드 코드는 변경하지 않고**(호환 유지) 계약을 명확히 인계하여 FE에서 문구를 교정한다.

### 백엔드 변경 (이번)
- 코드 변경 없음.
- 회귀 방지 테스트 보강: `ConnectionNotificationListenerTest`의 해제/거절 테스트에 **`data.type` 단언 추가**(`CONNECTION_CANCELLED` / `CONNECTION_REFUSED`) — FE가 키로 쓰는 필드를 고정해 향후 혼선 차단.

### 프론트엔드 작업 (인계)
1. **`CONNECTION_CANCELLED` / `connection-cancelled` = 연결 해제 전용**으로 처리. 표시 문구를 "거절"이 아닌 **해제 문구**로 교정.
   - 포그라운드에서 자체 문구를 만들지 말고 **서버가 보낸 `notification.body`를 그대로 표시**하면 가장 안전(요청/수락/거절/해제 모두 정상 문구가 옴).
2. **`CONNECTION_REFUSED` / `connection-refused` = 거절 전용** 핸들러를 별도로 둔다(PR #183에서 신설된 이벤트).
3. 두 식별자를 한 분기에서 공유하지 말 것.

### 와이어 계약(현행, 변경 없음)
| 액션 | `data.type` | WS 이벤트 | 서버 본문 |
|------|-------------|-----------|-----------|
| 해제 | `CONNECTION_CANCELLED` | `connection-cancelled` | "보호자/피보호자가 연결을 해제했습니다." |
| 거절 | `CONNECTION_REFUSED` | `connection-refused` | "연결 요청이 거절되었습니다." |

> 참고: 향후 `CONNECTION_CANCELLED` → `CONNECTION_DISCONNECTED` 정합화(옵션 A)는 FE 동시 배포를 전제로 별도 과제로 다룬다. 이번에는 호환을 깨지 않는다.

---

## 6. 테스트 결과

- `./gradlew test --tests "...ConnectionNotificationListenerTest"` → **EXIT 0** (보강 단언 포함 전체 통과).
- 빌드: `./gradlew build -x test --no-daemon` → **EXIT 0**.
- 4종 알림(요청/수락/거절/해제) 본문·`type`·WS 이벤트명이 서로 섞이지 않음을 테스트가 고정.
