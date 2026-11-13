# (2026-11-13) 기능 점검 - 안부 말벗 목소리 중계

> 템플릿 B. 대상 PR #484 `c60f9ec` (`feature/companion-tts-relay`). 점검만 - 코드 변경 없음.
> 짝: AI #281(TTS)·#282(점검 반영, 14:22 gosky 배포), FE #268(`feature/companion-voice-playback`).

## 종합 판정

**조건부 PASS** · 🔴 0 · 🟠 0 · 🟡 1 · 🟢 4. 인가·IDOR·저장 금지·로그 원문 금지는 모두 지켜진다. 남은 것은 1인 동시 상한이 없는 것(다른 피보호자의 목소리가 브라우저 읽기로 밀릴 수 있음)과 잠재 계약 문제다.

## 실서버 확인 (gosky dev, 시험 피보호자, 2026-11-13 14:2x)

| 확인 | 결과 |
|---|---|
| 열기 `audio` (대화 245) | `{index:0}` → 200 `audio/mpeg` 16KB, 0.6초, MP3 24kHz 모노 |
| 대답 1턴 `audio` | `{index:1}` → 200 `audio/mpeg` 33KB, 1.7초(합성 대기 포함) |
| 없는 번호 40 / 끝난 대화의 소리 1 | 404 / 200 |
| 헤더 | `Content-Type`·`Content-Length`·`Cache-Control: no-store`·`X-Content-Type-Options: nosniff` 각 1개(중복 없음) |
| 무인증 / 보호자 토큰 / 번호 abc·51 / 없는 대화 / sessionId 문자 | 401 / 403 / 400 / 404 / 400 `INVALID_INPUT`(값 미노출) |
| BE 로그 | 상태 코드·예외 클래스명만, 대답 원문 0건 |
| 참고 | 첫 시도(대화 244)는 AI #282 배포 재시작과 겹쳐 턴이 `COMPANION_UNAVAILABLE` - 장애 아님. 244는 마감 스케줄러가 닫는다 |

## 엔드포인트 × 역할

| 경로 | WARD | GUARDIAN | ADMIN | 무인증 | 근거 |
|---|---|---|---|---|---|
| `GET /api/ward/companion/sessions/{id}/audio/{index}` | 본인 대화만 | 403 | 403 | 401 | 클래스 `@PreAuthorize("hasRole('WARD')")` + `anyRequest().authenticated()` |

## PHASE A 보안·인가

| 항목 | 판정 | 근거 |
|---|---|---|
| IDOR | PASS | `requireOwned` - 남의 대화 403 `COMPANION_SESSION_NOT_OWNED` + `[IDOR-ATTEMPT]`(wardId·숫자 sessionId만), 없으면 404. AI 호출 전 |
| 확인 순서 | PASS | 스위치 → index 형식(DB·AI 미조회) → 소유 → 동시 상한 → AI. 형식 오류로 대화 존재를 알 수 없음 |
| 계정 상태 | PASS | 다른 말벗 API와 같음(정지·비밀번호 변경 = 토큰 무효화, 역할 변경 = 말벗 기록 삭제 + 토큰 무효화, 탈퇴 = CASCADE + AI `delete_ward`가 소리도 지움) |
| 입력 | PASS | index `^(0|[1-9][0-9]{0,2})$` + 상한 50, sessionId `long`(문자 400) |
| 응답 형식 | PASS | 허용 목록 mpeg·wav·ogg만 - AI가 HTML을 줘도 API 출처로 내보내지 않음(502), `nosniff` |
| 키·리다이렉트 | PASS | `X-API-Key` 헤더만, `setInstanceFollowRedirects(false)`(302 → 503 시험) |
| 로그 | PASS | `CompanionTranscriptLogGuardTest` 통과, 클라이언트 시험이 키·AI 문구·주소 미포함을 고정, 실서버 원문 0건 |
| 저장 | PASS | 메모리 `byte[]`만, 디스크·DB·Redis 쓰기 없음. `no-store` |

