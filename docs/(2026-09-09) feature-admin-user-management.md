# 관리자 회원 관리

> 2026-09-09 · 근거: 관리자 콘솔 회원관리 프로토타입 (2026-09-09 제시)
> 마이그레이션: **V47**(users.status에 RESTRICTED) · **V48**(admin_audit_log.action에 USER_NAME_CHANGE)
> 복원 기반: `a5b6328^` - 2026-05-15에 만들었다가 2026-06-11에 제거된 회원관리 구현

---

## 1. 무엇을 만들었나

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/api/admin/user` | 목록 - 검색(이름·이메일·전화·**연결 회원 이름**), 역할·계정 상태·연결 상태 필터, 페이징 |
| GET | `/api/admin/user/counts` | 탭별 건수 (전체·보호자·피보호자·관리자) |
| GET | `/api/admin/user/{userId}` | 상세 + 연결 전체 목록 |
| PATCH | `/api/admin/user/{userId}` | 정보 수정 (이름·역할·계정 상태, null=변경 안 함) |
| DELETE | `/api/admin/user/{userId}` | 강제 탈퇴 |

계정 상태는 **이용 중(`ACTIVE`) / 이용 제한(`RESTRICTED`) / 삭제(강제 탈퇴)** 세 가지입니다. "비활성화"는 이미 삭제가 있어 의미가 없다고 판단해 넣지 않았습니다.

---

## 2. 백지 구현이 아니라 복원이었다

회원관리 API는 2026-05-15에 완성됐다가(PR #121·#122) 2026-06-11 "프론트에서 검증되지 않은 API 정리"(`a5b6328`)에서 통째로 제거됐습니다. `UserRepository:43`에 **"회원관리 기능 구현 시 git 이력에서 복원할 것"** 이라는 주석까지 남아 있었습니다.

그래서 착수 전(PHASE 0)에 옛 구현과 현재 정책을 대조했고, **그대로 되살리면 안 되는 것이 여섯 개** 나왔습니다.

| # | 옛 구현 | 지금 | 결론 |
|---|---|---|---|
| 1 | 상태 토글 `ACTIVE ↔ INACTIVE` | INACTIVE는 탈퇴 전용, 스윕이 10분 뒤 영구 삭제 | `RESTRICTED` 신설 |
| 2 | (해당 없음) | V4가 `chk_users_status`를 두 값으로 좁혀 놓음 | **V47에서 CHECK 재정의** |
| 3 | 로그인 차단 `status == INACTIVE` (3곳) | RESTRICTED가 그대로 통과 | `!= ACTIVE`로 교체 |
| 4 | (해당 없음) | `JwtAuthenticationFilter`가 DB를 안 읽음 | 정지 시 토큰 무효화 이벤트 추가 |
| 5 | 역할 변경 시 연결 전부 `cancel()` | CANCELLED/DISCONNECTED로 뜻이 갈림 | ACTIVE는 `disconnect()`+알림 |
| 6 | 강제 탈퇴 = `userRepository.delete()` | 탈퇴는 2단계 파이프라인 | 파이프라인 재사용 |

---

## 3. 정지(RESTRICTED)를 새 상태로 만든 이유

2026-06-11의 **INACTIVE 불변식**이 정확히 이 경우를 예고해 뒀습니다 - "관리자 계정 제한·휴면 등 다른 용도로 INACTIVE를 재사용하면 해당 계정이 스윕에 삭제된다. 그런 기능 도입 시 반드시 별도 상태값(예: RESTRICTED)을 추가할 것."

`WithdrawnUserPurgeScheduler`는 `updated_at`이 10분 지난 INACTIVE 행을 좀비로 보고 **영구 삭제**합니다. 정지에 INACTIVE를 쓰면 정지시킨 계정이 20분 안에 사라집니다.

### V47이 필요한 이유 (enum만 늘리면 안 된다)

`users.status`에 CHECK가 걸려 있고 **V4가 허용 목록을 `ACTIVE`·`INACTIVE`로 좁혀** 놨습니다. `Status` enum에 값만 더하면 정지 UPDATE가 CHECK 위반(23514)으로 실패하고 500이 납니다. `admin_audit_log.action`에서 이미 두 번 터진 함정(C-S3-1, V46)과 같은 종류입니다.

재발을 막으려고 **`UserStatusCheckSyncTest`** 를 추가했습니다 - `AdminAuditActionCheckSyncTest`와 같은 방식으로 enum 전수와 최신 마이그레이션의 CHECK를 대조합니다. 앞으로 `Status`에 값을 더하면서 마이그레이션을 빼먹으면 테스트가 먼저 실패합니다.

### 정지는 즉시 들어야 한다

상태만 바꾸면 로그인·토큰 재발급은 막히지만 **이미 발급된 access token이 만료(30분)까지 살아 있습니다** - `JwtAuthenticationFilter`가 요청마다 DB를 읽지 않기 때문입니다. 그래서 `UserRestrictedEvent`를 발행해 탈퇴·비밀번호 변경과 같은 무효화 경로(refresh 토큰 삭제 + Redis 무효화 키)를 태웁니다.

### 로그인 차단은 등호가 아니라 부등호로

`AuthService`(로그인·재발급) 2곳과 `KakaoAuthService` 1곳이 `status == INACTIVE`로 비교하고 있었습니다. 이대로면 RESTRICTED 계정이 **그대로 로그인됩니다**. `!= Status.ACTIVE`로 바꿨습니다 - 새 상태값이 늘 때마다 조용히 뚫리는 형태를 없앤 것입니다.

응답은 기존 `INACTIVE_USER`("사용이 제한된 계정입니다. 고객센터에 문의해주세요.")를 그대로 씁니다. 문구가 이미 정지에 들어맞고, 탈퇴 INACTIVE는 purge로 사라져 실제로 이 문구를 볼 일이 거의 없습니다.

---

## 4. 역할 변경은 연결을 정리하고, 상대에게 알린다

역할이 뒤집히면 기존 연결의 보호자-피보호자 방향이 어긋나 관계가 뜻을 잃습니다. `ConnectionService.tearDownConnectionsOnRoleChange()`가 탈퇴 정리(`tearDownConnectionsOnWithdrawal`)와 **같은 규칙**을 따릅니다.

- **ACTIVE** → `disconnect()` + `ConnectionDisconnectedEvent` (상대에게 해제 알림)
- **PENDING** → `cancel()` (무알림)

PENDING까지 알리지 않는 이유는 2026-05-28의 알림 비대칭 정책입니다 - 수락 전 요청의 종료는 상대에게 통지하지 않기로 이미 정해져 있습니다. 실제로 연결돼 있던 사람만 상대가 사라진 것을 알아야 합니다.

옛 구현은 ACTIVE도 `cancel()`로 끝냈는데, 지금은 `CANCELLED`가 "보호자가 수락 전 요청을 스스로 취소", `DISCONNECTED`가 "ACTIVE 연결이 해제됨"으로 뜻이 갈려 있어 그대로 두면 이력이 사실과 달라집니다.

**ADMIN으로는 바꿀 수 없습니다**(400 `INVALID_ROLE`). 회원관리 화면 하나로 권한을 만들어낼 수 있게 되기 때문입니다.

---

## 5. 강제 탈퇴는 일반 탈퇴와 같은 길로 간다

옛 구현은 `userRepository.delete(user)`로 곧장 지웠습니다. 지금 그렇게 하면 AFTER_COMMIT 리스너를 건너뛰어 **연결 상대 알림·FCM 토큰 정리·WITHDRAW 접속로그가 통째로 유실**됩니다. 행 정리는 FK CASCADE가 해버려 겉보기엔 성공한 것처럼 보이는 게 더 나쁩니다.

그래서 `UserService.forceWithdraw()`를 추가했습니다 - 일반 탈퇴에서 **본인 확인만 뺀** 것이고, 나머지(상태 전환 + `UserWithdrawnEvent` → 리스너 3종 → `purgeWithdrawnUser`)는 동일합니다. purge 실패 시 1회 재시도하고, 그래도 실패하면 기존 스윕 스케줄러가 회수합니다.

**감사 로그는 삭제보다 먼저** 남깁니다. 뒤에 남기면 기록이 실패했을 때 계정만 사라지고 누가 지웠는지가 남지 않습니다. 반대 순서의 실패(로그만 남고 삭제 실패)는 스윕이 정리하거나 아무 일도 일어나지 않습니다.

---

## 6. 수정할 수 있는 것은 이름뿐이다

프로토타입의 수정 모달에는 이메일·전화번호 입력창이 있었지만, **둘 다 읽기 전용**으로 정했습니다.

- **전화번호** - 본인이 바꿀 때는 SMS 인증 nonce 소비가 필수입니다(`UserService:63`, H-5). 관리자 경로를 열면 그 인증을 통째로 우회하고, 잘못 넣으면 SOS·복약 문자가 **남의 번호로** 갑니다.
- **이메일** - 지금 본인조차 바꿀 수 없는 값입니다(`updateProfile`에 필드가 없습니다). 관리자에게 열면 시스템에서 유일한 로그인 ID 변경 경로가 됩니다.

이름은 인증 매개체가 아니고 오타 정정 수요가 실제로 있어 허용했습니다. 대신 `USER_NAME_CHANGE` 감사 액션을 추가했고, 그래서 **V48**(CHECK 재정의)이 딸려 왔습니다.

### 저장은 한 번, 감사 로그는 축별로

화면의 "저장"이 한 번이라 엔드포인트를 셋으로 쪼개면 프론트가 저장 한 번에 세 번 호출하고 부분 실패가 생깁니다. 그래서 `PATCH` 하나로 받되, **실제로 바뀐 축마다 따로** 감사 로그를 남깁니다. 하나로 합치면 나중에 무엇이 바뀌었는지 가려낼 수 없습니다.

값이 그대로면 감사 로그를 남기지 않습니다 - 저장만 눌러도 이력이 쌓이면 진짜 변경이 묻힙니다.

---

## 7. 목록에서 정한 것들

### 관리자 계정도 목록에 나온다 (수정은 안 된다)

탭이 `전체 / 보호자 / 피보호자 / 관리자` 네 개라 ADMIN도 모집단에 포함합니다. 다만 수정·삭제 요청은 403(`CANNOT_MODIFY_ADMIN`)입니다. 관리자가 관리자를 지울 수 있으면 서로를 지워 운영 주체가 사라집니다.

### 탈퇴 진행 중인 계정은 목록에 없다

`INACTIVE` 행은 purge 대기 중인 임시 상태라 관리자가 할 수 있는 일이 없고 곧 사라집니다. 목록과 탭 건수 모두 같은 모집단을 씁니다.

### 연결 상태는 우선순위로 하나를 고른다

한 회원이 연결됨과 수락 대기를 동시에 가질 수 있습니다. **ACTIVE가 하나라도 있으면 CONNECTED, 없고 PENDING만 있으면 PENDING, 둘 다 없으면 NONE.** 필터(JPQL의 CASE)와 표시(`AdminUserConnectionStates`)가 같은 규칙을 써야 "연결됨으로 걸렀는데 수락 대기로 보인다"가 없습니다.

**관리자 계정의 연결 상태는 NONE이 아니라 `null`입니다.** 연결이 0건인 게 아니라 연결이라는 축 자체가 없는 계정이라, NONE("미연결")으로 표시하면 "연결이 끊긴 회원"으로 정확히 반대로 읽힙니다. "모르는 값을 0으로 채우지 않는다"(2026-09-02)와 같은 판단입니다.

### 필터는 전용 enum, 수정 요청은 도메인 enum

- **필터**(`AdminUserStatusFilter`) - `Status`를 그대로 열면 `status=INACTIVE`가 유효한 값이라 400이 아니라 **빈 배열**로 응답되고, 호출자는 "탈퇴 회원 0명"으로 정반대로 읽습니다(`WardListFilter`, 2026-09-07과 같은 판단).
- **수정 요청** - `Status`를 그대로 받되 `INACTIVE`는 400(`INVALID_STATUS`)입니다. 쓰기 요청에서는 조용한 빈 결과가 아니라 **분명한 거절**이 되고, "탈퇴는 상태 변경이 아니라 삭제"라는 메시지를 정확히 전달할 수 있습니다.

### 연결 조회는 한 번에

목록 20건에 대해 연결을 행마다 조회하면 N+1입니다. `findByParticipantsAndStatusIn`으로 한 번에 읽어 배분하되, **양쪽 참여자가 모두 조회 대상일 수 있어** 두 방향을 모두 확인합니다(홍길동과 박민수가 같은 페이지에 있으면 그 연결은 두 행 모두에 붙어야 합니다).

---

## 8. 관계(relation)의 방향

프로토타입 1차에는 보호자 행에 `박민수 (부)`, 피보호자 행에 `홍길동 (아들)`처럼 **양방향 라벨**이 있었습니다. `connections.relation`(V19)은 한 방향만 저장합니다 - 알림 문구가 `"아들 박민수님이 연결을 요청했어요"` 인 데서 보이듯 **보호자를 가리키는 라벨**입니다. 반대 라벨은 어디에도 없고, 뒤집는 매핑(아들→부)은 성별·다의성 때문에 안전하지 않습니다.

2차 프로토타입에서 `이 회원은 박민수님의 아들` 처럼 문장으로 풀어 방향이 맞춰졌습니다. 서버는 `relation` 원본과 `counterpartRole`을 내리고, 방향 해석은 Swagger 설명에 명시했습니다 - `counterpartRole`이 GUARDIAN이면 relation은 상대방을, WARD이면 조회 대상 회원을 가리킵니다.

새 관계 선택지 13개(아들·딸·며느리·사위·손자·손녀·외손자·외손녀·배우자·조카·형제·자매·기타)는 모두 10자 이내라 기존 `@Size(max=10)` 제약에 걸리지 않습니다. **백엔드 변경 없습니다.**

---

## 9. 범위 밖으로 둔 것

- **강제 연결·강제 해제**(`FORCE_CONNECT`·`FORCE_DISCONNECT`) - enum과 CHECK에는 이미 있지만 API는 만들지 않았습니다. 회원 축과 연결 축은 화면도 다르고, 강제 연결은 상대 동의 없이 관계를 만드는 것이라 알림 정책을 따로 판단해야 합니다.
- **대시보드 집계** - `AdminDashboardService`가 `Status.ACTIVE`만 세므로 **정지 계정은 "총 회원 수"에서 빠집니다.** 대시보드는 계약이 따로 잡힌 화면이라 여기서 슬쩍 바꾸면 PR ③의 결정을 근거 없이 뒤집는 꼴이 됩니다. 정지 계정이 늘어나 지표가 흔들리면 그때 대시보드 계약으로 다루면 됩니다.
- **피보호자가 보내는 연결 요청** - 지금 연결 요청은 보호자만 보낼 수 있습니다(`POST /api/guardian/connection/request`). 피보호자 웹에서 보호자를 등록하는 화면이 실제로 요청을 *보내는* 것이라면 대응 엔드포인트가 없습니다. 별건입니다.

---

## 10. 변경 파일

**신규**
```
db/migration/V47__add_user_restricted_status.sql
db/migration/V48__add_user_name_change_audit_action.sql
domain/admin/controller/AdminUserController.java
domain/admin/service/AdminUserService.java
domain/admin/dto/  AdminUserListItem · AdminUserDetailResponse · AdminUserCountsResponse
                   AdminUserUpdateRequest · AdminUserConnectionItem
                   AdminUserStatusFilter · AdminUserConnectionFilter
                   AdminUserConnectionState · AdminUserConnectionStates
