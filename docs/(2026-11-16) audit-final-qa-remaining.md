# (2026-11-16) 기능 점검 - BE #493 최종 QA 남은 항목 (피보호자 해제 문구·multipart 12MB)

- 대상: BE #493 (머지 `d8fc29fa`) - `ConnectionNotificationListener` 피보호자 해제 문구, `spring.servlet.multipart.max-request-size` 12MB
- 기준: `dev` `d8fc29fa`, `./gradlew build` 통과
- 방식: 템플릿 B. 점검만, 코드 변경 없음. 서버는 읽기 전용

## PHASE 0. 점검 대상

| 영역 | 파일 |
|---|---|
| 해제 알림 | `domain/connection/listener/ConnectionNotificationListener.java`, `domain/connection/service/ConnectionService.java`(이벤트 발행 5곳), `domain/connection/event/ConnectionDisconnectedEvent.java` |
| 업로드 | `user/controller/UserController`(프로필), `medication/controller/GuardianMedicationOcrController`(약 봉투), `companion/controller/WardCompanionController`(말벗 음성), `chat/controller/GuardianChatController`·`WardChatController`(STT), `global/exception/GlobalExceptionHandler.handleMaxUploadSizeExceeded` |

## PHASE A. 보안·인가

| 확인 | 결과 |
|---|---|
| 새 문구에 이름·관계 등 개인정보 | 없음 - "보호자가/관리자가 가족 연결을 해제했어요." 고정 문구 | PASS |
| 업로드 경로별 서비스 크기 검사 | 프로필 5MB(`UserService:286`), 약 봉투 `maxImageBytes`(`MedicationOcrService:76`), 말벗 음성 5MB·사진 1MB×5(`CompanionService:594-615`), STT 10MB(`ChatSttService:83`) - 5개 경로 모두 있음 | PASS |
| 업로드 경로별 속도 제한 | 5개 경로 모두 `RateLimitService.check` (프로필 `UserService:136`, OCR `MedicationOcrService:45` + 동시 1건 슬롯, 말벗 `CompanionService`, STT `ChatSttService:49` + 동시 슬롯) | PASS |
| 인증 전에 multipart 를 읽는 곳 | 필터에서 `getParameter`·`getParts` 호출 0건 - multipart 는 인증 뒤 DispatcherServlet 에서만 읽힘 | PASS |

- 요청 전체 상한이 10MB → 12MB 로 오른 만큼 받는 양은 늘지만, 파일 하나 상한(10MB)과 경로별 서비스 검사가 그대로라 "검사 없이 커진 경로"는 없다.

## PHASE B. 기능 정합성 - 해제 주체 × 수신자

| disconnectedBy | 발행 위치 | 보호자가 받는 문구 | 피보호자가 받는 문구 |
|---|---|---|---|
| GUARDIAN | `ConnectionService:229` 보호자 해제 | (끊은 본인이라 알림 없음) | "보호자가 가족 연결을 해제했어요." |
| WARD | `ConnectionService:338` 피보호자 해제 | "피보호자가 연결을 해제했습니다." | (끊은 본인이라 알림 없음) |
| ADMIN | `ConnectionService:402` 역할 변경, `:467` 관리자 해제(양쪽) | "관리자가 연결을 해제했습니다." | "관리자가 가족 연결을 해제했어요." |
| WITHDRAWN | `ConnectionService:364` 탈퇴 | "피보호자가 탈퇴해 연결이 종료되었습니다." | "가족 연결이 해제됐어요."(M-2, 주체 없음) |

- 코드·`프로젝트_설명.txt`(WS `connection-cancelled` 표, 탈퇴·관리자 해제 문단)·QA 테스트가 모두 이 표와 같다.
- FE: 실시간 알림은 BE `body` 를 그대로 쓴다(`utils/dashboard/realtime.ts:23`). `PushNotificationListener.tsx:83` 의 "연결이 해제되었습니다."는 BE 문구가 없을 때만 쓰는 예비 문구. QA CONN-N06(피보호자 화면에 새 문구 표시) 통과로 확인.
- 피보호자 문자 90바이트 단문: 새 문구 2개 단위 테스트로 고정(`ConnectionNotificationListenerTest.피보호자_문자_규칙`). 알림톡 템플릿은 이상감지 1개뿐이라 해제 알림은 원래 알림톡을 쓰지 않는다 - 영향 없음.
- 알림 종류(`CONNECTION_DISCONNECTED`, `SETTINGS_ONLY`, 이메일 허용)·제목·WS 키는 그대로.

## PHASE C. 구조·계약

- `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` 그대로. 문구 선택은 순수 함수(`wardDisconnectedBody`) - DB 접근 없음.
- WS·푸시 응답 키 변화 없음(값만 바뀜). `MaxUploadSizeExceededException` 경로별 응답(OCR 413·말벗 413·STT 413·그 밖 400) 그대로.

## PHASE D. 테스트

- 해제 문구: 6개 조합 파라미터 테스트 + 개별 테스트 - 도달 가능한 조합 모두 덮음. 도달하지 않는 WARD→피보호자 조합은 기존 단문으로 둠.
- 12MB: `MultipartLimitConfigTest` 는 설정 값만 본다. 실제 10~12MB 요청이 서비스까지 오는지는 QA CHAT-N33(nginx 13m 적용 뒤)에서 확인할 몫.

## 이슈

- 🔴 / 🟠 / 🟡: 없음
- 🟢 L-1: 말벗 음성 업로드의 오류 설명이 옛 상한을 말한다. `ErrorCode.java:223` 주석 "서블릿 요청 상한(10MB)", `GlobalExceptionHandler.java:272` 주석, `WardCompanionController.java:179` Swagger "음성·사진 합계 10MB 초과". 이제 서블릿 상한은 12MB 다. 사용자 문구 "음성과 사진을 합쳐 10MB까지 보낼 수 있습니다."는 정상 한도(5MB + 1MB×5) 기준이라 그대로 맞다 - 주석·Swagger 만 고치면 된다.
- 🟢 L-2: 10~12MB 요청이 서비스 검사까지 가는 통합 테스트는 없다(설정 값 테스트만). nginx 적용 뒤 QA CHAT-N33 로 확인.
- 참고: vkcs-linux 에는 `/etc/nginx` 설정 파일이 없다 - 앞단 프록시 상한은 이번 점검 범위 밖.

## 종합 판정

**PASS** - 보안·기능·계약 문제 없음. Low 2건(L-1 주석·Swagger 갱신은 다음 PR 에 함께, L-2 는 QA 로 확인).
