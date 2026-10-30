---
paths:
  - "**/domain/admin/**"
  - "**/hospital/**"
---

# 도메인 정책 - 관리자 회원·병원·대시보드

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

## 기능 요약 (CLAUDE.md §1에서 이동, 2026-10-30)

- **관리자 제휴 병원 관리**(admin·hospital, 2026-10-26, V63 감사 로그 CHECK만): `GET /api/admin/hospital?q=&region=&department=`·`/summary`·`POST`·`PUT /{id}`·`DELETE /{id}`(소프트)·`PUT /{id}/status`·진료과 추가·이름 변경·삭제(ADMIN). 데이터는 예약 서비스에 있고 BE는 **전용 ADMIN 계정으로 8개 경로만 중계**(공용 `ADMIN_PANEL_PASSWORD` 금지, 주소·자격 기본값 없음 → 비면 503, vkcs는 503 정상), 메모리 필터·페이지, 쓰기 전 삭제·소유·중복 확인, 감사 로그는 호출 전, 병원 이메일·우리 사용자 예약 수 없음. gosky는 `docker-compose.gosky.yml`(sb-internal). 상세 rules 파일·`docs/(2026-10-26) feature-admin-partner-hospital.md`.


## 관리자 회원관리 - 정지는 별도 상태, 삭제는 탈퇴 경로 (2026-09-09)

- **경위**: 2026-05-15에 만들었다가 2026-06-11에 제거된 회원관리 API를 관리자 콘솔 프로토타입에 맞춰 복원했다(V47·V48). 옛 구현을 그대로 되살리면 안 되는 지점이 여섯 개 있었고, 그중 셋은 계정을 지우거나 정지를 무력화하는 것이었다.
- **불변 규칙 ①(정지는 RESTRICTED, INACTIVE 재사용 금지)**: 계정 상태는 **이용 중(`ACTIVE`) / 이용 제한(`RESTRICTED`) / 삭제(강제 탈퇴)** 세 가지다. 정지를 `INACTIVE`로 표현하지 말 것 - `WithdrawnUserPurgeScheduler`가 `updated_at`이 10분 지난 INACTIVE 행을 좀비로 보고 **영구 삭제**하므로 정지시킨 계정이 20분 안에 사라진다(2026-06-11 INACTIVE 불변식이 예고한 바로 그 경우다). 수정 API가 `INACTIVE`를 받으면 400(`INVALID_STATUS`)이다 - 탈퇴는 상태 변경이 아니라 삭제다.
  - "비활성화" 상태를 다시 만들지 말 것. 이미 삭제가 있어 의미가 없다고 판단해 넣지 않았다.
  - ⚠️ **`Status` enum에 값을 더할 때는 반드시 `chk_users_status` 재정의 마이그레이션을 함께** 넣는다. V4가 허용 목록을 `ACTIVE`·`INACTIVE`로 좁혀 놔서 enum만 늘리면 UPDATE가 CHECK 위반(23514)으로 실패하고 본 작업까지 롤백돼 500이 난다(`admin_audit_log.action`의 C-S3-1·V46과 같은 함정). `UserStatusCheckSyncTest`가 enum 전수와 CHECK를 대조해 막는다.
- **불변 규칙 ②(로그인 차단은 부등호로)**: 계정 상태 검사는 `status != Status.ACTIVE`로 한다. `== INACTIVE` 등호 비교로 되돌리지 말 것 - 새 상태값이 늘 때마다 조용히 로그인이 뚫린다(실제로 RESTRICTED 도입 시점에 `AuthService` 2곳·`KakaoAuthService` 1곳이 그 상태였다). 응답은 `INACTIVE_USER`(403)를 공용으로 쓴다.
  - **정지는 토큰까지 끊어야 즉시 듣는다**: `JwtAuthenticationFilter`가 요청마다 DB를 읽지 않아 상태만 바꾸면 기존 access token이 만료(30분)까지 유효하다. `UserRestrictedEvent` → `UserAccountEventListener.handleRestricted`가 refresh 삭제 + Redis 무효화 키를 세운다(탈퇴·비밀번호 변경과 같은 경로). 이 이벤트를 떼지 말 것.