domain/user/event/UserRestrictedEvent.java
test/  AdminUserServiceTest · UserStatusCheckSyncTest
```

**수정**
```
global/enums/Status.java              + RESTRICTED
global/enums/AdminAuditAction.java    + USER_NAME_CHANGE
global/config/SwaggerConfig.java      태그·순서 등록
domain/user/entity/User.java          + restrict() · changeName()
domain/user/service/UserService.java  + forceWithdraw()
domain/user/repository/UserRepository.java     관리자 검색·탭 건수 쿼리
domain/auth/service/AuthService.java           차단 조건 2곳
domain/auth/service/KakaoAuthService.java      차단 조건 1곳
domain/auth/listener/UserAccountEventListener.java  + handleRestricted
domain/connection/service/ConnectionService.java    + tearDownConnectionsOnRoleChange
domain/connection/repository/ConnectionRepository.java  + 벌크 조회
test/  ConnectionServiceTest · AuthServiceTest
```

---

## 11. 검증

- `./gradlew build` **533건 / 실패 0** (기존 508 + 신규 25)
- **마이그레이션은 gosky dev DB에서 트랜잭션 실행 후 롤백으로 사전 검증**했습니다. 확인한 것:
  1. V47 적용 후 `status='RESTRICTED'` UPDATE가 통과
  2. V48 적용 후 `USER_NAME_CHANGE` 감사 로그 insert가 통과
  3. 정의되지 않은 값(`DORMANT`)은 **여전히 CHECK 위반으로 거부**
  4. 롤백 후 제약이 원상 복구되고 RESTRICTED 행 0건

- 테스트로 고정한 것: 관리자 계정 수정·삭제 차단(상태·감사 로그 둘 다 안 바뀜) / ADMIN 승격 차단 / INACTIVE 지정 차단 / 정지 시 토큰 무효화 이벤트 / 해제 시에는 이벤트 없음 / 역할 변경의 연결 정리와 건수 / ACTIVE는 DISCONNECTED·PENDING은 CANCELLED / 강제 탈퇴의 2단계 경로와 감사 로그 순서 / purge 실패 재시도 / 관리자 계정 연결 상태 null / 0건 역할도 탭 키 유지 / RESTRICTED 로그인 차단 / Status↔CHECK 동기화

---

## 12. 프론트 영향

**기존 API 무변경**입니다. 새 엔드포인트 5개만 추가됐고, 관리자 화면은 아직 이 리포의 프론트(`../SilverBridgeFe`)에 없습니다.

다만 **회원 상태가 세 가지가 됐다**는 사실은 모든 화면에 영향을 줄 수 있습니다 - 정지된 계정은 로그인·토큰 재발급이 403(`INACTIVE_USER`)이고, 정지 즉시 기존 토큰도 무효화되어 사용 중이던 사용자는 다음 요청에서 401을 받습니다.
