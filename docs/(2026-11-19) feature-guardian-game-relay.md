# 보호자 게임 기록 중계 API (게임 관리 405 수정) - 2026-11-19

## 1. 배경

- 보호자 "게임 관리"(FE `/guardian/game`)가 "게임 기록을 불러오지 못했습니다"를 띄웠다.
- FE는 AI 게임 API를 FE `/api/streams` 프록시로 GET 호출하는데, 이 프록시는 2026-10-06 보안 수정(FE ad44cb6) 뒤 **POST + 송출 3개 경로**만 받는다 → `GET /api/streams/v1/games/progress`·`activity`가 405.
- FE가 게임 사용자 번호(`toGameUserId(partnerUserId)`)를 클라이언트에서 만들어 넘기는 구조라, 프록시가 열려 있었다면 남의 기록 조회가 가능했다.
- 프록시를 다시 열지 않고, 영상(2026-10-03)·챗(2026-10-07)처럼 **BE가 인가 후 중계**한다.

## 2. 전제 검증 (PHASE 0)

| 전제 | 실제 | 판정 |
|---|---|---|
| 프록시가 POST 3경로만 | `export const POST`만, `v1/stream-sessions`·`/{id}/frame`·`/{id}/stop` | ✅ (GET은 Next가 405) |
| FE가 게임 번호 계산 | `GuardianGameContent.tsx:95,98` | ✅ |
| `toGameUserId` 식 | `h=h*31+code\|0` → `Math.abs(h)\|\|1` = Java `hashCode` | ⚠️ JS `Math.abs(-2^31)`=2147483648 → BE는 `long` |
| AI `/progress`·`/activity` | `userId:int`, `days` 기본 182·AI가 1~366으로 자름, 날짜 하한 없음 | ✅ (D-1 불가) |
| 게임 전용 키 | `require_game_api_key` = 공용 `API_KEY`(`X-API-Key`) | ⚠️ BE `AI_API_KEY`와 같은 키 → 새 env 없음 |
| 피보호자 플레이 | AI `/games/embed` iframe 직접(키 없음) | ✅ 영향 없음 |
| 시각 형식 | 오프셋 없는 UTC, FE #259가 UTC로 읽음 | ⚠️ 변환 없이 그대로 |
| progress 응답 | FE 미사용 `state`(게임 내부 상태) 포함 | ⚠️ 허용 목록으로 제외 |

## 3. 결정 (2026-11-19 사용자 - "추천대로")

1. 새 도메인 `domain/game`
2. D-1(연결 이후 기록만) **미적용** - 누적 진행 상황이고 AI에 날짜 하한 없음(수용)
3. 피보호자 본인 화면은 범위 밖(iframe 직접, 정상 동작)
4. 새 env 없음 - `AI_HTTP_BASE_URL`·`AI_API_KEY` 공유

## 4. API 계약 (FE 전달용)

공통: `Authorization: Bearer`, **GUARDIAN 전용**(WARD·ADMIN 403), `Cache-Control: no-store`, 응답은 공통 `{success, message, data}`.

### `GET /api/guardian/game/progress?wardId={피보호자 ID}`

```json
{ "wardId": "aB3x9Z", "totalScore": 120, "totalAttempts": 9, "lastPlayedAt": "2026-11-18T05:03:09.123456",
  "games": [
    { "game": { "slug": "maze", "title": "…", "description": "…", "totalStages": 10, "themeColor": "#…" },
      "progress": { "gameSlug": "maze", "currentStageNo": 3, "score": 120, "attempts": 9, "cleared": false,
                    "lastAnswerCorrect": true, "startedAt": "…", "updatedAt": "…", "clearedAt": null } },
    { "game": { … }, "progress": null } ] }
```

- 안 해 본 게임은 `progress: null`. 시각은 **시간대 표기 없는 UTC**(지금처럼 `parseServerTime`으로 읽기).
- 옛 AI 응답과 차이: `userId`(게임 번호)·`progress.userId`·`progress.state` 없음, `wardId` 추가.

### `GET /api/guardian/game/activity?wardId=&days=`

```json
{ "wardId": "aB3x9Z", "days": 182, "activity": [ { "date": "2026-11-17", "attempts": 4, "correct": 3, "score": 30 } ] }
```

- `days` 선택(1~366, 생략 182). 활동 있는 날만, `date`는 KST `YYYY-MM-DD`.