## PHASE B 기능 정합성

| 항목 | 판정 | 근거 |
|---|---|---|
| AI 계약 | PASS | AI 경로의 `session_id` = BE 대화 번호(`store.create(..., body.sessionId)`), index 0 = 여는 질문·n = n번째 턴, 200 `audio/mpeg`·503 `TTS_NOT_READY`·404 `TTS_NOT_FOUND`, wait 0~8 |
| `audio` 칸 | PASS | 열기·턴 모두 AI 값 그대로(0~50 정수만), 고정 문장 턴·이어 가기는 null. 테스트 대화 열기도 같은 경로(실측 `{index:0}`) |
| 끝난 대화 | PASS | 상태 무관 허용 - 마지막 턴 마무리 문장(실측 200) |
| 시간 제한 | PASS | 연결 3초·전체 8초(AI wait 5초 + 여유), 마감 시 disconnect(전용 데몬 스레드) |

## PHASE C 구조·계약

| 항목 | 판정 | 근거 |
|---|---|---|
| 트랜잭션 | PASS | AI 호출은 트랜잭션 밖, 쓰기 없음 |
| Swagger | PASS | 설명·오류 7종, `SwaggerConfig` 순서 목록 |
| 응답 필드 | PASS | `audio`는 항상 있음(null 포함), 기존 필드 불변. FE #268 타입과 일치 |
| FE 처리 | PASS | FE `fetchWardCompanionAudio`는 기본 `Accept`(`*/*`)·`cache: no-store`, `!ok`면 무엇이든 브라우저 읽기(502 포함) |

## PHASE D 테스트

새 시험 4개 클래스(서비스 18·클라이언트 26·컨트롤러 HTTP 10·역할 +2). 공백은 아래 M-1(1인 상한)·L-1(Accept별 오류).

## 이슈

### 🟡 M-1 1인 동시 상한이 없다 - 한 피보호자가 서버 전체 8자리를 다 쓸 수 있다
- `CompanionAudioService`는 서버 전체 세마포어(8)만 있다. 한 피보호자가 병렬로 8건 이상 보내면(AI는 합성 대기로 최대 5초 붙든다) 그동안 **다른 피보호자의 목소리가 모두 503**(브라우저 읽기로 대체)이 된다. 다른 API·SOS에는 영향 없음(상한이 막음).
- 같은 종류 중계의 선례: 챗 중계 전체 10/1인 1, 챗 STT 전체 4/1인 1, 말벗 턴 대화 1.
- 제안: 피보호자당 동시 2(지금 줄 + 다음 줄 미리 받기 여유) - 넘치면 AI를 부르지 않고 503 `COMPANION_AUDIO_NOT_READY`. 시험: 같은 피보호자 3번째 거절·다른 피보호자는 통과·오류 뒤 자리 반환.

### 🟢 L-1 `Accept`가 소리 형식뿐이면 오류가 401로 바뀐다 (잠재)
- 실측: `Accept: audio/mpeg`·`audio/*`로 없는 대화·없는 번호를 요청하면 404 대신 **401**. `GlobalExceptionHandler`의 JSON 오류를 그 `Accept`로 쓸 수 없어(406) 오류 디스패치가 다시 보안 필터를 지나 401이 된다. `*/*`면 정상 404.
- 지금 FE는 `*/*`라 영향 없다. 다만 `Accept`를 소리로 좁히는 클라이언트는 401을 "로그인 만료"로 오해해 토큰 갱신·로그아웃 흐름을 탈 수 있다.
- 제안: ① FE 계약에 "`Accept`를 소리 형식으로만 좁히지 말 것" 명시(권장), ② `GlobalExceptionHandler` 오류 응답에 `Content-Type: application/json`을 명시해 협상을 건너뛰기(전 경로 영향 - 영향 범위 점검 C 필요).

