# AI 챗봇 중계 API 계약 (FE 전달용, 2026-10-07)

FE가 `/api/streams/v1/chat*`로 부르던 챗봇 호출을 **백엔드 API로 교체**한다. 3개 모두 보호자(GUARDIAN) 전용,
`Authorization: Bearer {accessToken}` 필수. 응답은 공용 포맷 `{success, message, data, code}`이며
**`data`가 AI 응답의 data 그대로**라 FE의 "래핑 언래핑" 코드는 `res.data.data`로 단순해진다.

> ⚠️ **프록시(`/api/streams`)로 되돌리지 말 것.** FE 보안 수정(ad44cb6)이 막은 것이 맞고, 프록시는 AI 키를 붙여 아무 경로나 넘기던 구멍이다.

## 1. 메시지 전송 `POST /api/guardian/chat`

요청(JSON):

```json
{
  "message": "머리가 아파요",
  "sessionId": "chat-1696000000000",
  "history": [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}],
  "context": {"name": "홍길동", "age": 70, "role": "GUARDIAN"},
  "uiSelection": {"field": "date", "value": "2026-10-08"}
}
```

- **`userId`는 보내지 않는다**(보내도 무시됨 - 사용자는 토큰으로 정해진다). 기존 `userId: 1` 고정값은 삭제.
- `message` 또는 `uiSelection` 중 하나 필수. `message` 2000자 이하, `history` 24개 이하(각 4000자), `context` 20키·8KB 이하, `sessionId` 64자 이하.
- `history[].role`은 `user` | `assistant`.

성공(200): `data`는 AI 챗 응답(`reply`, `riskLevel`, `recommendedAction`, `reservationRequired`, `intent`, `engine`, `modelName`, `type`, `ui`, `tool`, `data`, `sessionId`). 필드는 AI 그대로이므로 기존 `ChatResponse` 매핑을 유지한다.

## 2. 내 기록 목록 `GET /api/guardian/chat/logs`

쿼리 파라미터 없음(`userId` 보내지 않는다). `data` = 본인 기록 배열(최신순, 없으면 `[]`).

## 3. 기록 상세 `GET /api/guardian/chat/logs/{chatId}`

본인 기록만. 없는 기록과 남의 기록 모두 404.

## 오류 code (FE는 문구가 아니라 `code`로 분기)

| HTTP | code | 의미·FE 처리 |
|---|---|---|
| 400 | `CHAT_INVALID_REQUEST` | 빈 메시지·길이 초과 등 |
| 401 / 403 | (기존 인증 오류) | 로그인 만료 / 보호자 아님 |
| 404 | `CHAT_LOG_NOT_FOUND` | 상세 기록 없음 |
| 429 | `CHAT_LIMIT_EXCEEDED` | 이전 전송 응답을 기다리는 중 - 전송 중 버튼 비활성화로 방지 |
| 429 | `TOO_MANY_REQUESTS` | 분당 10회·시간당 120회 초과, `Retry-After` 초 |
| 503 | `CHAT_UNAVAILABLE` | AI 상담 서버 불가·일시 중단 |
| 504 | `CHAT_TIMEOUT` | AI가 130초 안에 답하지 못함 |

## FE가 기다릴 시간

전송은 서버가 **최대 130초** 기다린다. axios 타임아웃은 **140초 이상**(기존 10분이면 그대로 가능, 단 504를 받으면 "시간이 걸려 실패" 안내). 기록 조회는 15초.

## FE가 바꿀 파일

- `src/service/api/chat.ts` — `streamClient` 대신 기본 API 클라이언트(`/api/guardian/chat*`)로 교체, `/v1/chat/logs`의 `userId` 쿼리 제거, 응답은 `res.data.data`.
- `GuardianChatContent.tsx` — `sendChatMessage`의 `userId: 1` 제거, 로그 조회 호출에서 `userId` 인자 제거(`getChatLogs()`), 오류는 `code`로 분기(429 안내·504 안내 분리).
- `src/service/interface/chat.ts` — `ChatRequest.userId` 삭제.

## 참고

- 기존 대화 기록은 전부 `userId=1`(공용 버킷)로 저장돼 있어 새 API에서는 **보이지 않는다**(실제 사용자 기록은 0건이었으므로 화면 변화 없음).
- 같은 프록시를 쓰는 다른 호출(게임 `GET /v1/games/progress`, `streamSession.ts`, `liveStream.ts`)도 같은 이유로 막혔을 수 있다 - 별도 확인 필요.