- **불변 규칙 ③(강제 탈퇴도 탈퇴 파이프라인을 탄다)**: 관리자 삭제는 `UserService.forceWithdraw()`(본인 확인만 없는 일반 탈퇴) → AFTER_COMMIT 리스너 3종 → `purgeWithdrawnUser()` 2단계다. **`userRepository.delete()`를 직접 부르지 말 것** - 리스너를 건너뛰어 연결 상대 알림·FCM 토큰 정리·WITHDRAW 접속로그가 유실되는데, 행 정리는 FK CASCADE가 해버려 겉보기엔 성공한 것처럼 보인다.
  - **감사 로그는 삭제보다 먼저 남긴다.** 뒤에 남기면 기록이 실패했을 때 계정만 사라지고 누가 지웠는지가 남지 않는다.
- **불변 규칙 ④(관리자 계정은 조회만)**: 대상이 ADMIN이면 수정·삭제 모두 403(`CANNOT_MODIFY_ADMIN`)이고 `[ADMIN-MODIFY-BLOCKED]` WARN을 남긴다. 관리자가 관리자를 지울 수 있으면 서로를 지워 운영 주체가 사라진다. **역할 변경으로 ADMIN을 만들 수도 없다**(400 `INVALID_ROLE`) - 회원관리 화면 하나로 권한을 만들어낼 수 있게 된다.
- **불변 규칙 ⑤(역할 변경은 연결을 정리하고 ACTIVE만 알린다)**: 역할이 뒤집히면 보호자-피보호자 방향이 어긋나 관계가 뜻을 잃으므로 기존 연결을 정리한다. **ACTIVE는 `disconnect()` + `ConnectionDisconnectedEvent`(상대 알림), PENDING은 `cancel()`(무알림)** - 탈퇴 정리와 같은 규칙이며 PENDING 무알림은 2026-05-28 알림 비대칭 정책 그대로다. ACTIVE를 `cancel()`로 끝내지 말 것(CANCELLED는 "수락 전 요청을 스스로 취소"라는 뜻이라 이력이 사실과 달라진다).
  - **카메라도 함께 삭제한다 (2026-09-10 점검 M-3, 결정)**: 카메라는 피보호자 자산이라 보호자가 된 뒤에는 카메라 API(WARD 전용)를 쓸 수 없어 **아무도 지울 수 없는 고아**가 되고, AI 구독과 본인 화재 알림은 계속된다. 역할 변경 시 `CameraService.deleteAllByWard`로 전부 지우고 감사 로그 detail에 건수를 남긴다. 다시 쓰려면 피보호자 계정으로 재등록해야 한다(장치의 sessionId 재설정 포함). "카메라가 있으면 400으로 막자"는 안은 실사용 기준으로 검토했으나, 역할 변경이 필요한 경우가 대개 "가족이 실수로 피보호자로 가입"이라 초기화 후 재등록으로 확정했다.
  - **피보호자로서 갖고 있던 약도 중지한다 (2026-09-30 전체 점검 M-1)**: 카메라와 같은 이유다. 남겨 두면 알림 스케줄러가 계속 "약 드실 시간"을 보내는데 본인은 체크(피보호자 전용)도 삭제(연결된 보호자 전용)도 할 수 없다. `MedicationRoleChangeService.stopAllOwnedByWard`가 soft delete하고 감사 로그 detail에 건수를 남긴다. **그 사람이 보호자로서 남에게 등록해 준 약은 건드리지 않는다** - 일반 연결 해제 때도 남는 것과 같은 기준이다. 남는 설정 행(`medication_setting` 등)은 동작에 영향이 없어 두었다.
  - **토큰도 끊는다 (2026-09-10 점검 M-1)**: access token은 발급 시점의 role 클레임으로 권한을 만들어, 역할만 바꾸면 옛 역할의 토큰이 만료(30분)까지 `@PreAuthorize`를 통과한다. `UserRoleChangedEvent` → `UserAccountEventListener.handleRoleChanged`가 정지와 같은 무효화 경로를 탄다. 이 이벤트를 떼지 말 것.
  - **탈퇴 진행 중(INACTIVE) 계정은 상세·수정·삭제에서도 404다 (2026-09-10 점검 M-2)**: 목록에서만 빼면 ID를 아는 관리자가 `status=ACTIVE`로 되돌려 이미 토큰·연결·FCM이 정리된 반쪽 계정을 되살릴 수 있고, 스윕(INACTIVE만 회수)도 더는 지우지 않는다. `AdminUserService.getUserOrThrow`가 INACTIVE를 걸러낸다 - 목록과 같은 모집단.
