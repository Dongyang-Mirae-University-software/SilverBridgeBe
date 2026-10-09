# 피보호자 AI 챗봇 중계 API (2026-10-09)

## 배경
피보호자 화면에도 AI 의료 챗봇을 넣기로 했다. 보호자용 컴포넌트를 재사용하므로 보호자와 같은 구조·응답 스펙의 피보호자 경로가 필요하다. 기존 `/api/guardian/chat*`는 피보호자 계정이 호출하면 403이다.

## 범위
- 신규 `WardChatController`(클래스 레벨 `@PreAuthorize("hasRole('WARD')")`): `POST /api/ward/chat`, `GET /api/ward/chat/logs`, `GET /api/ward/chat/logs/{chatId}`.
- 기존 `ChatRelayService`를 그대로 호출한다(로직 복제 없음). 응답·오류 code·상태 코드·한도(분 10/시간 120/동시 1, 전체 동시 10)·타임아웃(서버 130초, FE 140초 권장)은 보호자와 동일.
- `ChatRelayService` 인자명 `guardianId` -> `userId`, `ChatSlots` 로그 문구 "보호자" -> "사용자". 동작 변경 없음.
- 안 바뀜: 보호자 챗 API, DB(마이그레이션 없음), SecurityConfig(역할 매처 없이 `@PreAuthorize`만 사용, 복약 등과 같은 방식), SwaggerConfig, AI 서버, FE.

## 정책
- 사용자 ID는 토큰에서만. 요청 DTO에 userId가 없고 AI 본문의 userId는 토큰 ID로 덮어쓴다. 기록 목록·상세도 토큰 ID로만 묻는다. 남의 chatId는 404 `CHAT_LOG_NOT_FOUND`.
- 보호자·관리자가 피보호자의 상담 내용을 보는 경로는 없다(의료 상담 = 본인만). 만들지 말 것.
- 상담 본문은 로그·예외·이력에 남기지 않는다(`ChatRelayLogGuardTest`가 chat 패키지 전체를 검사하므로 새 컨트롤러도 포함).
- `context.role`·`guardianId`는 클라이언트 값을 그대로 AI에 전달한다(보호자 경로와 동일). AI는 이 값으로 분기하지 않고 모델 입력 컨텍스트로만 쓴다(`chat_service.py` 297~302행). 서버가 덮어쓰지 않기로 했다.
- 사용자 ID 공간: `users.id`가 한 테이블의 PK라 보호자와 피보호자 ID가 충돌하지 않는다. 한도·동시 자리는 사용자 ID 키라 역할 간 간섭이 없고 전체 동시 상한 10건만 공유한다.

## 관찰(보고만)
- AI는 예약·중간 위험 의도에 `recommendedAction=guardian_contact`(기계용 코드, FE는 저장만 함)를 붙인다. 화면에 보이는 "보호자" 문구는 AI 폴백 응답의 위험도 중간 문구뿐이었고, 역할 무관 문구("가족이나 가까운 분과")로 바꿨다(AI 서버 PR #12, 서버 반영은 AI 재시작 시점).
- 응급 시 보호자 자동 알림은 이번 범위 밖이다. 필요하면 후속으로 제안한다.

## 테스트
- `WardChatControllerSecurityTest`: WARD 허용, GUARDIAN·ADMIN 3개 API 모두 403. 기존 `GuardianChatControllerSecurityTest`가 WARD의 보호자 경로 403을 계속 고정.
- `WardChatControllerHttpTest`: 실제 HTTP 직렬화(data.reply 문자열, 배열, 객체). `GuardianChatControllerHttpTest`의 Jackson 2 반환 타입 가드에 `WardChatController` 추가.
- `ChatRelayServiceTest`: 보호자·피보호자가 같은 서비스를 써도 AI에는 각자 토큰 ID, 한도·자리는 사용자별로 분리.
- `./gradlew test` 전체 통과(실패 0), `./gradlew build -x test` 통과.
