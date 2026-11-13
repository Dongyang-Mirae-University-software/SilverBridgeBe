# (2026-11-13) 안부 말벗 목소리 중계

> 피보호자 본인 대화에서 말벗이 한 말의 소리(AI TTS, AI #281)를 BE가 중계한다. 마이그레이션 없음.

## 무엇을

| 항목 | 내용 |
|---|---|
| 새 API | `GET /api/ward/companion/sessions/{sessionId}/audio/{index}` (WARD) |
| AI 호출 | `GET /companion/sessions/{sessionId}/utterances/{index}/audio?wait=5` (`sessionId` = BE 대화 번호, AI가 대화 열 때 받은 값) |
| 응답 칸 | 대화 열기 `SessionResponse.audio`·턴 `TurnResponse.audio` = `{"index": n}` 또는 `null` (기존 필드 불변, 필드는 항상 있음) |
| index | 0 = 여는 질문, n = n번째 턴 뒤 말벗 문장 (AI 계약) |

## 규칙

- **본인 대화만**: 기존 `requireOwned` 재사용 - 없으면 404 `COMPANION_SESSION_NOT_FOUND`, 남의 것 403 `COMPANION_SESSION_NOT_OWNED` + `[IDOR-ATTEMPT]` WARN. 끝난 대화도 받는다(마지막 대답의 마무리 문장).
- **스위치**: 다른 말벗 API와 같은 `requireUsable`(꺼짐 503 `COMPANION_DISABLED`). 계정 상태는 인증 필터·토큰 무효화가 막는다(다른 말벗 API와 같음).
- **index**: `0` 또는 앞 0 없는 1~3자리 정수, 상한 `audio-max-index` 50. 틀리면 400 `COMPANION_AUDIO_INDEX_INVALID`(DB·AI 미조회, 로그는 길이·상한만).
- **AI 응답 매핑**

| AI | BE |
|---|---|
| 200 + `audio/mpeg`·`audio/wav`·`audio/ogg`, 1MB 이하 | 200 바이트 그대로, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` |
| 200 + 다른 형식·형식 없음·빈 본문·1MB 초과 | 502 `COMPANION_AUDIO_BAD_UPSTREAM` |
| 404 | 404 `COMPANION_AUDIO_NOT_FOUND` |
| 503·그 밖 상태·리다이렉트·시간 초과·연결 실패 | 503 `COMPANION_AUDIO_NOT_READY` |

- FE는 404·503(그리고 502)이면 브라우저 읽기로 대신한다.
- **시간 제한**: 연결 3초(`connect-timeout`), 읽기·전체 8초(`COMPANION_AUDIO_CALL_TIMEOUT`, AI wait 5초 + 여유), 마감 시 연결을 끊는다(기존 말벗 클라이언트 방식, 전용 데몬 스레드). 리다이렉트 끔.
- **동시 상한** 8(`COMPANION_AUDIO_MAX_CONCURRENT`): 대답 턴 상한과 따로. 넘치면 AI를 부르지 않고 503 `COMPANION_AUDIO_NOT_READY`. 속도 제한(분 20·시간 200)은 대답 턴 몫이라 걸지 않는다.
- **저장 없음**: 소리는 요청 처리 중 메모리에만, 디스크·DB·Redis에 두지 않는다(음성 미저장 불변 규칙).
- **로그**: 상태 코드·예외 클래스명·길이·상한만. AI 오류 문구·키·주소·문장 없음(`CompanionTranscriptLogGuardTest` 통과, 클라이언트 시험이 로그 내용을 고정).
- **고정 문장 턴**: AI가 응답 문장을 주지 않아 BE가 고정 문장으로 바꾼 턴은 `audio`를 null로 둔다(소리와 문장이 어긋남).
- **이어 가는 대화**: AI를 부르지 않으므로 `audio` null.

## 바뀐 파일

- `ErrorCode` - `COMPANION_AUDIO_INDEX_INVALID`(400)·`_NOT_FOUND`(404)·`_NOT_READY`(503)·`_BAD_UPSTREAM`(502). 계획은 2개였으나 400·502를 나눴다.
- `CompanionProperties`·`application.yaml` - `audio-wait-seconds`·`audio-call-timeout`·`audio-max-bytes`·`audio-max-index`·`audio-max-concurrent`
- `AiCompanionClient.fetchUtteranceAudio` + `CompanionAudio` record
- `CompanionAudioService`(새) - 스위치 → index → 소유 → 동시 상한 → AI
- `CompanionService` - `audioRef`(AI `audio.index` 0~50 정수만), `requireOwned`·`requireUsable` 패키지 공개
- `CompanionDtos` - `UtteranceAudio`, `SessionResponse`·`TurnResponse`에 `audio`(옛 생성자 유지)
- `WardCompanionController` - 새 엔드포인트, `SwaggerConfig` 경로 순서

## 바뀌지 않는 것

DB·마이그레이션·SecurityConfig(GET이라 본문 파싱 전 차단 불필요)·턴/마치기 흐름·위험 알림·대화 내용 저장·기존 응답 필드.

## 시험

- `CompanionAudioServiceTest` - 본인 200·끝난 대화·남의 대화 403 + WARN·없는 대화 404·index 9종 400(원문 로그 없음)·상한 50·스위치·오류 시 자리 반환·동시 상한·`audioRef` 매핑
- `AiCompanionClientAudioTest` - 실제 HTTP: 경로·`wait=5`·키 헤더, 허용 형식 4종, 거부 형식 5종, 형식 없음, 1MB 초과(표기·chunked), 정확히 1MB, 빈 본문, 404, 503·500·401·422·400, 302 미추종, 시간 초과(1초 안에 끊음), 연결 실패, 미설정, 로그 원문 없음
- `WardCompanionAudioControllerHttpTest` - 바이트·형식·Content-Length·no-store·nosniff, `Accept: audio/*`, 오류 7종 JSON, 턴 응답 `audio` 직렬화
- `WardCompanionTranscriptConsentSecurityTest` - 보호자 토큰 403
- `./gradlew build` 통과