- **불변 규칙 ⑥(관리자도 이메일·전화번호는 못 바꾼다)**: 수정 대상은 **이름·역할·계정 상태 3가지뿐**이다.
  - **전화번호** - 본인 경로는 SMS 인증 nonce 소비가 필수라(H-5) 관리자 경로를 열면 그 인증을 통째로 우회한다. 게다가 전화번호는 SOS·복약 문자가 실제로 도착하는 곳이라 오입력이 긴급 알림을 남에게 보낸다.
  - **이메일** - 본인조차 바꿀 수 없는 로그인 ID다. 관리자에게 열면 시스템에서 유일한 이메일 변경 경로가 된다.
  - 이름 수정은 감사 로그(`USER_NAME_CHANGE`)를 남긴다. **`AdminAuditAction`에 값을 더할 때는 CHECK 재정의 마이그레이션을 함께** 넣는다(V48).
- **관리자 계정의 연결 상태는 `null`이다**: 연결이 0건인 것이 아니라 **연결이라는 축 자체가 없는** 계정이라, NONE("미연결")으로 표시하면 "연결이 끊긴 회원"으로 정확히 반대로 읽힌다. 대시보드의 "모르는 값을 0으로 채우지 않는다"(2026-09-02)와 같은 판단이다.
- **필터 enum과 요청 enum을 같게 만들지 말 것**: 목록 필터는 전용 `AdminUserStatusFilter`(ALL·ACTIVE·RESTRICTED)로 받는다 - `Status`를 그대로 열면 `status=INACTIVE`가 400이 아니라 **빈 배열**로 응답돼 "탈퇴 회원 0명"으로 정반대 해석을 낳는다(`WardListFilter` 2026-09-07과 같은 판단). 반면 수정 요청은 `Status`를 받아 `INACTIVE`를 분명히 400으로 거절한다 - 쓰기에서는 조용한 빈 결과가 아니라 명시적 거절이 필요하고, "탈퇴는 상태가 아니라 삭제"를 전달해야 하기 때문이다.
- **정지 계정은 대시보드 "총 회원 수"에서 빠진다(알려진 한계)**: `AdminDashboardService`가 `Status.ACTIVE`만 센다. 대시보드는 계약이 따로 잡힌 화면이라 회원관리 PR에서 슬쩍 바꾸지 않았다. 정지 계정이 늘어 지표가 흔들리면 대시보드 계약으로 다룰 것.
- **`connections.relation`은 한 방향뿐이다**: 값은 **보호자가 피보호자에게 어떤 사람인지**를 가리킨다("아들"). 반대 라벨(피보호자가 보호자에게 무엇인지)은 저장되지 않으며, 뒤집는 매핑은 성별·다의성 때문에 안전하지 않다. 화면에서 "{상대이름} ({relation})"으로 붙이면 relation이 상대를 가리키는 것처럼 읽히므로 문장으로 풀어 쓴다("이 회원은 박민수님의 아들"). 양방향 라벨이 필요하면 스키마 변경과 연결 요청 화면 수정이 선행되어야 한다.
- **불변 규칙 ⑦(정지 계정에는 알림을 보내지 않는다, 2026-09-09 추가)**: 이용 제한·탈퇴 진행 계정이 **수신자**면 어떤 채널로도 발송하지 않는다 - **강제 FCM도 예외가 아니다**(`NotificationDispatcher.resolveIfAllowed`). 정지는 탈취 의심 시의 임시 조치라, 알림을 계속 보내면 그 계정을 쥔 사람에게 피보호자의 SOS 발생·화재 감지 같은 생활 상황을 실시간으로 통보하게 된다. "필수 알림이니 예외" 논리로 되돌리지 말 것.
  - **막는 기준은 수신자다.** 정지된 피보호자가 **원인**인 알림(그 집의 화재·SOS)은 보호자들에게 그대로 나간다. 계정 정지는 이용 제한이지 안전망 해제가 아니다.
  - **차단은 `dispatch()` 한 곳에서만** 한다. 정책 분기마다 흩어 놓으면 하나를 고칠 때 나머지가 어긋난다. 단 `SETTINGS_ONLY`의 "활성 채널 0건이면 수신자 조회조차 안 한다" 최적화는 유지한다(채널 계산 → 조회·차단 순서).
  - **상태를 모르면(사용자 행 없음) 막지 않는다** - "정지"가 아니라 "알 수 없음"이다.
  - **수용한 위험**: 보호자가 한 명뿐인데 정지 상태면 그 피보호자의 SOS가 아무에게도 안 간다. `[NOTIFY-BLOCKED]` WARN이 유일한 흔적이다.
  - **WebSocket도 같은 기준이다 (2026-10-02, ANOM-G10·ADMIN-G28)**: `WebSocketEventPublisher.sendToUser`가 발송 직전에 수신자 상태를 PK로 조회해 ACTIVE가 아니면 보내지 않는다(`[WS-BLOCKED]` INFO, userId만). 사용자 행이 없거나 조회가 실패하면 상태를 모르는 것이라 막지 않는다. 정지 즉시성이 목적이므로 **상태 캐시를 두지 말 것**. 상태 조회는 `global`의 `WebSocketRecipientStatusPort`를 user 도메인(`UserWebSocketRecipientStatusAdapter`)이 구현한다(`global`→`domain` import 금지). 이 확인을 지우면 정지 전에 열린 소켓으로 SOS·화재가 계속 흘러간다.
  - **열린 WebSocket 세션은 토큰 무효화 직후 서버가 닫는다 - 해소 (2026-10-02 P15, 2026-09-10 점검 M-5의 수용한 한계를 대체)**: 정지·비밀번호 변경/재설정·역할 변경·탈퇴 시 `UserAccountEventListener`가 토큰 무효화 직후 `WebSocketSessionCloser.closeAll(userId)`로 그 사용자의 열린 세션을 닫는다(close `1008 POLICY_VIOLATION`, reason은 고정 `AUTH_INVALIDATED` - 사유 상세·개인정보 금지). 세션 목록은 Principal이 아니라 자체 `WebSocketSessionRegistry`(핸드셰이크 attributes의 userId 기준, `UserSessionTrackingHandlerDecorator`가 등록·제거)다 - Principal을 세우면 구독 인가·`convertAndSendToUser` 경로가 바뀔 수 있어 택하지 않았다.
    - ⚠️ **단일 인스턴스 메모리 목록**이라 API 서버를 여러 대로 늘리면 다른 인스턴스에 붙은 세션은 닫히지 않는다 - 그때 다시 설계한다.
    - **순서(무효화 -> 닫기)를 뒤집지 말 것**: 닫힌 클라이언트가 옛 토큰으로 즉시 재연결하면 핸드셰이크를 통과한다.
    - 위 WS 발송 차단(`[WS-BLOCKED]`)과는 **별개의 이중 방어**다(발송 차단은 상태 조회, 닫기는 이벤트 시점 1회). 정지 **해제는 닫지 않는다**.
    - FE는 `1008`/`AUTH_INVALIDATED`를 받으면 재연결하지 말고 로그아웃하거나 토큰 재발급 후 재연결한다.
  - **"유일한 보호자 경고"는 2026-09-09엔 만들지 않기로 했으나, 사용자가 2026-10-02에 추천안으로 변경해 대시보드 지표로 구현했다 (ADMIN-G27/CONN-G08)**: `safetyEvents.wardsWithoutReachableGuardian` = ACTIVE 연결 보호자가 없거나, 있어도 전원이 이용 중(ACTIVE)이 아닌 피보호자 수. 기존 `wardsWithoutGuardian`은 그대로 둔다(하위호환). **개수만 내리고 대상·정지 사유는 싣지 않는다. 피보호자 화면에는 표시하지 않는다**(정지 사유 비노출). 정지 결정 자체를 막거나 관리자 화면에 경고 팝업을 만들지는 않는다.
    - 옛 판단(2026-09-09): 정지 대상을 보안 이상·법적 문제 계정으로 한정해 경고가 불필요하다고 봤다. 정지를 실제로 쓰며 사각지대가 지표에 안 잡히는 점이 문제로 확인되어 뒤집혔다 - 위 지표를 지우고 "만들지 않기로 했다"로 되돌리지 말 것.
    - ⚠️ **정지가 만드는 사각지대**: 강제 탈퇴·역할 변경은 연결이 사라져 `wardsWithoutGuardian`에 잡히지만 정지는 연결이 남아 거기엔 안 잡힌다. 정지까지 포함한 지표는 `wardsWithoutReachableGuardian`이다.