### 🟢 L-2 AI가 소리를 대화 번호만으로 찾는다 - BE 두 곳이 같은 AI를 쓰면 섞인다 (잠재)
- AI `tts_service.get(session_id, index)`는 피보호자를 보지 않는다. 다른 말벗 AI 경로는 AI가 만든 `dialogueId`를 쓰지만 이 경로만 BE 순번 `sessionId`다. vkcs와 gosky가 같은 AI를 쓰면 같은 번호의 다른 피보호자 소리가 나간다(BE 소유 확인은 각자 DB 기준이라 못 막음).
- 지금은 vkcs 말벗 꺼짐(설정 없음 확인) - 노출 없음. 정책으로 "말벗 AI 하나를 BE 두 곳이 같이 쓰지 말 것"을 policy-companion에 기록했다. AI가 `wardId`를 함께 받아 대조하게 하는 것은 AI 범위 선택지.

### 🟢 L-3 FE의 소리 요청은 토큰 갱신을 타지 않는다 (FE 범위)
- `fetchWardCompanionAudio`는 `apiClient`가 아닌 맨 `fetch`라 access token이 만료되면 401 → 브라우저 읽기로 조용히 대체되고, 다음 `apiClient` 호출이 토큰을 갱신할 때까지 목소리가 안 나온다. 기능은 유지(대체 읽기). FE 전달 사항으로만 기록.

### 🟢 L-4 턴 로그 `audio=`는 "음성 대답"이라는 뜻이다 - 새 목소리 칸과 이름이 겹친다
- `CompanionService.handleTurn`의 `[COMPANION] 턴 처리: ... audio={}`는 대답이 음성이었는지다(실측 `audio=false` 턴에 목소리 index 1이 나감). 로그를 읽는 사람이 목소리 유무로 오해할 수 있다. 제안: `voiceAnswer=`로 이름만 바꾸기(동작 무변경).

## 조치 (2026-11-13, 사용자 "전부 반영", `fix/companion-tts-audit`)

