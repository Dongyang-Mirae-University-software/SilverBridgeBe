# 관리자 AI 통계 - 챗 집계 중계 기능 점검 (2026-10-28)

- 대상: BE PR #397 `GET /api/admin/ai-stats/chat` (머지 `eae3bec`, vkcs CD·gosky 수동 배포 13:52~13:53)
- 템플릿: 기능 점검(B). 점검만 했고 코드는 고치지 않았다.
- 방법: 독립 검토(결론 없이 코드만 넘김) + 핵심 주장 직접 재현(JDK 헤더 예외 메시지, `daily` 처리 코드) + gosky 실측(공개 도메인 401, Swagger, 서버 로그, 속도 제한 31회 호출, 두 서버 키 줄 CR 개수 - 값 출력 없음)

## 점검 범위

| 파일 | 역할 |
|---|---|
| `admin/controller/AdminAiStatsController.java` | 경로 1개, Swagger |
| `admin/service/AdminAiChatStatsService.java` | 기간 검사·속도 제한·캐시·응답 형식 검사·오류 바꾸기 |
| `admin/dto/AdminAiChatStatsResponse.java` | 응답 record(허용 목록) |
| `chat/client/AiChatClient.java` `stats` + 공용 `call` | AI 호출 |
| `global/exception/ErrorCode.java`·`GlobalExceptionHandler.java`·`global/security/SecurityConfig.java`·`RateLimitService.java` | 공용 경로 |

## 엔드포인트 × 인가

| 경로 | 인가 | 결과 |
|---|---|---|
| `GET /api/admin/ai-stats/chat` | `SecurityConfig` `/api/admin/**` → ADMIN + 클래스 `@PreAuthorize("hasRole('ADMIN')")` | PASS - 보호자 403·비로그인 401(gosky 내부·공개 도메인 `api.devdmu.gosky.kr`), vkcs 비로그인 401 |

IDOR: 해당 없음(사용자별 자원 아님). 캐시는 기간 단위로 관리자 사이에 공유되지만 담긴 것이 사용자 ID 없는 집계 숫자라 노출이 아니다 - PASS. 서버 로그 `[ADMIN-AI-STATS]`는 adminId·기간·캐시 여부·ms만 - PASS.

## 실측 (gosky)

- Swagger: 태그 "관리자 - AI 통계", 파라미터 `from`·`to`, 응답 200·400·401·403·429·503·504
- 속도 제한: 같은 기간 31회 호출 → 30회 200, 31번째 429 `TOO_MANY_REQUESTS`. 그중 AI 호출은 1회(`cached=false` 1건 + `cached=true` 29건)
- 두 서버 `.env.dev`의 `AI_API_KEY` 줄 CR 문자 0개(L-1이 지금 터지지 않음을 확인)

## 이슈

### 🟡 M-1 `daily`의 날짜 값을 검사하지 않는다
- 위치: `AdminAiChatStatsService.toResponse` - `daily` 줄 수(= 기간 일수)와 `totalChats` 형식만 보고 `date`는 문자열인지만 확인한다.
- 실패 시나리오: AI가 날짜를 UTC로 묶는 회귀를 내서 10/01~10/31 요청에 `2026-09-30`~`2026-10-30` 31줄을 주거나 같은 날짜를 2줄 주면 그대로 200으로 나간다. 관리자 그래프가 하루씩 밀린 채 정상 집계처럼 보인다 - "형식이 어긋나면 0이나 그럴듯한 값이 아니라 503" 원칙의 구멍.
- 수정안: `daily[i].date == from + i`(문자열 비교) 하나로 범위·순서·중복을 함께 막는다. 어긋나면 503.

