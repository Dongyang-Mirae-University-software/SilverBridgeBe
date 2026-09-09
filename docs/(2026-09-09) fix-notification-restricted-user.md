# 이용 제한 계정 - 알림 차단 · 정지 사유 · 피보호자 정지 금지

> 2026-09-09 · 선행: 회원관리(PR #243, V47·V48)
> 마이그레이션: **V49**(`users.status_reason`)

---

## 1. 무엇을 고쳤나

회원관리로 계정 정지가 생겼지만 **정지된 계정에 알림이 계속 나갔다.** `NotificationDispatcher`가 사용자 상태를 보지 않기 때문이다. 앱은 못 여는데 SOS·화재·복약 알림은 계속 도착하고, 문자는 요금까지 나갔다.

함께 정한 것 셋이다.

1. **이용 제한·탈퇴 진행 계정에는 어떤 채널로도 보내지 않는다** (강제 FCM 포함)
2. **정지 사유를 남긴다** (`users.status_reason`, V49)
3. **피보호자는 이용 제한할 수 없다** (400)

---

## 2. 왜 강제 채널까지 막는가

`WARD_SOS`·`ANOMALY_DETECTED`는 사용자 설정을 무시하고 FCM을 보내는 필수 알림이다. "필수인데 막아도 되나"가 쟁점이었고, **막는 쪽이 맞다**고 판단했다.

정지의 용도를 **"탈취가 의심될 때의 임시 조치"** 로 정했기 때문이다. 그 계정을 지금 누가 쥐고 있는지 모르는 상태에서 알림을 계속 보내면:

- 탈취된 **보호자** 계정 → 공격자가 그 피보호자의 **SOS 발생 시각·화재 감지·복약 이력**을 실시간으로 통보받는다. 집이 비었는지, 응급 상황인지를 알게 된다
- 탈취된 **피보호자** 계정 → 본인 수신 알림이 공격자에게 간다

즉 "안전을 위해 알림을 이어간다"가 아니라 **취약한 노인의 생활 패턴을 공격자에게 계속 흘리는 것**이 된다. 개인정보 관점에서 이쪽이 훨씬 무겁다.

### 막지 않는 것 - 정지된 사람이 "원인"인 알림

차단 기준은 **수신자**다. 정지된 피보호자 집에서 화재가 감지되면 그 보호자들에게는 **그대로 발송된다.** 계정 정지는 이용 제한이지 안전망 해제가 아니다.

### 받아들인 위험

보호자가 한 명뿐인 피보호자에게서 SOS가 났는데 **그 보호자가 정지 상태면 아무에게도 가지 않는다.** 발송을 건너뛸 때마다 `[NOTIFY-BLOCKED]` WARN을 남겨 흔적은 만들지만, 근본 해결은 "정지 전에 관리자에게 알리는 것"이다(§6 참조).

---

## 3. 차단 지점 - 한 곳, 추가 쿼리 0

`NotificationDispatcher.dispatch()`가 단일 진입점이다(호출부 12곳 전부 여기를 지난다). 그리고 `NotificationRecipientResolver`가 **이미 `findById()`로 User를 읽고 있어서**, `NotificationRecipient`에 상태를 실으면 **추가 쿼리 없이** 판정할 수 있다.

```java
private Optional<NotificationRecipient> resolveIfAllowed(String userId, NotificationType type) {
    NotificationRecipient recipient = recipientResolver.resolve(userId);
    if (recipient.canReceive()) return Optional.of(recipient);
    log.warn("[NOTIFY-BLOCKED] ...");
    return Optional.empty();
}
```

**기존 최적화를 깨지 않았다.** `SETTINGS_ONLY`는 원래 "활성 채널이 하나도 없으면 수신자 조회조차 안 한다"는 최적화가 있었고(테스트로 고정돼 있었다), 처음 구현에서 이걸 깨뜨렸다가 되돌렸다. 지금은 채널 계산이 먼저고 그 다음에 조회·차단이다.

**상태를 모르면 막지 않는다.** 사용자 행을 못 찾으면 `status`가 `null`인데, 이건 "정지된 것"이 아니라 "알 수 없는 것"이다. 기존 "부분 정보로도 가능한 발송은 진행한다" 동작을 유지한다.

---

## 4. 정지 사유 (V49)

`statusReason`을 감사 로그에만 남기면 **아무도 볼 수 없다.** 관리자 감사 로그 *조회* API는 2026-06-11에 제거돼 쓰기 경로만 남아 있어서, DB를 직접 열지 않으면 "이 계정이 왜 잠겼는지"에 답할 수 없다.

정지가 "본인 확인이 될 때까지의 임시 조치"인 이상 **해제 판단을 하려면 사유가 화면에 보여야 한다.** 그래서 `users.status_reason` 컬럼을 두고 목록·상세 응답에 실었다.

- 이용 제한으로 바꿀 때 채우고, **이용 중으로 되돌리면 지운다**(`User.activate()`가 함께 비운다). 잠기지 않았는데 잠긴 이유가 남아 있으면 안 된다
- 변경 이력은 계속 `admin_audit_log`가 담당한다(사유도 detail에 함께 기록). 컬럼은 **"지금 왜 잠겨 있는가"만** 답한다
- NULL 허용 컬럼 추가라 기존 행 무영향, 백필 없음

---

## 5. 피보호자 정지 금지

**피보호자를 정지하면 SOS를 보낼 수 없다.** `POST /api/ward/sos`는 로그인이 전제인데 정지되면 로그인이 막힌다. 복약 체크도 못 한다. 피보호자 계정은 편의 기능이 아니라 **안전망 그 자체**라, 그걸 끄는 걸 정당화할 사유가 없다.

탈취가 의심되면 **정지 대신 비밀번호 재설정**이 맞다. `PasswordChangedEvent`가 refresh 토큰을 지우고 기존 access token까지 무효화하므로 공격자는 그 자리에서 튕겨나가고, **본인은 계속 쓸 수 있다.**

### 조합으로 검사한다 (두 단계 우회 차단)

축별로 따로 검사하면 **"보호자를 정지 → 역할을 피보호자로 변경"** 두 단계로 금지된 상태를 만들 수 있다. 반대로 순서를 정해 검사하면 "제한을 풀면서 동시에 역할을 바꾸는" 정상 요청이 잘못 거부된다.

그래서 `validateResultingCombination()`이 **바뀐 뒤의 조합**을 한 번에 본다.

| 요청 | 결과 |
|---|---|
| 보호자 → 이용 제한 | 통과 |
| 피보호자 → 이용 제한 | 400 `WARD_CANNOT_BE_RESTRICTED` |
| 정지된 보호자 → 역할만 피보호자로 | **400** |
| 정지된 보호자 → 역할 피보호자 + 이용 중 동시에 | 통과 |

---

## 6. 범위 밖으로 둔 것

- **"이 보호자를 정지하면 김영희의 유일한 보호자가 사라집니다" 경고** - 관리자 API가 연결을 역으로 세야 해서 범위가 커진다. §2의 "받아들인 위험"을 실제로 줄이는 방법이라 별건으로 남긴다
- **SMS 인증번호** - 디스패처를 거치지 않는 별개 흐름이라 정지 계정도 비밀번호 재설정 문자를 받을 수 있다. auth 도메인 건이다
- **WebSocket** - 추상화 밖이지만 정지되면 로그인 자체가 안 돼 세션이 없다. 손댈 것 없음

---

## 7. 변경 파일

**신규**: `db/migration/V49__add_user_status_reason.sql`

**수정**
```
notification/channel/NotificationRecipient.java      + status, canReceive()
notification/dispatch/NotificationRecipientResolver  상태를 함께 반환
notification/dispatch/NotificationDispatcher         resolveIfAllowed() 게이트
user/entity/User.java                                + statusReason, restrict(reason), activate()가 사유 정리
admin/dto/AdminUserUpdateRequest                     + statusReason (200자)
admin/dto/AdminUserListItem · AdminUserDetailResponse + statusReason
admin/service/AdminUserService                       조합 검증 + 사유 저장·기록
admin/controller/AdminUserController                 Swagger 설명
global/exception/ErrorCode                           + WARD_CANNOT_BE_RESTRICTED
global/config/SwaggerConfig                          태그 설명
```

---

## 8. 검증

- `./gradlew build` **543건 / 실패 0** (기존 533 + 신규 10)
- **V49는 gosky dev DB에서 트랜잭션 실행 후 롤백으로 확인**: 컬럼 생성(varchar 200, nullable) / 기존 14행 전부 NULL로 무영향 / 정지+사유 저장 동작 / 200자 초과 거부 / 롤백 후 컬럼 원상
- 테스트로 고정한 것: 정지 계정에 설정 채널·강제 FCM·이상감지 모두 미발송 / 탈퇴 진행 계정도 미발송 / **상태 불명은 기존대로 발송** / 차단 시 사용자 설정을 읽지도 않음 / 활성 채널 0건이면 조회조차 안 하는 기존 최적화 유지 / 사유 저장·해제 시 삭제 / 피보호자 정지 400 / 두 단계 우회 400 / 해제+역할변경 동시 요청 통과
