# 챗봇 마이크 음성 인식 중계 기능 점검 (템플릿 B, 2026-11-04)

- 대상: BE #448(머지 `07b5e1d`) `POST /api/ward/chat/stt`·`POST /api/guardian/chat/stt` → AI `POST /companion/stt`(AI #177, main `4e6cb92`)
- 범위: 점검만(코드 수정 없음). AI 저장소는 `origin/main`을 읽기만 했다. 실제 AI 호출 없음(미배포·스위치 꺼짐).
- **판정: 조건부 PASS** - 🔴 0 · 🟠 0 · 🟡 1 · 🟢 5

## PHASE -1. 사전 환경

| 항목 | 결과 |
|---|---|
| 날짜 | 2026-11-04 |
| 로컬 dev | `07b5e1d` 포함 |
| `./gradlew test` | 2367건 통과(건너뜀 19, 실패 0) |
| vkcs-linux | `07b5e1de`, `dmu-dev-api` healthy, 익명 POST 두 경로 401 |
| gosky | `07b5e1de`, `dmu-dev-api` healthy, 익명 POST 두 경로 401(공개 도메인도 401) |
| 스위치 | `application.yaml` `chat.stt.enabled=${CHAT_STT_ENABLED:false}`, 주소·키 기본값 빈 값 |
| `.env.dev`의 `CHAT_STT_*` | 두 서버 모두 **키 없음**(0개, 값 미출력) → 503 `CHAT_STT_DISABLED` 상태 |

## PHASE 0. 점검 파일

`domain/chat/client/AiSttClient.java` · `config/ChatSttProperties.java` · `service/ChatSttService.java` · `dto/ChatSttResponse.java` · `controller/WardChatController.java`·`GuardianChatController.java`·`ChatSttApiDoc.java` · `global/exception/ErrorCode.java:195-199` · `GlobalExceptionHandler.java:43,262-285` · `global/security/SecurityConfig.java:108-137` · `application.yaml:39-43,393-404` · 테스트 `AiSttClientTest`·`ChatSttServiceTest`·`(Ward|Guardian)ChatController(Security|Http)Test`·`LogRawExceptionGuardTest:46-47` · AI `app/routers/companion_router.py:321-377`·`app/core/companion_errors.py`·`companion_security.py`·`config.py:313-345`

### 엔드포인트 × 역할

| 경로 | WARD | GUARDIAN | ADMIN | 익명 |
|---|---|---|---|---|
| `POST /api/ward/chat/stt` | 200 | 403(파싱 뒤, M-1) | 403(파싱 뒤, M-1) | 401(실측) |
| `POST /api/guardian/chat/stt` | 403(파싱 뒤, M-1) | 200 | 403(파싱 뒤, M-1) | 401(실측) |

## PHASE A. 보안·인가

| 항목 | 결과 |
|---|---|
| 클래스 `@PreAuthorize`가 새 메서드에 적용 | PASS - `SecurityTest`가 교차 역할·ADMIN 403과 서비스 미호출을 고정 |
| URL 수준 역할(본문 파싱 전 차단) | **FAIL → M-1** |
| 사용자 식별·IDOR | **PASS** - `@AuthenticationPrincipal`만 사용. 요청에 사용자·대상 칸이 없고 업로드 파일 이름은 버리고 고정 `audio`로 보낸다 |
| 키 보호 | PASS - `X-API-Key` 헤더만, `setInstanceFollowRedirects(false)` + 고정 길이 스트리밍(JDK도 POST 리다이렉트를 따라가지 않음), 제어문자 키는 호출 전 차단(값 미기록), 준비 단계 예외는 클래스명만(JDK 헤더 오류 메시지의 키 원문 차단) |
| 무저장·무로그 | PASS - DB 쓰기 없음. 로그는 userId·바이트 수·처리 시간·`empty` 여부·오류 코드 이름. AI 오류 본문은 `code`만 읽고(8KB 상한) 목록 밖 코드는 `unknown`. `LogRawExceptionGuardTest` 등록 확인. 테스트가 글자·키 미출력을 고정 |
| 업로드 경계 | PASS(정보 1건) - 없음·빈 파일 400 `CHAT_STT_AUDIO_INVALID`, 서블릿 10MB 초과 413 `CHAT_STT_AUDIO_TOO_LARGE`(경로 분기), multipart 아님 415, 깨진 multipart 400 `FILE_REQUIRED`(L-4). 서블릿 `max-request-size` 10MB는 multipart 머리 포함이라 서비스 10MB 검사는 사실상 도달하지 않는다(문구 같음, 영향 없음) |
| 남용 | PASS - 속도 제한 키는 토큰 userId(`chat-stt`, 분 10·시간 100, Redis 장애 시 fail-open은 공용 정책), 순서 스위치→속도 제한→파일→1인→전체, 1인·전체 자리는 `finally`로 반납(시간 초과도 `CustomException`) |
| 인증 필터 | PASS - permitAll·SOS fail-open(`/api/ward/sos` 정확 일치) 어디에도 해당 없음 |

