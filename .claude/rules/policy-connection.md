---
paths:
  - "**/domain/connection/**"
---

# 도메인 정책 - 보호자-피보호자 연결

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

## 연결 거절 — 보호자 실시간 알림 추가 (2026-05-28)

- **의도**: 피보호자가 연결 요청을 거절해도 보호자에게 이벤트가 안 가 보호자 웹이 새로고침 전까지 "요청중"에 멈춰 있던 문제. 수락(`ConnectionAcceptedEvent`)·해제(`ConnectionDisconnectedEvent`)와 **비대칭**이던 거절을 동일 패턴으로 정렬.
- **변경**: `refuseConnectionAsWard()`가 `refuse()` 커밋 후 `ConnectionRefusedEvent(connectionId, guardianId)` 발행 → `ConnectionNotificationListener.handleRefused()`(AFTER_COMMIT, `@Async`)가 보호자에게 WebSocket(`connection-refused`) + FCM(`"연결 요청이 거절되었습니다."`) 발송.
- **FCM 문구**: 시니어/4050 타겟 직관성 우선 — "거절되었습니다"로 명확히. 모호한 "종료/해제" 표현은 회피.
- **알림 비대칭 정책(의도된 것)**: 같은 PENDING 종료라도 알림 대상이 다름 — ① **피보호자 거절 → 보호자 알림O**(본인의 명시적 액션), ② **보호자 요청 취소(`cancel`) → 무알림**, ③ **회원 탈퇴 시 PENDING → CANCELLED 무알림**(`tearDownConnectionsOnWithdrawal`). 향후 "일관성" 명목으로 ②③에 알림을 추가하지 말 것 — 거절만 상대에게 통지가 필요한 명시적 거부 행위.
- **인가**: `connection-refused` 토픽은 `StompSubscriptionAuthorizationInterceptor`의 범용 `{userId}==세션` 검증으로 자동 보호(이벤트명 화이트리스트 없음) — 별도 등록 불필요.
- **DB 영향 없음**: 상태 전이(`refuse()`)는 기존과 동일, 이벤트 발행만 추가. 마이그레이션 불필요.
- 상세: `docs/(2026-05-28) feature-connection-refused-notification.md`.

## 연결 요청 취소 - 알림은 없고 화면 갱신 신호만 간다 (2026-10-01 QA BE-3)

- 위 비대칭 정책(취소 → 무알림)은 **푸시·문자·알림 이력**에 대한 결정이다. 그런데 신호가 아예 없으면 피보호자가 열어 둔 "요청 온 목록"에 취소된 요청이 남아, 누르면 `CONNECTION_NOT_PENDING` 오류가 났다.
- `cancelPendingAsGuardian`이 `ConnectionRequestCancelledEvent`를 발행하고, 리스너가 피보호자에게 WebSocket **`connection-request-cancelled`(payload `{connectionId}`)만** 보낸다. **디스패처를 거치지 않으므로 FCM·SMS·알림 이력이 생기지 않는다** - 이 경계를 넘어 푸시를 붙이지 말 것.
- 기존 `connection-cancelled`를 재사용하지 말 것 - FE가 그 이벤트를 "연결이 해제되었습니다" 토스트로 보여 줘서 수락 전 요청 취소에는 문구가 거짓이 된다.
- 탈퇴·역할 변경으로 PENDING이 조용히 취소되는 경로는 같은 증상이 있으나 이번에 다루지 않았다(필요해지면 같은 이벤트를 재사용).

## 연결 요청 반복 제한 - 같은 쌍 24시간 쿨다운 (2026-10-02 QA CONN-G04/XCUT-G29)

- **규칙**: 같은 (보호자, 피보호자) 쌍의 요청은 24시간에 **5건까지** 허용하고 **6번째부터 429 `CONNECTION_REQUEST_COOLDOWN`**(`retryAfterSeconds` = 남은 TTL)이다(`ConnectionRequestLimiter.MAX_REQUESTS_PER_PAIR = 5`, 사용자 지시 "반복 5회 이상이면 적용" 후 4→5로 조정). Redis `connection:request:count:{guardianId}:{wardId}`, 24시간 고정 윈도우, Flyway 없음.
- **세는 시점**: 중복(PENDING·ACTIVE) 검사 **뒤**에 센다(409는 세지 않음). 조회→생성→증가 순서라 동시 요청이 한두 건 더 통과할 수 있음은 수용한다.
- **초기화**: 피보호자가 **수락하면 키를 지운다.** 거절·취소에서는 지우지 말 것 - 요청→거절 반복으로 제한이 풀린다.
- **Redis 장애 시 fail-open**(`[CONN-REQUEST-LIMIT-REDIS-DOWN]` WARN). 관리자 강제 연결에는 적용하지 않는다.
- **알림 비대칭 정책 그대로**: 429는 요청을 만들지 않으므로 알림·WS가 없다. 취소·PENDING 종료에 알림을 추가하지 말 것. 피보호자 차단 기능은 만들지 않았다(별도 결정).


