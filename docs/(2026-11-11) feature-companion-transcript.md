# 말벗 대화 내용 저장(동의·30일) + 보호자 7일·날짜별·대화 보기 + 테스트 전체 공개 (2026-11-11)

## 배경

- 보호자 "AI 기분 체크" 프로토타입: 최근 7일 기분 → 날짜의 시간대 목록 → "대화 보기"(기분 요약·턴별 기분 흐름·나눈 이야기).
- 사용자 결정(11/11):
  - 안부 말벗 테스트를 모든 피보호자 계정에 연다(gosky dev). 보호자 계정은 해당 없음.
  - 대화 원문(말벗 문장·어르신 대답, 받아 적은 글)은 **피보호자 별도 동의 시에만** 저장하고 30일 뒤 지운다.
    개인정보 보호법 제23조(건강 등 민감정보 별도 동의)·제21조(보유기간 경과 시 파기). 원문 미저장(결정 B, 11/02)을 동의 조건부로 바꾼 정책 변경이다.
  - 턴별 기분(글 없음)은 동의와 무관하게 저장한다.
- PHASE 1 결정: 동의 API는 말벗 아래(`/api/ward/companion/transcript-consent`), today·days 칸에 `sessionId` 추가, 대화 보기는 최근 7일만.

## 변경

| 구분 | 내용 |
|---|---|
| V71 (가역) | `companion_transcript_consent(ward_id PK → users CASCADE, consented_at)`, `companion_turn(session_id → companion_session CASCADE, turn_no, mood, bot_text, answer_text, created_at, UNIQUE(session_id, turn_no))` |
| 턴 저장 | `CompanionService.handleTurn` → `CompanionSessionWriter.recordTurn(..., TurnContent)` → `CompanionTurnRecorder`(같은 트랜잭션). 기분 = `turnResult.moodCode` 또는 한글 `label`. 글 = 대답(음성이면 받아 적은 글)·그 뒤 말벗 문장. 동의 행 `FOR SHARE` |
| 동의 | `GET/PUT /api/ward/companion/transcript-consent` `{consent}` → `{consented, consentedAt, retentionDays}`. 철회 = 행 삭제 + 글 즉시 비움 |
| 보관 | `CompanionTranscriptRetentionScheduler` 04:55, `COMPANION_TRANSCRIPT_RETENTION_DAYS`(30) |
| 정리 | 탈퇴 FK CASCADE, 역할 변경 `clearOnRoleChange`에 동의 행 삭제 추가 |
| 보호자 | `GET .../companion/week`, `GET .../companion/days/{date}`, `GET .../companion/sessions/{sessionId}`, today·days `slots[].sessionId` |
| 테스트 전체 공개 | `COMPANION_TEST_ALL_WARDS`(기본 false) → `isTestWard`가 모든 피보호자 true. gosky dev `.env.dev`에만 |
| 오류 코드 | `COMPANION_DATE_OUT_OF_RANGE`(400) |

### 대화 보기 응답

```
{sessionId, date, slot, question, status, mood, test, turnCount, negativeCount, riskAlerted, startedAt, endedAt,
 turns: [{turnNo, mood, botText, answerText}], transcript: AVAILABLE | NO_CONSENT | EXPIRED}
```

- `negativeCount` = 걱정·우울 턴 수. `riskAlerted` = 이 대화로 위험 알림이 나갔는가.
- 글은 `transcript=AVAILABLE`일 때만 싣는다. 동의가 지금 없으면 남은 글이 있어도 싣지 않는다.
- 첫 질문은 `question`, 턴 n은 "어르신 대답 n → 말벗 문장 n" 순서로 보여 준다.

## 바뀌지 않는 것

- today API의 기존 필드(칸에 `sessionId`만 추가), 주의·위험·누적 알림, 일반 대화 창 규칙, 관리자 집계, AI 계약.
- 음성·사진은 저장하지 않는다. 로그·알림·감사 로그에는 동의와 무관하게 글을 넣지 않는다.

## 수용한 한계

- 동의 전에 시작해 동의 뒤에 이어진 대화는 동의 뒤 턴만 글이 있다(부분 표시).
- 보호자 화면은 7일만 보이므로 `EXPIRED`는 보관 일수를 7일보다 짧게 줄였을 때만 나온다(방어용 값).
- 테스트 전체 공개 중에는 모든 피보호자 응답에 `analysis`가 실리고 테스트 대화가 위험 알림 집계에 들어간다(대체 문자 과금 가능, L-5).

## 테스트

- 단위: `CompanionTurnRecorderTest`·`CompanionTranscriptServiceTest`·`CompanionGuardianViewTest`·`CompanionTestModeTest`(전체 공개)·`CompanionTranscriptLogGuardTest`(로그 인자 원문 금지)·`LogRawExceptionGuardTest`(새 파일 4개 추가).
- 통합(vkcs): `CompanionTranscriptFullIntegrationTest` - 동의 없음/동의/철회·30일·역할 변경·탈퇴 CASCADE·철회와 턴 저장 경합.
