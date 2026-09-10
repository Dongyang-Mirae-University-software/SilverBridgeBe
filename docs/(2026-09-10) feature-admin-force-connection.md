# 관리자 강제 연결·해제

> 2026-09-10 · 선행: 회원관리(PR #243, V47·V48) · 알림 차단(PR #244, V49)
> **마이그레이션 없음** - 감사 액션 `FORCE_CONNECT`·`FORCE_DISCONNECT`가 enum과 V48 CHECK에 이미 있다

---

## 1. 무엇을 만들었나

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/api/admin/connection` | 강제 연결 (`{ guardianId, wardId }`) |
| DELETE | `/api/admin/connection/{connectionId}` | 강제 해제 |

회원관리 상세 모달의 "연결" 영역에서 호출한다. **연결 조회 API는 만들지 않았다** - `GET /api/admin/user/{userId}`가 이미 그 회원의 연결 전체를 돌려준다.

**왜 필요한가**: 고객센터 문의를 받아 관리자가 대신 처리하는 경로다. 시니어가 수락 버튼을 누르지 못해 가족이 연결하지 못하는 경우가 실제로 있다 - 이 서비스의 핵심 UX 문제 그대로다.

---

## 2. 강제 연결은 동의 없이 관계를 만든다

일반 연결은 보호자 요청 → 피보호자 수락 2단계이고, **그 수락이 곧 동의**다. 강제 연결은 그 동의 없이 SOS·카메라·복약·위치 이력을 열어 준다. 이 서비스에서 가장 민감한 조작이라 두 가지를 붙였다.

1. **양쪽 모두에게 알린다** (§3)
2. **감사 로그를 반드시 남긴다** - 개인 관계를 만드는 조작이라 "누가 언제 누구를 연결했는가"가 남아야 한다

---

## 3. 기존 알림을 재사용하면 거짓말이 된다

착수 전 확인에서 드러난 가장 중요한 지점이다.

| 재사용 후보 | 나갔을 문구 | 실제 |
|---|---|---|
| `ConnectionAcceptedEvent` | "피보호자가 연결 요청을 **수락했습니다**" | 수락한 적 없다 |
| `ConnectionDisconnectedEvent` | "보호자가 연결을 **해제했습니다**" | 관리자가 했다 |

이 프로젝트는 문구 정확성에 규칙이 여럿 있다 - 복약은 "안 드셨습니다"가 아니라 **"체크되지 않았습니다"**, 이상감지는 "화재가 발생했습니다"가 아니라 **"화재 감지가 있었습니다"**. 같은 판단을 적용했다.

### 강제 연결 - 새 종류를 만들었다

`NotificationType.CONNECTION_FORCED`(`SETTINGS_ONLY`) + `ConnectionForcedEvent` 신설.

- 보호자: **"관리자가 박민수님과의 연결을 완료했습니다."**
- 피보호자: **"관리자가 홍길동님을 보호자로 연결했습니다."**

**피보호자에게 "연결됨"을 알리는 경로가 원래 없었다.** 늘 수락하는 쪽이라 본인이 이미 알기 때문이다. 강제 연결은 모르는 채로 생기므로 새로 필요했다.

`NotificationType`은 DB에 저장되지 않아 값 추가에 **마이그레이션이 필요 없다**(오늘 두 번 걸린 CHECK 함정이 여기엔 없다).

### 강제 해제 - 기존 이벤트에 값만 추가

`DisconnectedBy.ADMIN`을 더해 **"관리자가 연결을 해제했습니다."** 로 분기시켰다.

그리고 **양쪽 모두에게** 보낸다. 당사자가 끊을 때는 "해제하지 않은 반대편"에게만 보내면 되지만, 관리자가 끊으면 **둘 다 자기가 끊지 않았다.** 한쪽만 알리면 나머지는 연결이 사라진 것을 모른다.

---

## 4. 수락 대기 요청이 있으면 승격시킨다

같은 쌍의 `PENDING` 요청이 있으면 새로 만들지 않고 그것을 `activate()` 한다.

**실제 시나리오가 정확히 이것이다** - 보호자가 요청을 보냈는데 시니어가 수락 버튼을 누르지 못해 요청만 떠 있는 상태. 관리자가 **대신 수락해 주는** 셈이라 의미도 맞고, 새로 만들면 같은 쌍의 연결이 둘이 된다.

---

## 5. 거부하는 경우

| 조건 | 응답 |
|---|---|
| 역할 불일치(보호자↔피보호자가 아님) | 400 `INVALID_CONNECTION_ROLE` |
| 이용 제한·탈퇴 진행 계정 | 400 `CONNECTION_TARGET_NOT_ACTIVE` |
| 이미 ACTIVE 연결 | 409 `CONNECTION_ALREADY_EXISTS` |
| 없는 회원 | 404 |
| 해제 대상이 ACTIVE가 아님 | 409 `CONNECTION_NOT_ACTIVE` |

**정지 계정을 연결 대상에서 뺀 이유**: 로그인도 알림도 되지 않는 계정이라(PR #244) 연결해 두어도 아무것도 동작하지 않는다. 관리자 계정은 역할 검사에서 자동으로 걸린다.

**중복 검사 기준이 옛 구현과 다르다.** 삭제됐던 코드는 `StatusNot(CANCELLED)`였는데, V23으로 `REFUSED`·`DISCONNECTED`가 생기면서 "종료된 연결은 재요청 허용"이 정책이 됐다. 지금 기준은 `ACTIVE` 존재 여부다(PENDING은 승격 대상이라 막지 않는다).

`relation`은 비워 둔다 - 관리자는 두 사람의 가족 관계를 알 수 없다.

---

## 6. 구조

상태 전이는 `ConnectionService.forceConnect()`·`forceDisconnect()`가, 검증·감사 로그는 `AdminConnectionService`가 맡는다. 연결 도메인이 자기 상태를 소유하도록 나눴고, `AdminUserService`가 `tearDownConnectionsOnRoleChange`를 위임하는 것과 같은 방식이다.

---

## 7. 변경 파일

**신규**
```
admin/controller/AdminConnectionController.java
admin/service/AdminConnectionService.java
admin/dto/AdminForceConnectRequest · AdminConnectionResponse
connection/event/ConnectionForcedEvent.java
test/AdminConnectionServiceTest.java
```

**수정**
```
connection/service/ConnectionService            + forceConnect · forceDisconnect
connection/event/ConnectionDisconnectedEvent    + DisconnectedBy.ADMIN
connection/listener/ConnectionNotificationListener + handleForced, 해제 문구 분기
notification/dispatch/NotificationType          + CONNECTION_FORCED
global/exception/ErrorCode                      + CONNECTION_TARGET_NOT_ACTIVE
global/config/SwaggerConfig                     태그·순서 등록
test/ConnectionServiceTest
```

---

## 8. 검증

- `./gradlew build` **555건 / 실패 0** (기존 543 + 신규 12)
- 테스트로 고정한 것: PENDING 승격 / 없으면 신규 생성 / 강제 연결 시 양쪽 알림 이벤트 / 강제 해제 시 **양쪽에 ADMIN 주체로** 알림 / ACTIVE 아닌 연결 해제 거부 / 역할 불일치·정지 계정·중복·없는 회원 거부 시 **연결도 감사 로그도 남지 않음**

---

## 9. 프론트가 할 일

회원 상세 모달의 "연결 상태" 영역이 지금 읽기 전용인데, 여기에 붙인다.

- 각 연결 행에 **"연결 해제"** 버튼
- 목록 아래 **"+ 피보호자 연결"**(피보호자 모달이면 "+ 보호자 연결")
- 상대 고르기는 **새 API가 필요 없다** - `GET /api/admin/user?keyword=&role=WARD&status=ACTIVE`를 피커로 재사용하면 된다
- **확인 다이얼로그 필수** - 동의 없이 정보를 여는 조작이고 양쪽에 알림이 나간다는 것을 밝힐 것