## PHASE B. AI 계약 대조 (AI main `companion_router.py` `/stt`)

| AI 응답 | BE 매핑 | 클라이언트 HTTP |
|---|---|---|
| 200 `data.text` | 그대로(빈 글자 허용) | 200 `{text}` |
| 200 `data.text` 없음·문자열 아님·JSON 아님 | `CHAT_STT_UNAVAILABLE` | 503 |
| 413 `AUDIO_TOO_LARGE` / `AUDIO_TOO_LONG` | `CHAT_STT_AUDIO_TOO_LARGE` | 413 |
| 422 `AUDIO_MISSING`·`AUDIO_FORMAT_UNSUPPORTED`·`AUDIO_UNREADABLE` | `CHAT_STT_AUDIO_INVALID` | 400 |
| 503 `STT_BUSY` | `CHAT_STT_BUSY` | 503 |
| 503 `STT_UNAVAILABLE` | `CHAT_STT_UNAVAILABLE` | 503 |
| 401 `AUTH_INVALID_KEY` | `CHAT_STT_UNAVAILABLE` + ERROR 로그(코드 이름만) | 503 |
| 404 경로 없음(옛 AI, `{"detail":..}`) | `code` 없음 → `CHAT_STT_UNAVAILABLE` + ERROR `unknown` | 503 |
| 3xx | 따라가지 않음 → `CHAT_STT_UNAVAILABLE` | 503 |
| 연결 실패·30초 초과 | 연결 끊기 → `CHAT_STT_UNAVAILABLE` | 503 |

- 9종 모두 빠짐없이 매핑됨. AI 오류 본문은 `code`·`errorCode`를 같은 값으로 싣고(`companion_error_body`) BE는 `code`를 읽는다 - 일치.
- 필드명 `audio`, 업로드 상한(AI 본문 10MB+4KB / 파일 10MB, BE 파일 10MB) 일치.
- 스위치 꺼짐·주소/키 없음은 서비스 첫 줄에서 503 `CHAT_STT_DISABLED`(AI 미호출, `ChatSttServiceTest.disabled/notConfigured`).
- **시간 판단(측정은 AI 배포 후)**: AI 자리 대기 5초 + SenseVoice CPU(기본 엔진, 평균 0.6초 측정)이면 BE 30초는 넉넉하다. 단 `/stt` 경로에는 AI 쪽 받아쓰기 시간 제한이 없다(L-3). `COMPANION_STT_TIMEOUT_SEC`(40초)는 말벗 대화 경로 전용이라 `/stt`와 무관하고, `COMPANION_STT_MODEL=large-v3`는 `COMPANION_STT_ENGINE=whisper`일 때만 쓰인다 - whisper large-v3 CPU로 바꾸면 30초 음성이 BE 30초를 넘을 수 있다.

## PHASE C. 구조·계약

| 항목 | 결과 |
|---|---|
| 응답 래퍼·`no-store` | PASS - `ApiResponse.ok` + `CacheControl.noStore()`(Http 테스트가 고정) |
| 상태코드·Swagger | 400/401/403/413/429/503 일치. 415·깨진 multipart 400 `FILE_REQUIRED` 미기재(L-4) |
| 오류 코드 규칙 | PASS - `CHAT_STT_` 접두, 받는 사람 기준 고정 문구, AI 문구 미전달 |
| 요청 스레드 점유 | PASS - 전체 4 × 30초 = 최대 4스레드(Tomcat 200 중), SOS 등 영향 없음. 마감 스레드는 `@PreDestroy shutdownNow`, 종료 뒤 예약 거절은 503. 마감 스레드 수 1(L-5) |
| 기존 챗 경로 영향 | PASS - `ChatRequestSizeFilter`는 `/api/guardian/chat`·`/api/ward/chat` 정확 일치라 `/stt`와 겹치지 않음. 기존 챗 테스트 통과 |
| 말벗 코드와 분리 | PASS - 클라이언트·설정·속도 제한 키 모두 별도 |
| FE 전달 노션 페이지 | **미확인** - 이 세션에 노션 도구가 없다. 코드 기준 계약은 이 문서 PHASE B 표와 `ChatSttApiDoc` |