### 오류 코드

| 상태 | 코드 | 언제 |
|---|---|---|
| 400 | `GAME_INVALID_DAYS` | days가 1~366 밖 |
| 403 | `GAME_NOT_AUTHORIZED` | ACTIVE 연결이 아닌 피보호자, 형식이 틀린 wardId |
| 403 | (역할) | 보호자 아닌 계정 |
| 503 | `GAME_UNAVAILABLE` | AI 장애·시간 초과(8초)·키 불일치·응답 형식 오류 |

### FE 할 일 (프롬프트 ②)

- `service/api/guardian/game.ts`를 `streamClient`(프록시) 대신 BE 클라이언트로 `/api/guardian/game/*` 호출, 파라미터는 `wardId = ward.partnerUserId`.
- 보호자 화면의 `toGameUserId` 사용 제거(피보호자 iframe은 그대로 사용). `interface/game.ts`의 보호자 응답 타입에서 `userId` → `wardId`.
- **프록시(`/api/streams`)에 게임 경로를 추가하지 말 것.**

## 5. 변경 파일

| 파일 | 내용 |
|---|---|
| `domain/game/controller/GuardianGameController.java` | 두 엔드포인트, `@PreAuthorize GUARDIAN`, no-store |
| `domain/game/service/GuardianGameService.java` | 형식 검사 → `isActiveConnection` → AI, 허용 필드 변환, days 검증 |
| `domain/game/service/GameUserIds.java` | FE `toGameUserId`와 같은 번호(`long`) |
| `domain/game/client/AiGameClient.java` | HttpURLConnection, 키 헤더·리다이렉트 끔·전체 마감·256KB 상한, 실패=503 |
| `domain/game/config/GameRelayProperties.java` + `application.yaml` `game.relay.*` | 기존 env 공유, 시간 제한 3/5/8초 |
| `domain/game/dto/*Response.java` | 허용 목록 record |
| `global/exception/ErrorCode.java` | `GAME_NOT_AUTHORIZED`·`GAME_INVALID_DAYS`·`GAME_UNAVAILABLE` |
| `global/config/ExternalUrlSchemeChecker.java` | `game.relay.ai-base-url` |
| `global/config/SwaggerConfig.java` | 태그 "보호자 - 게임" |
| 테스트 5종 + `LogRawExceptionGuardTest` 목록 | 아래 |

**바뀌지 않는 것**: FE 스트림 프록시, AI 서버 코드·설정, 피보호자 게임 iframe, 다른 보호자 API, 마이그레이션 없음, 서버 `.env` 변경 없음.

## 6. 테스트

- `GameUserIdsTest`(7): FE 함수를 node로 돌린 값 5개(`aB3x9Z`→1455350745 등) + `-2^31`→2147483648 + 0→1
- `GuardianGameServiceTest`(15): 연결 OK·필드 변환(state 제외·시각 원문)/미연결 403·AI 미호출/형식 오류 6종 DB·AI 미조회/days 기본·경계 1·366/범위 밖 400/AI 503 전달
- `AiGameClientTest`(9): 키 헤더·쿼리/5xx·401→503/시간 초과/리다이렉트 미추종/형식 오류/크기 상한/키 미설정
- `GuardianGameControllerHttpTest`(3): 실제 HTTP 직렬화·no-store·`userId` 없음
- `GuardianGameControllerSecurityTest`(3): GUARDIAN 허용, WARD·ADMIN 403
- `./gradlew build`: **2759 tests, 0 failed, 21 skipped** (`SwaggerTagSyncTest`가 태그 미등록을 잡아 SwaggerConfig에 추가)

## 7. 검증 가이드 (배포 후)

1. 보호자 시험 계정 로그인 → `GET /api/guardian/game/progress?wardId={연결된 피보호자}` 200, `games` 배열
2. 같은 계정으로 연결 없는 wardId → 403 `GAME_NOT_AUTHORIZED`
3. 기동 로그에 `[EXTERNAL-URL-PLAINTEXT]`가 `game.relay.ai-base-url`로 새로 뜨지 않는지(https면 정상)

## 8. 배포 순서

BE 먼저(dev 머지 → vkcs CD, gosky 수동) → FE ②. BE만 배포된 동안 FE 화면은 지금처럼 405(변화 없음).
