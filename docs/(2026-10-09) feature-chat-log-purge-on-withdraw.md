# 탈퇴 시 AI 상담 기록 삭제 (2026-10-09)

## 배경
피보호자 챗 점검(`docs/(2026-10-09) audit-ward-chat-relay.md`) M-1: 회원이 탈퇴해도 AI 서버 DB(`chat_logs`)의 상담 기록이 남는다. 탈퇴는 hard delete이고 "탈퇴자가 남긴 데이터를 붙들지 않는다"가 기본인데, 상담 기록은 백엔드 DB 밖이라 CASCADE가 닿지 않았다. 피보호자 경로가 열려 고령 사용자의 건강 상담이 대상에 들어가면서 중요해졌다.

## 변경
- **AI 서버** (`SilverBridgeAiServer`, PR #10): `DELETE /api/v1/chat/logs?userId=` 추가. `userId` 필수(없음·공백은 422, 아무것도 지우지 않음), 해당 회원 행만 삭제, `{deleted: n}` 반환(없으면 0, 멱등). 챗 라우터 전체에 걸린 API 키 검사 적용. 테스트 4건.
- **백엔드**: `AiChatClient.deleteLogs(userId)`, `ChatLogPurgeListener`(`UserWithdrawnEvent` AFTER_COMMIT, `@Async("chatPurgeExecutor")`), `AsyncConfig.chatPurgeExecutor`(1/2/100, 포화 시 폐기 + WARN).
- 마이그레이션 없음.

## 정책
- 비동기인 이유: 이벤트가 `userId`를 들고 있어 회원 행이 필요 없다(클립 삭제와 다름). AI 호출(최대 15초)이 탈퇴 응답을 붙들지 않게 한다. 백엔드 DB를 쓰지 않아 `REQUIRES_NEW`도 불필요.
- best-effort: 실패는 삼키고 `[CHAT-PURGE-FAILED]` WARN(userId·예외 클래스명만). 상담 내용·AI 오류 문구는 로그에 남기지 않는다.
- 성공은 AI가 200을 줄 때뿐이다(옛 AI 서버의 404/405를 성공으로 보지 않는다).
- 관리자 강제 탈퇴·보호자·피보호자 탈퇴가 같은 파이프라인이라 모두 포함된다. 킬 스위치 `chat.relay.enabled`는 삭제에 영향이 없다.

## 수용한 한계
- 스윕 purge 경로(리스너 미경유)·AI 장애·executor 포화로 놓친 건은 AI에 남는다(재시도 없음).
- **배포 순서: AI 서버 먼저.** AI 반영 전에 백엔드가 먼저 나가면 삭제 호출이 404/405로 실패해 WARN만 남고, 그 기간에 탈퇴한 건은 지워지지 않는다. 이미 탈퇴해 남아 있는 기존 기록은 이번 변경이 지우지 않는다(필요하면 별도 일회성 정리).
- AI 서버의 예약 자격 증명(`reservation_credentials`)은 이번 범위 밖이다.

## 테스트
- 백엔드: `AiChatClientDeleteLogsTest`(메서드·경로·키 헤더·인코딩·200만 성공·키 미설정), `ChatLogPurgeListenerTest`(요청·실패 삼킴·AFTER_COMMIT+전용 executor), `NotificationExecutorAssignmentTest`·`LogRawExceptionGuardTest` 확장. `./gradlew test` 전체 통과(실패 0).
- AI: `tests/test_chat_log_purge.py` 4건 통과(그 파일이 쓰는 최소 의존성 venv에서 실행, 다른 기존 테스트는 numpy 등 미설치로 수집 불가라 돌리지 못함).