- **불변 규칙 ⑧(피보호자는 이용 제한 금지, 2026-09-09 추가)**: 피보호자를 정지하면 로그인이 막혀 **SOS를 보낼 수 없다.** 피보호자 계정은 편의 기능이 아니라 안전망 그 자체라 400(`WARD_CANNOT_BE_RESTRICTED`)으로 막는다. 탈취가 의심되면 정지 대신 **비밀번호 재설정**을 쓴다(토큰만 끊기고 본인은 계속 쓸 수 있다).
  - 검사는 **바뀐 뒤의 조합**으로 한다(`validateResultingCombination`). 축별로 따로 검사하면 "보호자를 정지 → 역할을 피보호자로" 두 단계 우회가 열리고, 순서를 정해 검사하면 "제한을 풀면서 역할을 바꾸는" 정상 요청이 잘못 거부된다.
- **정지 사유는 컬럼에 남긴다(V49)**: 감사 로그에만 남기면 **아무도 볼 수 없다** - 관리자 감사 로그 조회 API가 2026-06-11에 제거돼 쓰기 경로만 있다. `users.status_reason`은 "지금 왜 잠겨 있는가"만 답하며 해제 시 지운다(`User.activate()`). 변경 이력은 계속 `admin_audit_log`가 담당한다. 이 컬럼을 이력 저장소로 쓰지 말 것.
- 상세: `docs/(2026-09-09) feature-admin-user-management.md`, `docs/(2026-09-09) fix-notification-restricted-user.md`.