### 🟢 L-1 AI 키에 줄바꿈이 섞이면 키 원문이 로그에 남는다 (공용 `AiChatClient.call`, 이번 PR 이전부터)
- 위치: `AiChatClient.call`의 `setRequestProperty("X-API-Key", …)` + `GlobalExceptionHandler.handleIllegalArgument`(`log.warn("IllegalArgumentException: {}", e.getMessage())`).
- 재현: JDK `HttpURLConnection.setRequestProperty`는 값에 CR/LF가 있으면 `IllegalArgumentException("Illegal character(s) in message header value: <값>")`을 던진다(메시지에 값 원문 포함 - 직접 확인). 이 예외는 `IOException`이 아니라 `call`의 catch를 빠져나가 공용 핸들러가 메시지째 WARN으로 찍고 400 `INVALID_INPUT`으로 답한다.
- 발동 조건: `.env.dev`를 CRLF로 저장하는 등 설정 오류일 때만. 지금 두 서버는 CR 0개라 발동하지 않는다. 다만 챗 전송·기록 조회·탈퇴 삭제·이번 집계가 모두 같은 `call`을 쓴다.
- 수정안: `call`에서 키를 붙이기 전에 헤더 값 검사(제어 문자 있으면 키 미설정과 같은 503 + 고정 WARN), 또는 `call` 안의 `IllegalArgumentException`·`RuntimeException`을 클래스명만 남기고 `CHAT_UNAVAILABLE`로 바꾼다. 공용 핸들러의 `e.getMessage()` 로깅도 `LogRawExceptionGuardTest` 원칙(예외 원문 금지)과 어긋나 함께 보는 것을 권한다(영향 범위가 넓어 별도 판단).

### 🟢 L-2 `CustomException`이 아닌 예외는 엉뚱한 상태로 나간다 (설정·종료 상황)
- `AI_HTTP_BASE_URL`이 깨진 주소 → `IllegalArgumentException` → 400 `INVALID_INPUT`(503이어야 함) / http(s)가 아닌 스킴 → `ClassCastException` → 500 / 종료 중 마감 스레드 `RejectedExecutionException` → 500.
- 서비스는 `CustomException`만 잡는다. 수정안: L-1과 같이 `call`에서 런타임 예외를 `CHAT_UNAVAILABLE`로 감싸면 함께 해결된다.

### 🟢 L-3 `generatedAt` 오프셋을 확인하지 않는다
- `OffsetDateTime.parse`가 `Z` 등 +09:00이 아닌 오프셋도 받는다. Swagger·DTO는 "KST +09:00"이라고 적었다. 수정안: 오프셋이 +09:00인지 확인하거나 KST로 바꿔 내린다.

### 🟢 L-4 호출 제한 15초·동시 상한 없음
- 기록 조회용 `logs-call-timeout`(15초)을 쓴다. 숫자 집계라 일반 AI 조회(8초)보다 길다. 실패는 캐시하지 않아 AI가 멈추면 관리자 한 명이 분 30회로 요청 스레드를 몇 개 계속 붙들 수 있다(관리자 수가 적어 영향 작음). 같은 기간 동시 미스는 모두 AI를 부른다(속도 제한이 상한). 수정안: 집계 전용 8초 제한, 필요하면 같은 기간 진행 중 호출 공유.

### 🟢 L-5 기간 오류 안내가 실제 조건보다 좁다
- `AI_STATS_INVALID_RANGE` 문구·Swagger `[기간]`은 형식·31일만 말하고 "시작일 ≤ 종료일"·"2000~2999년"을 적지 않았다(Notion 전달 문서에는 있음).

### 🟢 L-6 테스트 빈틈
- `daily` 날짜 값·순서·중복, `generatedAt` 오프셋, 클라이언트 `stats`의 시간 초과(504 경로)·64KB 상한, `CustomException`이 아닌 예외의 503 처리를 시험하지 않는다.

### 참고 (이슈 아님)
- 속도 제한은 Redis 장애 시 fail-open(기존 `RateLimitService` 정책)이라 그동안 AI 보호는 60초 캐시뿐이다 - "보조 방어" 정책과 같아 수용.
- 캐시 적중도 속도 제한 횟수에 센다. 오늘 숫자는 최대 60초 늦다(문서화됨).
- 권한 테스트는 메서드 보안만 본다(비로그인 401은 경로 규칙 담당) - 형제 컨트롤러와 같은 관행.

## 종합 판정