## 피보호자 목록 status 필터 - 좁혀도 인가 목록이 아니다 (2026-09-07)

- **경위**: 프론트의 상태 탭이 클라이언트 필터링 대신 서버 기준으로 목록을 받도록 `GET /api/guardian/connection/select`에 `status`(ACTIVE·PENDING·ALL)를 추가했다(PR #242, 마이그레이션 없음).
- **불변 규칙 ①(인가 목록 아님)**: `getMyWards(guardianId, ACTIVE)`가 결과적으로 ACTIVE 연결만 돌려주지만 **이것을 타 도메인의 IDOR 인가 근거로 쓰지 말 것**. 인가는 계속 `getActiveWardIds()`·`isActiveConnection()`만 쓴다. 이 메서드는 화면 조회용이라 표시 요건(정렬·상태 표기)에 따라 언제든 PENDING을 다시 포함하도록 바뀔 수 있고, 그때 인가가 조용히 넓어진다. SOS·복약·이상감지가 `getMyWards`를 금지해 온 이유가 "지금 PENDING이 섞여서"가 아니라 "이건 인가용 메서드가 아니라서"임을 잊지 말 것.
- **불변 규칙 ②(전용 필터 enum 유지)**: 파라미터는 `ConnectionStatus`가 아니라 전용 `WardListFilter`(ACTIVE·PENDING·ALL)로 받는다. 전체 상태 enum을 받으면 `status=REFUSED`가 유효한 값이라 400이 아니라 **빈 배열**로 응답되고, 호출자는 "거절된 연결이 0건"으로 **정반대 해석**을 한다("0건인 유형은 항목을 만들지 말 것"·"모르는 값을 0으로 채우지 말 것"과 같은 판단이다). 종료된 이력(CANCELLED·REFUSED·DISCONNECTED)은 `/api/guardian/connection/requests`가 담당하며, 편의를 이유로 `/select`에 종료 상태를 열지 말 것.
- **불변 규칙 ③(마스킹은 필터와 무관)**: 연락처·주소·이메일은 `ConnectionResponse`가 `status == ACTIVE`일 때만 채운다. `status=PENDING`으로 좁혀 조회해도 null이며, "이미 PENDING만 골라 왔으니 보여줘도 된다"는 식으로 필터를 근거 삼아 마스킹을 풀지 말 것 - 수락 전 피보호자의 연락처는 보호자에게 노출 대상이 아니다.
- **하위호환**: 파라미터 생략 = 기존 동작(ACTIVE + PENDING). 기본값을 단일 상태로 바꾸면 파라미터 없이 호출하는 기존 프론트 화면이 조용히 비어 보인다.
- 상세: `docs/(2026-09-07) feature-connection-select-status-filter.md`.


## 관리자 강제 연결 - 동의 없이 만드는 관계 (2026-09-10)

- **경위**: 고객센터 문의를 받아 관리자가 대신 연결해 주는 경로를 열었다(`POST /api/admin/connection`, 마이그레이션 없음). 시니어가 수락 버튼을 누르지 못해 가족이 연결하지 못하는 경우가 실제로 있다.
- **불변 규칙 ①(동의의 대체물이 필요하다)**: 일반 연결은 **피보호자의 수락이 곧 동의**인데 강제 연결에는 그것이 없다. 그 대가로 ① **양쪽 모두에게 알리고** ② **감사 로그를 반드시 남긴다**. 둘 중 하나라도 빼면 관리자가 조용히 남의 집을 열어 볼 수 있는 경로가 된다.
  - 특히 **피보호자 알림을 빼지 말 것**. 원래 피보호자에게는 "연결됨" 알림 경로가 없었는데(늘 수락하는 쪽이라 본인이 안다) 강제 연결은 모르는 채로 생기므로 새로 만든 것이다.
- **불변 규칙 ②(문구를 재사용하지 말 것)**: `CONNECTION_ACCEPTED`("피보호자가 수락했습니다")·`DisconnectedBy.GUARDIAN`("보호자가 해제했습니다")를 강제 조작에 쓰면 **알림이 거짓말을 한다.** 그래서 `CONNECTION_FORCED` 종류와 `DisconnectedBy.ADMIN` 값을 따로 두었다. 복약 "체크되지 않았습니다"·이상감지 "화재 감지가 있었습니다"와 같은 판단이다.
  - **탈퇴·역할 변경으로 끊긴 연결도 같은 규칙이다 (2026-09-30, 점검 M-2)**: 탈퇴 정리는 `DisconnectedBy.WITHDRAWN`("보호자가 탈퇴해 연결이 종료되었습니다." / "피보호자가 탈퇴해 연결이 종료되었습니다." - 받는 쪽이 피보호자면 떠난 쪽은 보호자), 관리자 역할 변경 정리는 `DisconnectedBy.ADMIN`("관리자가 연결을 해제했습니다.")으로 발행한다. 예전에는 둘 다 `GUARDIAN`/`WARD`를 재사용해 남은 쪽에게 "상대가 연결을 해제했습니다"가 나갔다 - 탈퇴 쪽은 H-1 때문에 실제로는 발송되지 않다가 2026-09-30 #259로 처음 보이게 됐고, 역할 변경 쪽은 그전부터 발송되고 있었다. `DisconnectedBy`는 이벤트 전용 enum이라 DB CHECK 대상이 아니다(마이그레이션 불요). 새 연결 종료 경로를 만들면 **누가 끊었는지**에 맞는 값을 고를 것.
- **불변 규칙 ③(강제 해제는 양쪽에)**: 당사자가 끊을 때는 반대편에게만 알리지만, 관리자가 끊으면 **둘 다 자기가 끊지 않았다**. 한쪽만 알리면 나머지는 연결이 사라진 것을 모른다. "기존 해제와 같으니 한쪽만"으로 되돌리지 말 것.
- **수락 대기 요청은 승격시킨다**: 같은 쌍의 PENDING이 있으면 새로 만들지 않고 `activate()`한다. 실제 시나리오가 "요청은 갔는데 수락을 못 누른 상태"라 관리자가 대신 수락해 주는 셈이고, 새로 만들면 같은 쌍의 연결이 둘이 된다.
- **이용 제한·탈퇴 진행 계정은 연결 대상이 아니다**(400 `CONNECTION_TARGET_NOT_ACTIVE`) - 로그인도 알림도 되지 않는 계정이라 연결해 두어도 아무것도 동작하지 않는다.
  - **피보호자의 일반 수락 경로도 같은 기준이다 (2026-09-11 회귀 재점검 R-1)**: 요청 뒤 보호자가 정지·탈퇴 진행 상태가 되면 `acceptConnectionAsWard`가 같은 400으로 막는다. 그대로 수락하면 알림은 디스패처가 막지만 연결은 살아 있어 **정지 해제 즉시 SOS·카메라·복약 이력이 열린다.** 요청 시점 검사(`validateConnectionRequest`)만으로는 요청과 수락 사이의 상태 변화를 못 본다.
- **연결 조회 API를 따로 만들지 않았다** - 회원 상세(`GET /api/admin/user/{userId}`)가 이미 그 회원의 연결 전체를 준다. 별도 "연결 관리" 화면이 생기면 그때 판단한다.

## 2026-10-31 전체 점검 반영 (M-6·L-3)

- **피보호자 수락 시 보호자 정지·탈퇴 진행은 `CONNECTION_REQUESTER_NOT_ACTIVE`(400)**: 받는 사람이 피보호자라 요청 쪽 문구(`CONNECTION_TARGET_NOT_ACTIVE`)를 쓰면 뜻이 통하지 않는다(수신자 기준 문구 정책). 보호자 요청·관리자 강제 연결은 `CONNECTION_TARGET_NOT_ACTIVE` 그대로. FE 수락 화면은 모르는 코드면 서버 문구를 보여 준다.
- **강제 연결도 요청 횟수를 지운다**: `forceConnect`가 일반 수락처럼 `ConnectionRequestLimiter.reset`을 부른다 - 강제 해제 뒤 보호자의 정상 재요청이 옛 횟수로 막히지 않게.
- **피보호자 경로 IDOR 문구 분리 (2026-10-31 L-6)**: 피보호자가 남의 연결을 수락·거절·해제하려 하면 `CONNECTION_NOT_OWNED`(403, "본인에게 온 연결 요청만 처리할 수 있습니다."), 보호자 경로는 `CONNECTION_NOT_AUTHORIZED` 그대로. `[IDOR-ATTEMPT]` WARN 유지.
- **연결 요청의 Redis 확인은 잠금 안에 둔다 (L-4, 의도)**: 앞으로 옮기면 409·400보다 429가 먼저 나가 오류 우선순위가 바뀐다. 대기는 Redis 명령 제한 2초로 한정되고 장애 시 fail-open.