| 이슈 | 조치 | 시험 |
|---|---|---|
| 🟡 M-1 | `CompanionAudioService` 피보호자당 동시 2(`COMPANION_AUDIO_MAX_PER_WARD`, 기본 2) - 넘치면 AI를 부르지 않고 503 `COMPANION_AUDIO_NOT_READY` + `[COMPANION-LIMIT]` WARN. 피보호자 칸은 0이 되면 지운다. 순서: 피보호자 자리 → 전체 자리 → AI, 실패해도 둘 다 반환 | `perWardLimit`(3번째 거절·다른 피보호자 통과·자리 반환)·`perWardReleasedOnFailure` + **실서버(시험 대화 253)**: 대답 직후 합성 중인 소리를 동시 4건 → 200 2건(1.1초 대기)·503 2건(0.16초, AI 미호출), `[COMPANION-LIMIT] … 피보호자당` WARN 4건(두 턴) |
| 🟢 L-1 | 목소리 엔드포인트 안에서 `CustomException`(정확히 그 클래스)만 잡아 같은 상태·본문·로그로 내되 `Content-Type: application/json`·`no-store`를 못박는다(협상 건너뜀). 429 등 전용 처리가 있는 하위 예외는 전역 처리기 그대로. 컨트롤러 전체 `@ExceptionHandler`는 쓰지 않았다 - 다른 말벗 API의 429 `Retry-After`를 가로챈다 | `errorsIgnoreAudioAccept`(audio/mpeg·audio/*·목록 → 404 JSON, **수정 한 줄을 빼면 3건 실패 확인**)·`tooManyRequestsKeepsRetryAfter` |
| 🟢 L-2 | BE는 AI 목소리 요청에 대화 주인 `X-Companion-Ward-Id` 헤더를 싣는다(형식 `^[A-Za-z0-9]{1,16}$`, 틀리면 AI 미호출). AI 대조는 아래 AI 프롬프트(AI 재시작이 필요해 확인 지점). 정책 "BE 두 곳 공유 금지"는 그대로 | `relaysAudio`(헤더 값·URL 미포함)·`rejectsBadWardId` 6종 |
| 🟢 L-3 | **바꾸지 않음** - FE 점검 `audit-companion-voice.md`(FE #270)이 같은 점을 보고 PASS로 정했다: `apiClient`를 쓰지 않는 것은 소리 하나의 401로 세션 정리(로그아웃)를 타지 않게 하려는 의도이고, 소리 요청 직전 대답 요청이 토큰을 갱신하며 실패해도 기기 음성으로 읽는다. `refreshAccessToken`은 갱신 거절 시 `clearSession()`을 불러 그 의도와 어긋난다 | - |
| 🟢 L-4 | 턴 로그 `audio=` → `voiceAnswer=`(동작 무변경) | `CompanionTranscriptLogGuardTest` 통과 |

`./gradlew build` 통과.

**후속(같은 날)**: L-2 AI 대조는 AI #285 `c7721e3` 머지(헤더가 있으면 소리 주인과 대조, 다르면 기다리지 않고 404, 없으면 옛 동작 - TTS 시험 24 passed, 말벗 시험 실패 목록 수정 전과 동일). **gosky AI 배포 완료**(사용자 "배포해", 14:55:40 `c7721e3`, 롤백 `sb-ai-rollback:20261113-pre-tts-owner` 1개) - 실서버: BE 경유 200, AI 직접 다른 주인 404·헤더 없음 200·같은 주인 200(시험 대화 252). L-3은 위처럼 FE 결정 유지.

### AI 전달 프롬프트 (L-2) - AI #285로 반영됨

```
[AI] 말벗 목소리 - 대화 주인 대조. 보고 언어: 모든 PHASE 보고와 최종 보고는 한국어로.
배경: BE(#485)가 GET /companion/sessions/{sessionId}/utterances/{index}/audio 에 X-Companion-Ward-Id 헤더(대화 주인 wardId)를 싣는다.
AI는 소리를 session_id만으로 찾아, BE 두 곳이 같은 AI를 쓰면 같은 번호의 남의 소리가 나갈 수 있다.
할 일: tts_service에 소리를 만들 때 wardId를 함께 두고(start에 이미 d.wardId가 들어온다), get에서 헤더 값이 있으면 다르면 404 TTS_NOT_FOUND
(존재를 드러내지 않게 없는 것과 같게). 헤더가 없으면 지금처럼 동작(옛 BE 호환). 로그에 wardId·문장 원문 금지.
시험: 같은 wardId 200 / 다른 wardId 404 / 헤더 없음 200. 배포는 AI 재시작이 필요하니 사용자 확인 후.
```

### FE 전달 프롬프트 (L-3) - 쓰지 않음(FE #270 결정 유지)

```
[FE] 말벗 목소리 요청의 토큰 갱신. 보고 언어: 모든 PHASE 보고와 최종 보고는 한국어로.
배경: fetchWardCompanionAudio(src/service/api/ward/companion.ts)는 apiClient가 아닌 맨 fetch라 access token이 만료되면 401 →
브라우저 읽기로 조용히 대체되고, 다른 apiClient 호출이 갱신할 때까지 서버 목소리가 나오지 않는다.
할 일: 401이면 apiClient와 같은 갱신 함수(진행 중인 갱신 공유)로 한 번 갱신하고 한 번만 다시 요청, 그래도 실패하면 지금처럼 브라우저 읽기.
Accept는 지정하지 않는다(기본 */* - 소리 형식으로만 좁히지 말 것, BE 점검 L-1). cache: 'no-store' 유지. 소리를 저장하지 않는다.
시험: 401 → 갱신 → 200 재생 / 갱신 실패 → 브라우저 읽기 / 404·502·503 → 갱신 없이 브라우저 읽기.
```
