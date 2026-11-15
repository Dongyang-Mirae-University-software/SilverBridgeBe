# 최종 QA BE 결함 11건 수정 (2026-11-15)

근거: 최종 QA(2026-11-13~15, SilverBridgeQA Playwright 593 테스트) 결과 노션 DMU > SilverBridgeQA > 최종 QA - BE+AI.
브랜치 `fix/final-qa-be-defects`. 마이그레이션 없음. 인프라(nginx) 3건·문서-코드 불일치 5건은 범위 밖(기획 확인·AI 저장소).

## 범위와 변경

| 케이스 | 우선순위 | 원인 | 수정 | 파일 |
|---|---|---|---|---|
| NOTI-N15b | 높음 | 피보호자 칸이 빈 알림만 있으면 빈 맵 `Map.of().get(null)` NPE → 받은 알림 목록 500 | wardId 가 null 이면 이름 조회 생략 | `notification/service/UserNotificationService.java` |
| CONN-A26·A31 | 중간·낮음 | 웹 요청 JSON(Jackson 3)이 enum 칸의 숫자를 순번으로 받음(`{"sosAction":0}`→CALL_119, `timeSlot:1`→LUNCH) | `spring.jackson.datatype.enum.fail-on-numbers-for-enums: true` | `application.yaml` |
| NOTI-A60 | 중간 | 로그아웃해도 그 토큰으로 연 WebSocket 이 안 닫힘 | 핸드셰이크 때 토큰 해시를 세션 속성에 적고, 로그아웃 커밋 뒤 그 토큰 세션만 1008 `AUTH_INVALIDATED` 로 닫음 | `auth/event/UserLoggedOutEvent.java`(신규), `auth/service/AuthService.java`, `auth/listener/UserAccountEventListener.java`, `global/websocket/JwtHandshakeInterceptor.java`, `global/websocket/WebSocketSessionCloser.java` |
| ADM-A19 | 중간 | 이미 정지된 계정에 새 사유를 보내면 200 인데 아무것도 안 바뀜 | 같은 RESTRICTED 이면 사유만 바꾸고 감사 로그 "정지 사유 변경". 같은·빈 사유는 변경 없음 | `admin/service/AdminUserService.java` |
| CHAT-A27 | 중간 | 없는 병원 삭제, 전화만 바꾸는 수정도 감사 로그가 남음 | 감사 로그 전에 `findActive`(없거나 삭제된 병원 404) | `admin/service/AdminHospitalService.java` |
| ADM-A07 | 중간 | 일반 사용자 공지 응답에 관리자 실명(authorName) | 칸은 유지, 값은 항상 고정 문구 "관리자" | `announcement/dto/AnnouncementResponse.java` |
| CONN-A12 | 중간(BE 보완) | 연결 응답 시각이 UTC 표기라 앞 10자리를 자르는 화면이 한국 새벽 요청을 전날로 표시 | `createdAt`·`connectedAt`·`requestedAt` 을 같은 순간의 +09:00 표기로 | `connection/dto/ConnectionTimes.java`(신규), `ConnectionResponse.java`, `PendingConnectionResponse.java` |
| ADM-A18 | 낮음 | 관리자 이름 수정에 NUL 등 제어문자가 통과(조용히 지움) | `@NoControlChars` (회원 본인 수정과 같게) | `admin/dto/AdminUserUpdateRequest.java` |
| CONN-A55 | 낮음 | 문의 내용이 제로폭 공백·NBSP 뿐이어도 저장 | `@VisibleText` (제목과 같게) | `inquiry/dto/InquiryCreateRequest.java` |
| CONN-A03 | 낮음 | 연결 대상 미리보기가 빈 값·5자·7자에 404 (실제 요청은 400) | 속도 제한 뒤, 조회 전에 6자 형식 검사 → 400 `INVALID_INPUT` | `connection/controller/GuardianConnectionController.java` |

## 결정 (2026-11-15 사용자)

- NOTI-A60: 로그아웃은 그 access 토큰 하나만 블랙리스트에 올리므로 닫는 범위도 **그 토큰으로 연 세션만**. 다른 기기(아직 유효한 다른 토큰)의 세션은 남긴다. 토큰 원문이 아니라 `JwtTokenProvider.hashToken` 값을 세션 속성 `accessTokenHash` 에 둔다.
- ADM-A07: 칸 삭제 대신 고정 문구 "관리자"(응답 모양 유지). 관리자용 `AdminAnnouncementResponse` 는 그대로.
- CONN-A12: BE 가 +09:00 표기로 내린다(같은 순간이라 `new Date()` 로 읽는 쪽은 결과가 같다). FE 쪽 날짜 처리 수정은 FE 담당(노션 FE 페이지).

## 바뀌지 않는 것

- 로그아웃의 refresh 토큰 전체 삭제·access 토큰 블랙리스트·접속 로그는 그대로. 정지·비밀번호 변경·역할 변경·탈퇴의 `closeAll` 경로도 그대로.
- 숫자-enum 거부는 웹 요청 JSON(Jackson 3)에만 적용 - `@RequestParam` enum(Spring 변환기)과 `JacksonConfig` 의 Jackson 2 `ObjectMapper` 는 영향 없음. main 코드에 Jackson 3 매퍼를 직접 주입해 쓰는 곳은 없다.
- 상태가 실제로 바뀌는 정지(ACTIVE→RESTRICTED)의 `UserRestrictedEvent`·감사 문구는 그대로. 사유만 바꿀 때는 토큰 무효화 이벤트를 다시 보내지 않는다.
- 병원 수정에서 이름·주소·진료 시간·점심시간을 바꾸는 경로(이미 현재 값을 읽음)와 상태·진료과 경로는 그대로.

## 테스트

- `./gradlew build` 통과(단위 테스트 2700여 개, 실패 0).
- 추가·수정: `UserNotificationServiceTest`(피보호자 없는 알림만), `WebJsonEnumNumberRejectTest`(실제 application.yaml 로 숫자 거절·글자 허용), `WebSocketSessionCloserTest`(그 토큰 세션만 닫음), `JwtHandshakeInterceptorTest`(해시 저장·원문 미저장), `AdminUserServiceTest`(사유만 변경·같은/빈 사유), `AdminHospitalServiceTest`(없는·삭제된 병원 감사 로그 0), `AnnouncementServiceTest`(고정 문구), `GuardianConnectionControllerPreviewTest`(형식 오류 400), `ConnectionTimesTest`(한국 날짜), `SyncAfterCommitListenerPropagationTest` 표에 `handleLoggedOut`(쓰기 없음) 추가.
- 배포 뒤 SilverBridgeQA 로 해당 케이스를 gosky dev 에 다시 돌려 확인한다(결과는 노션).
