# AI 의료 챗봇 "이어서 물어볼 질문" (2026-10-30)

> AI `SilverBridgeAiServer` #135 · BE `feature/chat-suggested-questions` · FE `feature/chat-suggested-questions`

## 배경

FE 챗봇 빠른 질문이 고정 문장 4개라 대화가 이어지지 않았다(사용자 요청: "기록을 보고 계속 질문으로 진행"). AI가 답마다 이어서 물어볼 질문을 최대 3개 내려 주고, FE 빠른 질문 칸이 대화 흐름대로 바뀐다. 세션을 넘는 개인화(지난 상담·복약·예약)는 범위 밖.

## 응답 계약

- 전송 응답(`POST /api/guardian/chat`·`/api/ward/chat`) `data.suggestedQuestions: string[]` - 0~3개
- AI가 빈 목록을 주는 답: 응급·자해·위기 후속·응급 후속 안내·되묻기·생성 실패·예약 흐름(선택지·도구 결과), `FOLLOW_UP_ENABLED=false`
- AI 생성: 짧은 Claude 호출(`FOLLOW_UP_MODEL` 기본 haiku, 입력은 사용자 문장·답 본문뿐) → 3초(`FOLLOW_UP_TIMEOUT_SEC`) 안에 안 끝나거나 실패하면 규칙 문장. 상세 AI `docs/design/2026-10-30-chat-suggested-questions.md`
- 챗 기록(목록·상세)에는 없다

## BE

- `SuggestedQuestions.sanitize`(신규): 전송 응답의 이 칸만 정리 - 문자열만, 제어문자 제거·공백 정리, 1~30자, 중복 제외, 최대 3개. 배열이 아니면 칸 삭제, 답은 그대로. 로그 없음
- `ChatRelayService.relay`에서 `plain(reply)` 직전에 호출
- 테스트: `SuggestedQuestionsTest` 3건, `ChatRelayServiceTest` 1건(정리 후 전달)
- 바뀌지 않는 것: 기록 허용 목록(`ChatLogProjection`), 사용자 ID·wardId 처리, 속도·동시 상한, 로그 규칙

## 배포 순서

AI → BE → FE. 새 칸이 없어도 BE·FE는 지금처럼 동작한다(옛 AI 응답은 손대지 않음).