**조건부 PASS** - 🔴 0 · 🟠 0 · 🟡 1 · 🟢 6. 인가·원문 미노출·null 유지·기간 검사·속도 제한·캐시는 실측까지 맞다. M-1은 AI 쪽 회귀가 있어야 드러나지만 "그럴듯한 틀린 값"을 내보내는 종류라 고치는 것을 권한다. L-1은 지금 발동하지 않지만 키 노출 경로라 L-2와 함께 공용 `call`에서 막는 것을 권한다.

## 수정용 커밋 메시지 초안

```
fix: 관리자 AI 챗 집계 점검 반영 - daily 날짜 검사·AI 호출 런타임 예외 감싸기·KST 오프셋·전용 제한 시간

- daily[i].date가 from + i 와 다르면 응답 형식 이상(503) - 범위·순서·중복을 함께 막는다
- AiChatClient.call: 헤더 값 제어 문자·잘못된 주소 등 런타임 예외를 클래스명만 남기고 CHAT_UNAVAILABLE로
  (키 원문이 공용 핸들러 로그에 남던 경로 차단, 400/500 대신 503)
- generatedAt 오프셋 +09:00 확인, 집계 호출 제한 8초
- AI_STATS_INVALID_RANGE 문구·Swagger에 시작일 ≤ 종료일·2000~2999년 명시, 빈틈 테스트 추가
```

## 후속 반영 (2026-10-28, 사용자 "전부 반영", branch `fix/admin-ai-chat-stats-audit`)

| 이슈 | 반영 |
|---|---|
| 🟡 M-1 | `daily[i].date`가 `from + i`일과 다르면 503 - 범위 밖·순서·중복·형식을 한 번에 막는다 |
| 🟢 L-1 | `AiChatClient.call`이 `CustomException`이 아닌 런타임 예외를 클래스명만 남기고 `CHAT_UNAVAILABLE`로 감싼다 - 헤더 값 오류의 키 원문이 공용 핸들러 로그로 가지 않는다. `requireApiKey`가 키의 제어 문자를 먼저 걸러 고정 문구 WARN("CRLF 확인")을 남긴다(값 미기록) |
| 🟢 L-2 | 같은 감싸기로 잘못된 주소(상대 주소·http 아닌 스킴)·종료 중 예약 거절도 400/500이 아니라 503 |
| 🟢 L-3 | `generatedAt` 오프셋이 +09:00이 아니면 503 |
| 🟢 L-4 | 집계 전용 호출 제한 `chat.relay.stats-call-timeout`(기본 8초, `CHAT_RELAY_STATS_CALL_TIMEOUT`). 같은 기간 진행 중 호출 공유는 넣지 않았다(속도 제한이 상한, 점검 문서의 "필요하면") |
| 🟢 L-5 | `AI_STATS_INVALID_RANGE` 문구·Swagger에 2000~2999년·from ≤ to 명시, 504 설명에 8초 |
| 🟢 L-6 | 서비스: 날짜 하루 밀림·중복·순서·형식·UTC 오프셋 5가지 503 / 클라이언트: 집계 시간 초과·64KB 상한·키 제어 문자(예외·로그에 키 없음)·잘못된 주소 2가지 |

- 검증: 관리자·chat 테스트 357건, 변이 4종 검출(날짜 검사·오프셋 검사·런타임 예외 감싸기·전용 제한), `./gradlew build` 2075건 통과(건너뜀 1, 기존).
- ~~남긴 것~~ → **해소 (2026-10-28, branch `fix/global-handler-raw-log`)**: 공용 `GlobalExceptionHandler`의 원문 로그 10곳을 이름·클래스명·발생 위치로 바꾸고 `LogRawExceptionGuardTest` 목록에 넣었다(응답 무변경). IAE의 하위인 `NumberFormatException`이 사용자 입력을 남기던 것과 Content-Type 원문 로그(가짜 줄 삽입)도 함께 막혔다.
- 배포·실측(2026-10-28 14:18, PR #398 `58e636c`): 두 서버 반영, gosky에서 실제 AI 응답이 새 검사를 통과(오늘·10월 200)하고 챗 중계(피보호자·보호자) 200. 탈퇴 2건으로 집계가 줄어든 것을 확인해 "탈퇴한 회원의 상담은 빠진다"를 기능 문서·Notion 전달 문서에 적었다.