## 관리자 제휴 병원 관리 - 예약 서비스 관리자 권한을 좁게 중계한다 (2026-10-26)

- **경위**: 제휴 병원 데이터·관리 API는 예약 서비스(SilverBridgeReservation)에 있다. 관리자 웹이 BE만 보고 붙도록 `GET·POST·PUT·DELETE /api/admin/hospital/**`가 예약 서비스 관리자 API를 중계한다(BE는 병원 데이터를 저장하지 않음, V63은 감사 로그 CHECK만). 상세 `docs/(2026-10-26) feature-admin-partner-hospital.md`.
- **불변 규칙 ①(자격)**: BE 전용 예약 서비스 ADMIN 계정(`RESERVATION_ADMIN_EMAIL`/`_PASSWORD`)으로 `/api/v1/auth/login`한다. **공용 `ADMIN_PANEL_PASSWORD`·`admin-login`을 쓰지 말 것**(가장 작은 id의 ADMIN = seed 계정 토큰이 나간다). 주소·자격에 **코드 기본값을 두지 말 것** - 비면 이 API들만 503이고 기동은 막지 않는다(vkcs는 예약 서비스가 없어 비어 있는 것이 정상). 계정은 예약 서비스 API로 만들고 DB 직접 수정·`prisma db seed`는 금지(AI 챗봇 중계 불변 규칙 ⑨, 2026-10-20).
- **불변 규칙 ②(허용 목록)**: 그 토큰은 예약 서비스의 회원·API 키·예약 상세(환자 이름·전화·증상)까지 열 수 있다. `ReservationAdminClient`는 로그인 + 병원·진료과 관리 8개 경로만 부르고, 경로는 상수 + 숫자 ID로만 만든다. 임의 경로를 받는 메서드를 만들지 말 것. "우리 사용자 예약 수"를 위해 `/admin/reservations`를 부르지 말 것(환자 정보 전체를 받게 된다 - 필요하면 예약 서비스에 숫자만 주는 API를 만든다).
- **불변 규칙 ③(토큰·오류)**: 토큰은 인스턴스 메모리에만 두고, 로그·응답·예외에 토큰·비밀번호를 싣지 않는다. 401이면 한 번만 재로그인, 로그인이 거절되면 30초 동안 다시 시도하지 않는다. 예약 서비스 오류 본문은 읽지 않고 상태 코드로 고정 문구. 연결 3초·관리자 요청 하나 전체 8초(`withinCall`로 목록 확인·쓰기가 마감 공유, 전용 마감 스레드, 빈 아님)·리다이렉트 미추종.
- **불변 규칙 ④(쓰기 전 확인)**: 예약 서비스는 삭제된 병원에도 상태·진료과 변경을 받아 주고 진료과 중복을 막지 않는다. 상태·진료과 쓰기 전에 목록으로 삭제 여부·소유·이름 중복을 확인한다 - 이 확인을 지우지 말 것.
- **불변 규칙 ⑤(감사 로그 = 시도 기록)**: 쓰기 7종(`HOSPITAL_*`)은 예약 서비스 호출보다 먼저 남긴다(기록 실패 시 변경하지 않음, 호출이 실패해도 시도 기록은 남음). detail에는 보낸 칸과 값을 남긴다(병원 정보는 개인정보가 아님, DB에만). 조회는 남기지 않는다. 결과·등록된 id는 `[ADMIN-HOSPITAL]` 로그로 본다.
- **불변 규칙 ⑥(결과 불명은 실패와 구분, 2026-10-26 점검 M-1)**: 쓰기가 예약 서비스에 연결된 뒤의 시간 초과·끊김·응답 형식 이상은 504 `HOSPITAL_RESULT_UNKNOWN`이다 - 503 실패로 보이면 관리자가 다시 눌러 중복이 생긴다(예약 서비스에 유일 제약 없음). 등록·이름/주소 수정은 같은 이름 + 주소면 409 `HOSPITAL_DUPLICATED`. 이 구분과 확인을 지우지 말 것.
- **인프라**: gosky만 `docker-compose.gosky.yml`로 api를 `sb-internal`에 붙인다(`http://reservation-api:6015`). 이 override를 기본 compose에 합치지 말 것 - vkcs에는 그 네트워크가 없어 CD가 깨진다.