## PHASE D. 테스트

| 변이 | 결과 |
|---|---|
| M1 `GlobalExceptionHandler` STT 413 분기 무력화 | **살아남음** → L-1 |
| M2 `ChatSttService` `global.release()` 제거 | **살아남음** → L-2 |
| M3 `setInstanceFollowRedirects(true)` | 살아남음 - 동등 변이(고정 길이 스트리밍 POST는 JDK가 리다이렉트를 따라가지 않음). 조치 불필요 |
| URL 수준 역할 규칙 테스트 | 없음 → M-1과 함께 |

변이는 확인 뒤 모두 `git checkout`으로 되돌렸고 `git diff`가 비어 있음을 확인했다.

잘 고정된 것: 성공·빈 글자·오류 9종 매핑·404·`data.text` 없음·리다이렉트·시간 초과(400ms 제한, 1.4초 안 반환)·키 제어문자·로그 미출력(글자·키·AI 문구)·스위치·속도 제한 시 AI 미호출·빈/큰 파일·1인 자리 반납·동시 상한·교차 역할 403.

## 이슈

### 🟡 M-1. STT 두 경로에 URL 수준 역할 규칙이 없다 - 교차 역할 요청이 10MB를 다 읽은 뒤 403

- 위치: `src/main/java/kr/silverbridge/main/global/security/SecurityConfig.java:129-133`
- 내용: 메서드 보안(`@PreAuthorize`)은 multipart 파싱 뒤에 돈다. 약 봉투 OCR(2026-10-22 점검 L-2)과 말벗 음성(같은 날)은 이 이유로 URL 규칙을 넣었는데 STT 두 경로는 빠졌다. 교차 역할 요청은 서비스에 닿지 않아 STT 속도 제한(분 10)도 타지 않는다(공용 사용자 속도 제한만 적용).
- 재현: GUARDIAN 토큰으로 `POST /api/ward/chat/stt`에 9MB 파일 → 서블릿이 본문 전체를 받아 임시 파일로 파싱한 뒤 403.
- 수정안: `.requestMatchers(HttpMethod.POST, "/api/ward/chat/stt").hasRole("WARD")`, `.requestMatchers(HttpMethod.POST, "/api/guardian/chat/stt").hasRole("GUARDIAN")`를 말벗 줄 아래에 추가(컨트롤러 `@PreAuthorize`는 유지). 테스트: `SecurityConfigAccessDeniedTest`·`GuardianMedicationOcrControllerSecurityTest`와 같은 형태로 교차 역할·ADMIN이 필터 단계에서 403(서비스 미호출)임을 고정.

### 🟢 L-1. STT 경로 413 매핑을 지키는 테스트가 없다

- 위치: `GlobalExceptionHandler.java:277-281`
- 재현: 분기를 무력화해도 전체 chat·global 테스트 통과(M1) → 회귀하면 FE는 400 `FILE_TOO_LARGE`(프로필 사진 5MB 문구)를 받는다.
- 수정안: `GuardianMedicationOcrControllerHttpTest:100`처럼 `MaxUploadSizeExceededException` + URI `/api/ward/chat/stt`·`/api/guardian/chat/stt` → 413 `CHAT_STT_AUDIO_TOO_LARGE` 테스트 추가.

### 🟢 L-2. 서버 전체 자리 반납을 테스트가 확인하지 않는다

- 위치: `ChatSttService.java:72`, `ChatSttServiceTest.java:117-127`
- 재현: `global.release()`를 지워도 통과(M2) - 테스트의 `maxConcurrent`가 4라 한 번 새어도 다음 호출이 성공한다. 운영에서는 실패 4번이면 STT가 영구 503 `CHAT_STT_BUSY`.
- 수정안: `releasesOnFailure`에서 `properties.setMaxConcurrent(1)`로 만든 서비스를 쓰고, 실패·시간 초과(`CHAT_STT_UNAVAILABLE`) 뒤 다음 호출이 성공하는지 확인.

### 🟢 L-3. AI `/stt`에는 받아쓰기 시간 제한이 없다 (AI 범위, 판단만)

