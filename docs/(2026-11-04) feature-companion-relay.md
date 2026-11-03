# 안부 말벗 백엔드 중계 - PR-A (2026-11-04)

> 재희 AI 계약(`SilverBridgeAiServer` 브랜치 `feat/companion-ai-part` `ae621f3`, main 미머지) 기준. 노션 카드 1·2·3·4·5 + 9의 위험 알림.
> 정책·불변 규칙은 `.claude/rules/policy-companion.md`. 질문 근거는 `docs/(2026-11-04) companion-question-research.md`.

## 1. 흐름

```
기기 ─ GET  /api/ward/companion/today
     ─ POST /api/ward/companion/sessions                 → AI POST /companion/dialogues (질문·questionId·sessionId)
     ─ POST /api/ward/companion/sessions/{id}/reply      → AI POST /companion/dialogues/{dialogueId}/reply
     ─ POST /api/ward/companion/sessions/{id}/reply/audio→ AI POST .../reply/audio (audio + frame 0~5)
     ─ POST /api/ward/companion/sessions/{id}/finish     → AI POST .../finish (결과 1건)
```

- 시간대·질문은 서버가 정한다(시작 시각부터 60분). 같은 시간대는 하루 한 번(UNIQUE). 진행 중이면 이어 간다(`resumed`).
- 턴 응답에 `ended=true`가 오면 BE가 곧바로 finish를 불러 결과를 저장한다.
- 위험 신호(`signals.riskFlag`)가 처음 나온 턴 → `COMPANION_RISK` 1번. 마감 결과가 URGENT인데 아직 안 보냈으면 그때 1번.

## 2. 저장

| 표 | 담는 것 |
|---|---|
| `companion_question` | 시간대·문장·활성 여부 (초기 19문장) |
| `companion_session` | 피보호자·날짜·시간대·질문 번호·AI 대화 번호·상태(OPEN/FINISHED/LOST)·종료 이유·기분·턴 수·위험 여부·알림 시각 |
| `companion_prompt_log` | 질문 안내 발송 기록(선점 후 발송) |
| `companion_purge_request` | 탈퇴 피보호자 AI 데이터 삭제 대기열(FK 없음) |

원문 컬럼은 없다. 기분 null = 판단 불가.

## 3. AI 값 변환

| AI | BE |
|---|---|
| endReason `최대 턴 수` / `연속 무응답` / `USER_STOP` | `MAX_TURNS` / `NO_RESPONSE` / `USER_STOP` (모르는 값 → USER_STOP, 멈춘 대화 마감은 NO_RESPONSE) |
| mood `기쁨` / `평온` / `걱정` / `우울` / null | `JOY` / `CALM` / `WORRY` / `DEPRESSED` / null |
| 오류 `DIALOGUE_NOT_FOUND` | 409 `COMPANION_SESSION_LOST` + 대화 LOST |
| `DIALOGUE_ENDED` | 409 `COMPANION_SESSION_ENDED` (결과를 받아 저장 시도) |
| `ANSWER_EMPTY` / `AUDIO_*` / `FRAME*` / `STT_UNAVAILABLE` | 400 / 400·413 / 413 / 503 |
| 401·FastAPI 422 `{detail}`·모르는 코드·연결 실패 | 503 `COMPANION_UNAVAILABLE` (ERROR 로그) |
| 90초 초과 | 504 `COMPANION_TIMEOUT` |

## 4. 설정 (`companion.*`)

`COMPANION_ENABLED`(false) · `COMPANION_AI_BASE_URL`·`COMPANION_AI_API_KEY`(기본값 없음) · `COMPANION_CALL_TIMEOUT` 90s · 동시 8 · 분 20/시간 200 · 창 60분 · 멈춤 10분 · `COMPANION_PROMPT_WARD_IDS`(질문 안내 허용 목록).

**.env.dev에는 아직 넣지 않았다** - AI가 gosky 메인 AI 서버에 올라온 뒤 사용자 확인을 받고 넣는다. 그 전에는 기기 API가 503 `COMPANION_DISABLED`다.

## 5. 스케줄러 (공용 풀 3)

| 작업 | 주기 |
|---|---|
| 질문 안내 | 09·12·15·18·21시 정각 |
| 멈춘 대화 마감 | 5분마다(1회 10건, 첫 실패 중단) |
| 탈퇴 삭제 재시도 | 매시 35분(1회 50건, 첫 실패 중단) |

## 6. 테스트

- 단위: `CompanionServiceTest`(11) · `AiCompanionClientTest`(8, JDK HttpServer) · `CompanionValueMappingTest`(4) · `CompanionCheckSyncTest`(1) · `CompanionRiskNotificationListenerTest`(2) + 가드 갱신(디스패처 강제 종류·관리자 기타 탭·FCM 긴급도·executor·원문 로그).
- 통합(실 DB): `CompanionSessionRepositoryIntegrationTest`(5) - 초기 질문, 시간대 UNIQUE, 위험 선점 1회, 멈춘 대화 조회, 탈퇴 CASCADE.

## 7. 남은 것

- PR-B: 피보호자 화면용 주의(NOTICE) 계산·알림·설정, 보호자 화면 결과, 관리자 통계, 질문 안내 허용 목록 제거.
- PR-C: 챗봇 마이크 STT(카드 6).
- 재희 확인: 질문 19문장 확정, 한 턴 60초, main 서버 등록·오류 처리기, 계약 문서 D:90 정정, 대화 TTL, frame 형식 확인.