## 관리자 AI 통계 - 개수만 넘긴다 (2026-10-28)

- **경위**: 관리자 웹 "AI 통계 > AI 의료 챗봇" 탭용 `GET /api/admin/ai-stats/chat`(ADMIN, 조회 전용). AI `GET /api/v1/chat/stats`를 서버 키로 불러 넘긴다. 상세 `docs/(2026-10-28) feature-admin-ai-chat-stats.md`.
- **불변 규칙 ①(개수만)**: 응답은 `AdminAiChatStatsResponse` record 칸만 옮긴다(허용 목록). 상담 원문·사용자 ID·사용자 목록 칸을 더하지 말 것 - 피보호자 상담 내용을 관리자에게 노출하지 않는다는 챗 중계 규칙 ②의 연장이다. AI가 칸을 더해도 자동으로 나가지 않게 JSON을 그대로 넘기는 방식으로 바꾸지 말 것.
- **불변 규칙 ②(모르면 0이 아니다)**: AI를 부르지 못하거나 응답 형식이 어긋나면 503 `AI_STATS_UNAVAILABLE`(시간 초과 504 `AI_STATS_TIMEOUT`)이고, AI가 null을 준 개수는 null 그대로다. 실패를 0이나 빈 집계로 바꾸지 말 것(대시보드 2026-09-02와 같은 판단).
- **불변 규칙 ③(AI 보호)**: 기간 검사(31일·2000~2999년)를 AI 호출 앞에 두고, 관리자 기준 분 30·시간 600 속도 제한과 같은 기간 60초 캐시를 둔다. AI 서버는 상담·카메라 분석과 같은 프로세스라 집계 폭주가 화재 감지를 늦출 수 있다 - 화면 자동 반복 호출을 FE에 요구하지 말 것.
- **응답 검사를 줄이지 말 것 (2026-10-28 점검 M-1·L-3)**: `daily`는 i번째 날짜가 시작일 + i일, `generatedAt`은 +09:00이어야 하고 어긋나면 503이다. 줄 수만 보면 AI가 날짜를 잘못 묶을 때 하루 밀린 그래프가 정상 집계처럼 나간다.
- **AI 호출의 런타임 예외는 503으로 감싼다 (점검 L-1·L-2, 공용 `AiChatClient.call`)**: JDK 헤더 값 오류 메시지에는 키 원문이 들어 있어, 감싸지 않고 올리면 공용 `IllegalArgumentException` 핸들러가 로그에 남긴다. 이 catch를 지우지 말 것. 키 제어 문자는 호출 전에 고정 문구로 거른다(값 미기록).
- 역할별 수(`byRole`)의 근거인 `context.role`은 BE가 정한다(챗 중계 절, #395).
