# 관리자 AI 통계 - 의료 챗봇 집계 중계 (2026-10-28)

관리자 웹 v13 "AI 통계 > AI 의료 챗봇" 탭이 쓸 집계를 BE가 AI 서버에서 받아 넘긴다. 개수만 다루고 상담 내용·사용자 ID는 없다.

- AI 쪽: `GET /api/v1/chat/stats`(AI #84·#87, 점검 `docs/(2026-10-28) audit-ai-chat-stats.md`)
- BE 쪽(이 문서): `GET /api/admin/ai-stats/chat?from=YYYY-MM-DD&to=YYYY-MM-DD` (ADMIN 전용, 조회라 감사 로그 없음, 마이그레이션 없음)

## 결정 (2026-10-28 사용자 "추천안으로 진행")

| 항목 | 결정 | 이유 |
|---|---|---|
| AI를 못 불렀을 때 | 503 `AI_STATS_UNAVAILABLE` / 시간 초과 504 `AI_STATS_TIMEOUT` | 집계 전체를 모르는 상황이라 칸마다 null보다 화면 전체 "불러올 수 없음"이 정확하다. 0으로 채우지 않는다 |
| 캐시 | 같은 기간 성공 결과만 60초, 기간 32개까지 | 화면 진입·기간 변경 때 부르는 화면이라 짧게만 둔다. 실패는 두지 않는다 |
| 속도 제한 | 관리자 기준 분 30·시간 600 | AI 서버(상담·카메라 분석과 같은 프로세스)를 보호 |
| 챗 킬 스위치 | 적용하지 않음 | `chat.relay.enabled`는 전송만 막는다(기록 조회처럼 조회는 계속) |
| 경로 | `/api/admin/ai-stats/chat` | 다른 AI 탭(말벗 등)이 생기면 `/api/admin/ai-stats/...` 아래에 붙인다 |
| 기간 오류 코드 | 400 `AI_STATS_INVALID_RANGE`(PHASE 1의 `INVALID_INPUT`에서 바꿈) | `CustomException`이 코드만 받아 일반 문구("잘못된 입력값")가 되므로 기간 안내 문구를 가진 전용 코드로 했다 |

## 동작

1. **기간 검사(BE에서 먼저)**: `YYYY-MM-DD` 형식, 2000~2999년, `from <= to`, 시작·종료 포함 31일 이하. 어기면 400이고 AI·속도 제한 모두 건드리지 않는다.
2. **속도 제한** → **캐시**(60초 안이면 AI를 부르지 않음) → **AI 호출**(`AiChatClient.stats`, 키는 `X-API-Key` 헤더만, 리다이렉트 미추종, 호출 전체 15초 = 기록 조회와 같은 `logs-call-timeout`, 응답 64KB 상한).
3. **응답 형식 검사(허용 목록)**: `AdminAiChatStatsResponse` 칸만 옮긴다. 칸이 없거나 타입이 다르거나(문자열 숫자·소수·음수) 요청 기간과 다르거나 날짜 줄 수가 기간과 다르거나 `generatedAt`이 시각이 아니면 503. AI가 null(모름)을 준 개수는 null 그대로다. AI가 칸을 더해도(원문 등) 자동으로 나가지 않는다.
4. **오류 바꾸기**: AI 클라이언트의 `CHAT_TIMEOUT` → `AI_STATS_TIMEOUT`, 그 밖(`CHAT_UNAVAILABLE` 등) → `AI_STATS_UNAVAILABLE`. 상담용 문구("AI 상담 서버…")가 관리자 화면에 나가지 않는다. AI 4xx는 BE가 기간을 먼저 검사했으므로 계약 어긋남(옛 AI 서버의 404 등)으로 보고 503.
5. **로그**: `[ADMIN-AI-STATS]` adminId·기간·캐시 여부·소요 ms·실패 코드만.

## 응답 예시

```json
{"success":true,"data":{
 "from":"2026-10-01","to":"2026-10-31","totalChats":577,"users":7,
 "byRole":{"guardian":300,"ward":39,"unknown":238},
 "byRoute":{"reservation":302,"symptom":234,"general":41,"unknown":0},
 "emergencyGuidance":36,"reservationCompleted":42,"fallbackReplies":0,
 "daily":[{"date":"2026-10-01","totalChats":0}],
 "generatedAt":"2026-10-28T11:03:27+09:00"}}
```

화면에 적을 것: 응급 안내 = 고위험 판정 기준(실제 응급보다 조금 많을 수 있음) / 역할 "모름"은 10/08~10/12 옛 기록 / 시험 계정 상담 포함 / 화면 진입·기간 변경 때만 호출.

## 변경 파일

| 파일 | 내용 |
|---|---|
| `domain/chat/client/AiChatClient.java` | `stats(from, to)` 추가(기존 메서드 무변경) |
| `domain/admin/controller/AdminAiStatsController.java` (신규) | 경로 1개, Swagger |
| `domain/admin/service/AdminAiChatStatsService.java` (신규) | 검사·속도 제한·캐시·형식 검사·오류 바꾸기 |
| `domain/admin/dto/AdminAiChatStatsResponse.java` (신규) | 응답 record |
| `global/exception/ErrorCode.java` | `AI_STATS_INVALID_RANGE`·`AI_STATS_UNAVAILABLE`·`AI_STATS_TIMEOUT` |
| `global/config/SwaggerConfig.java` | 태그 "관리자 - AI 통계" 등록(`SwaggerTagSyncTest`) |

바뀌지 않는 것: 챗 중계(`/api/guardian/chat`·`/api/ward/chat`), AI 상태(`/api/admin/dashboard/ai-status`), `AiChatClient` 기존 메서드.

## 테스트

- `AdminAiStatsControllerSecurityTest`: ADMIN 허용, 보호자·피보호자 403, 쓰기 매핑 없음
- `AdminAiStatsControllerHttpTest`: 칸 이름·값, null 칸 유지, 오류 `code`(400·503·504)
- `AdminAiChatStatsServiceTest`: 허용 목록 옮기기(원문·userId 미노출), null 유지, 기간 오류 12가지(AI·속도 제한 미호출), 31일 허용, 형식 이상 8가지 503, 오류 바꾸기, 속도 제한 초과, 캐시 60초·만료·실패 미캐시·기간별·32개 상한
- `AiChatClientTest`: 쿼리·키 헤더, AI 404 → `CHAT_UNAVAILABLE`
- 변이 6종 검출(기간 상한·null→0·기간 불일치 검사·캐시 만료·시간 초과 구분·관리자 권한)
- `./gradlew build`: 2066건 통과(건너뜀 1, 기존)
- 작업 중 찾은 것: BE 기간 검사가 `0001-01-01`을 통과시켜 AI 400 → 503으로 보이던 것을 연도 범위로 막았다

## 실서버 확인 (2026-10-28 13:5x, PR #397 머지 `eae3bec`)

- 배포: vkcs CD 성공(통합 테스트 포함, 13:52 기동) / gosky 수동 배포(13:53 기동, gosky override 포함). 두 서버 모두 `eae3bec`, AI WS 연결 확인.
- gosky, 시험 관리자 계정:
  - 오늘(10/28) 200 - 키 `from,to,totalChats,users,byRole,byRoute,emergencyGuidance,reservationCompleted,fallbackReplies,daily,generatedAt`. 상담 9·사용자 2·역할 보호자 8/피보호자 1/모름 0·예약 완료 1(다른 세션 시험 상담)
  - 10월 200 - 상담 586·사용자 7·역할 308/40/238·경로 309/234/43/0·응급 36·예약 완료 43·대체 0, `daily` 31줄
  - 같은 기간 다시 호출 → `generatedAt` 같음(60초 캐시)
  - 32일·`0001`년·`20261001` 형식·`from` 누락 → 모두 400 `AI_STATS_INVALID_RANGE`
  - 보호자 토큰 403, 비로그인 401
- vkcs: 비로그인 401(시험 계정은 gosky에서만 로그인되어 관리자 호출은 gosky에서만 확인).
