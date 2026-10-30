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

## 배포·실서버 확인 (2026-10-30)

- 배포: AI `932f9a6`(gosky 17:17 재시작, 다른 세션 측정 잠금 해제 뒤) · BE #408 `8ccb18d`(vkcs CD 성공, gosky dev) · FE #183 `6bce937`(gosky dev만, prod 재빌드 없음)
- gosky dev 시험 계정 3턴씩(증상 → 되묻기 답 → 이어질 질문 누르기 → 응급 문장):

| 턴 | 보호자 | 피보호자 |
|---|---|---|
| 첫 증상(되묻기) | 빈 목록 - 설계대로(`followupAsked`) | 빈 목록 |
| 되묻기 답(MedGemma) | "병원에 모시고 가야 할까요?"·"정형외과는 어떻게 예약하나요?"(대리 말투, 예약 칩 뒤) | 규칙 문장 3개(모델 결과가 정리에서 모두 빠짐 - 아래) |
| 이어질 질문 누름 | "어느 진료과에 가야 하나요?" | "두통에 좋지 않은 음식이 있나요?"(남는 칸 기본 문장, 버튼 4개) |
| 응급 문장 | 빈 목록·고정 칩 복귀(생성 호출 없음) | 빈 목록·기본 버튼 복귀 |

- 지연: 생성 1.7~2.7초(모두 3초 안), MedGemma 답 기준 전체 약 10초 → 약 14~15초
- 375px 가로 스크롤 없음, 17자 질문은 큰 버튼 안에서 두 줄
- **발견(후속 과제)**: AI 정리 상한 18자에 모델 질문이 자주 걸린다(4번 중 1번은 전부 빠져 규칙 문장, 나머지도 1~2개만 남음). 상한을 22자 안팎으로 올리고 프롬프트에 "15자 안팎"을 적는 조정이 필요하다 - AI 재배포(재시작) 필요라 별도 확인 후 진행