- 위치: AI `app/routers/companion_router.py:325-332,368`(`_transcribe_with_slot`이 `transcribe_pcm`을 바로 부름. `call_with_timeout`은 말벗 대화 경로 300행에만 있음)
- 영향: 받아쓰기가 멈추면 BE는 30초에 끊고 503을 주지만 AI는 자리(동시 2)를 계속 쥔다 → 이후 요청은 5초 대기 뒤 `STT_BUSY` 연쇄.
- 수정안(AI 팀 전달): `/stt` 받아쓰기를 `call_with_timeout`(≤ 24초 = BE 30초 − 자리 대기 5초 − 여유)으로 감싸 `STT_UNAVAILABLE`로. BE 수정 불필요. AI 배포 후 실측 때 함께 확인.

### 🟢 L-4. Swagger·FE 계약에 415와 깨진 multipart 400 `FILE_REQUIRED`가 없다

- 위치: `WardChatController.java:42-50`, `GuardianChatController.java:42-50`, `ChatSttApiDoc.java:17-20`
- 내용: multipart가 아닌 요청은 415 `UNSUPPORTED_MEDIA_TYPE`, 깨진 multipart 본문은 400 `FILE_REQUIRED`(`GlobalExceptionHandler.java:128-133`)로 STT 전용 코드가 아니다. FE는 녹음 실패 안내를 `CHAT_STT_AUDIO_INVALID`로만 분기하면 이 둘을 놓친다.
- 수정안: 설명에 두 코드를 적는다(동작 변경 없음).

### 🟢 L-5. 마감 전용 스레드가 1개다 (정책은 클라이언트마다 2)

- 위치: `AiSttClient.java:66`
- 내용: `policy-chat.md` "호출 마감은 전용 스레드"는 다른 AI 클라이언트처럼 2개다. `disconnect()`가 한 번 늦어지면 다른 요청의 마감도 밀린다. 동시 4건이라 실제 영향은 작다.
- 수정안: `newScheduledThreadPool(2, ...)`로 맞춘다.

## 수정용 커밋 메시지 초안

```
fix: 챗봇 음성 인식 경로 역할을 본문 파싱 전에 검사

- SecurityConfig에 /api/ward/chat/stt(WARD)·/api/guardian/chat/stt(GUARDIAN) URL 규칙 추가 - 교차 역할 요청이 10MB를 읽은 뒤에야 403이던 문제(M-1)
- 테스트: 필터 단계 403, STT 경로 413 매핑(L-1), 서버 전체 자리 반납(L-2)
- Swagger에 415·FILE_REQUIRED 안내(L-4), 마감 스레드 2개(L-5)
```

## 조치 (2026-11-04, 브랜치 `fix/chat-stt-hardening`)

| 이슈 | 조치 |
|---|---|
| M-1 | `SecurityConfig`에 `POST /api/ward/chat/stt` WARD·`POST /api/guardian/chat/stt` GUARDIAN URL 규칙. `SecurityConfigAccessDeniedTest.chatStt_roleCheckedAtUrlLevel`(메서드 보안 없는 시험 매핑으로 다른 역할·ADMIN 403, 비로그인 401, 해당 역할 200) |
| L-1 | `ChatSttUploadLimitTest` - 두 경로 413 `CHAT_STT_AUDIO_TOO_LARGE`, 비슷한 다른 경로는 바뀌지 않음 |
| L-2 | `ChatSttServiceTest.releasesOnFailure` - 전체 상한 1에서 BUSY·UNAVAILABLE·예상 밖 예외 뒤에도 다음 호출 성공 |
| L-4 | Swagger 400(`FILE_REQUIRED`)·415 설명 추가, `ChatSttApiDoc`에 안내 |
| L-5 | 마감 전용 스레드 2개 |
| L-3 | AI 범위 - BE 변경 없음, AI 팀 전달 |

변이 재확인: 413 분기 무력화·`global.release()` 제거·STT URL 규칙 제거 모두 테스트가 잡는다(되돌린 뒤 `git diff`에 변이 없음). 전체 단위 테스트 2374건 통과, `build -x test` 성공.

## AI 직접 실호출 확인 (2026-11-04 23:29 KST, gosky, `/tmp/ai-measure.lock` 사용)

- gosky AI(`silverbridge-ai-server`, 6017)는 #177(`4e6cb92`)로 떠 있다. gosky BE `.env.dev`에는 이미 `CHAT_STT_*` 3개가 들어 있다(23:02, 백업 `.env.dev.bak-20261104-2302`, 주소·키 = 말벗 값, 이 세션이 넣은 것 아님) → `dmu-dev-api`가 `CHAT_STT_ENABLED=true`로 떠 있다. vkcs는 키 없음(꺼짐).
- 측정 음성: Windows TTS(한국어) 5초·25초 WAV, ffmpeg로 WebM(Opus)·MP3·50초 WAV·무음·깨진 파일.

| 입력 | AI 응답 | BE가 내릴 값 |
|---|---|---|
| WAV·WebM·MP3 5초, WAV 25초, 무음 3초 | **503 `STT_UNAVAILABLE`**(즉시) | 503 `CHAT_STT_UNAVAILABLE` |
| WAV 50초 | 413 `AUDIO_TOO_LONG` | 413 `CHAT_STT_AUDIO_TOO_LARGE` |
| 임의 바이트 | 422 `AUDIO_FORMAT_UNSUPPORTED` | 400 `CHAT_STT_AUDIO_INVALID` |
| 머리만 WAV | 422 `AUDIO_UNREADABLE` | 400 `CHAT_STT_AUDIO_INVALID` |
| `audio` 칸 없음 | 422 `AUDIO_MISSING` | 400 `CHAT_STT_AUDIO_INVALID` |
| 키 없음 | 401 | 503 `CHAT_STT_UNAVAILABLE` |

- **🟠 운영 문제 - gosky AI에 SenseVoiceSmall 모델 파일이 없다**: 메시지 "받아쓰기 모델 파일이 없습니다 (SenseVoiceSmall)". 기대 위치는 호스트 `SilverBridgeAiServer/models/companion/stt-sensevoice/`(컨테이너 `/app/models`)인데 `models/companion` 폴더가 없다. DGX에도 HF 캐시 머리 파일(3.6MB)뿐이다. AI는 이 오류를 처음 한 번 기억해 두므로(`sensevoice_service._load`) **파일을 넣은 뒤 AI 재시작이 필요**하다. 같은 받아쓰기를 쓰는 **안부 말벗 음성 대답도 같은 이유로 실패**한다. 지금 BE 스위치가 켜져 있어 마이크를 누르면 항상 "지금은 음성을 알아듣지 못해요. 글로 입력해 주세요."(503)가 나온다.
- 오류 계약(413·422·401)은 PHASE B 표와 실측이 일치한다. 처리 시간·동시 상한은 모델이 없어 재지 못했다.

## L-3 AI 팀 전달문

> `/companion/stt`(companion_router.py `_transcribe_with_slot`)는 받아쓰기에 시간 제한이 없습니다. 말벗 대화 경로는 `call_with_timeout(..., COMPANION_STT_TIMEOUT_SEC)`로 감싸는데 `/stt`는 `transcribe_pcm`을 바로 부릅니다. BE는 30초에 연결을 끊고 503을 주지만, AI는 받아쓰기가 멈추면 자리(동시 2)를 계속 쥐어 이후 요청이 5초 대기 뒤 `STT_BUSY`로 이어집니다. 제안: 자리를 얻은 뒤 받아쓰기를 `call_with_timeout`(새 설정 `COMPANION_STT_API_TIMEOUT_SEC`, 기본 20초, BE 30초 − 자리 대기 5초 − 여유)으로 감싸고, 시간 초과는 `STT_UNAVAILABLE`로 돌려주세요(BE 매핑 그대로). 엔진을 whisper large-v3(CPU)로 바꾸면 30초 음성이 BE 30초를 넘을 수 있으니 엔진 변경 때 BE `CHAT_STT_CALL_TIMEOUT`과 함께 봐 주세요. 별개로 gosky에 SenseVoiceSmall 모델 파일이 없어 `/stt`·말벗 음성 대답이 모두 503입니다(`models/companion/stt-sensevoice/`, 넣은 뒤 재시작 필요).

## 다음

1. 위 전달문을 AI 팀(말벗 담당)에 전달.
2. gosky AI 모델 배치 + 재시작(사용자 확인 필요) 뒤 실호출 처리 시간·동시 상한 측정, 시험 계정으로 BE 경로 확인.
3. AI #177 gosky 배포 뒤: 사용자 확인을 받고 `CHAT_STT_*` 주입 → 실호출(WAV·WebM·MP3, 빈 글자, 30초 초과, 동시 3건) 측정.
