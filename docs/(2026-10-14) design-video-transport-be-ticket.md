# 송출용 1회용 업로드 자격(upload ticket) 발급 설계 - 백엔드 (2026-10-14)

> 상태: **종결 - 현행 JPEG 유지(C) 확정, H.264 보류(2026-10-14).** 결과·판정: `(2026-10-14) result-video-transport-h264-poc.md`. 이 문서의 구조(AI가 BE 내부 API로 소비)는 BE가 WS를 중계하는 구조로 대체돼 구현됐으나 머지하지 않았다. 재개할 때만 참고한다.
> 근거: `(2026-10-13) design-video-transport-be.md` 3-1·3-2·3-3, `.claude/rules/domain-security-policy.md` "보호자 실시간 카메라 보기", 현행 시청 티켓(`CameraStreamTicketService`).
> 범위: **송출(피보호자 기기 -> AI)** 방향의 자격만. 시청 방향 전환(WebRTC·WS 시청)은 이 문서 밖이다.

## PHASE -1. 환경

| 항목 | 값 |
|---|---|
| 날짜 | 2026-10-14 (KST) |
| 브랜치 / HEAD | `dev` / `14a369e` (이 문서는 워크트리 `worktree-docs-video-transport-be-ticket`에서 작성) |
| 작업 트리 | `.claude/skills/work-prompt/SKILL.md` 수정 1건(이 작업과 무관, 건드리지 않음) |
| 마이그레이션 최신 | V60 (`reset_email_notification_setting`). 이 설계는 **마이그레이션 없음** |
| 빌드 | 코드 변경이 없어 실행하지 않음 |

## PHASE 0. 전제 vs 실제

| # | 전제 | 실제 (2026-10-14 코드 확인) | 설계에 주는 영향 |
|---|---|---|---|
| 1 | 송출 인증은 FE 프록시가 AI 키를 붙이고 BE는 관여하지 않는다 | **맞다.** BE에는 송출(세션 생성·프레임·종료) 관련 코드가 없다. FE `api/streams` 프록시가 3경로(`POST v1/stream-sessions`·`/{id}/frame`·`/{id}/stop`)만 남겨 AI 키를 붙여 넘긴다(`(2026-10-03) feature-ai-stream-relay.md` 범위 밖·수용한 한계). 즉 **송출 경로는 지금도 사용자 인증 없이 AI 키로만 열려 있다** | 새 자격은 "새 구멍을 막는 것"이 아니라 **ws-h264 경로를 처음부터 인증된 경로로 만드는 것**이다. jpeg 경로의 무인증은 이 설계로 해결되지 않는다(별도 결정 필요, 5절) |
| 2 | 시청 티켓을 재사용할 수 있다 | **부분적.** `CameraStreamTicketService`는 발급(`SET EX`)·소비(`GETDEL`)·형식 검사·세션 바인딩·`issuedAt` 반환 구조가 그대로 쓸 만하다. 단 접두사(`st_`)·키(`camera:stream:ticket:`)·값 의미가 시청 전용이고, 소비 호출자가 **BE 자신**(`relay()`)이다. 송출은 소비자가 **AI 서버**다 | 같은 구조의 **별도 서비스**를 둔다(키 공간 분리). 코드를 공유하려고 시청 서비스에 `purpose` 파라미터를 끼우지 않는다 - 시청 티켓으로 송출을 여는 오용 경로를 구조로 막는다 |
| 3 | `getViewableCamera`를 쓸 수 있다 | **쓸 수 없다.** 보호자 전용이고(ACTIVE 연결 판정) **`is_active=false` 카메라를 404로 거른다.** 송출은 피보호자 본인이고, `is_active`는 표시용이라 꺼진 카메라도 감지·알림은 계속된다(ANOM-G08) - 송출 자격을 `is_active`로 막으면 "끄면 감지가 멈춘다"는 정반대 정책이 생긴다 | 소유 검증은 `getOwnedCamera(wardId, cameraId)`(타인 403 `CAMERA_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`, `CameraService:413`)를 재사용하고 **`is_active`는 보지 않는다** |
| 4 | 재확인 로직을 재사용할 수 있다 | `CameraStreamService.isStillViewable`(계정 ACTIVE -> 카메라·연결 -> `isRevokedSince`)이 형태는 같다. 보호자 연결 항목만 송출에서는 "본인 카메라 존재"로 바뀐다. 무효화 비교는 `TokenInvalidation.isRevoked(issuedAtMillis, ...)` | 같은 순서(DB 실패는 끊음 / 무효화 키 Redis 실패만 이어 감)로 `isUploadStillValid`를 만든다 |
| 5 | 속도 제한 | `CameraStreamRateLimit` enum(사용자 ID 기준 분·시간 이중 윈도우, `RateLimitService`, Redis 장애 시 fail-open) | enum에 `UPLOAD_TICKET` 한 줄 추가 |
| 6 | WS 세션 닫기 수단 | `WebSocketSessionCloser`는 **우리 STOMP 세션 레지스트리**만 닫는다(userId 기준, 단일 인스턴스 메모리). AI 서버에 붙은 송출 연결은 BE가 갖고 있지 않다 | **재사용 불가**. 재사용하는 것은 규약뿐(close `1008`·고정 reason `AUTH_INVALIDATED`). 끊는 수단은 AI 쪽에 필요하다(3-4절) |
| 7 | `CameraResponse`·`WardLiveCameraView`에 필드를 더해도 되는가 | **된다(가산 변경).** 둘 다 record이고 JSON 필드만 늘어 FE가 모르는 필드는 무시한다. `WardLiveCameraView`는 `CameraResponse`를 받아 만드는 `of(...)`라 한 곳만 바꾸면 된다. 확인할 것: `CameraResponse.of(camera, fps)` 호출처 4곳(`CameraService` 87·105·135·157)과 테스트의 record 생성자 직접 호출 | `transport` 추가는 하위호환. 단 record 정식 생성자 호출을 하는 테스트는 컴파일 수정이 필요하다 |
| 8 | Redis 장애 시 인증 필터 | 일반 경로는 무효화 조회 실패 시 **503**, `POST /api/ward/sos`만 fail-open(`[AUTH-STORE-FAIL-OPEN]`, "다른 경로로 넓히지 말 것") | 발급 API는 **필터 단계에서 이미 fail-closed**다(3-5절 근거) |
| 9 | 카메라 등록 상한 | `camera.max-total=3`(2026-10-14). 등록 수가 송출 상한을 겸한다 | 송출 자격에 별도 동시 상한을 두지 않는다(등록 수 이하) |
| 10 | 이 계정이 정지되면? | **피보호자는 이용 제한 금지**(400 `WARD_CANNOT_BE_RESTRICTED`) | 송출을 끊는 계정 사유는 **탈퇴·강제 탈퇴·역할 변경·비밀번호 변경(토큰 무효화)**이다. "정지로 송출이 끊긴다"는 경로는 없다 |

## PHASE 1. 설계안

### 1. 한눈에

```
FE(피보호자)            BE                                   AI 서버
   | POST /api/ward/camera/{id}/upload-ticket  (Bearer, WARD)
   |------------------->| 인가·속도제한·플래그 -> ticket(ut_..., 60초·1회)
   |<-------------------| {ticket, expiresInSeconds, uploadUrl}
   |  WS 연결  uploadUrl?ticket=...  (브라우저 -> AI, access token 아님)
   |----------------------------------------------------->|
   |                    |<-- POST /api/internal/camera/upload-ticket/consume (X-API-Key)
   |                    |    {ticket, sessionId}  : GETDEL + 계정·카메라·무효화 재확인
   |                    |--> {wardId, sessionId, issuedAtMillis}  | 실패 401/403/404
   |  H.264 프레임 ...  |                                        | (수립)
   |                    |<-- POST .../upload-ticket/verify  60초마다 {sessionId, issuedAtMillis}
   |                    |--> 200 continue / 410 revoked   -> AI가 1008 AUTH_INVALIDATED로 닫음
```

### 2. 발급 API

`POST /api/ward/camera/{id}/upload-ticket` (`WardCameraController`, 클래스 `@PreAuthorize("hasRole('WARD')")`)

| 항목 | 규칙 |
|---|---|
| 주체 | 피보호자 본인만. 보호자·관리자는 403(클래스 게이트). **보호자·관리자용 송출 자격 API를 만들지 않는다** |
| 소유 검증 | `getOwnedCamera(wardId, cameraId)` - 타인 카메라 403 `CAMERA_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`(userId·cameraId만), 없는 카메라 404 `CAMERA_NOT_FOUND`. 응답에 소유자·방 이름 미포함 |
| `is_active` | 보지 않는다(3절 전제 3) |
| 사용자 ID | `@AuthenticationPrincipal`만. 요청 본문·쿼리 없음 |
| 바인딩 | 값 = `wardId:sessionId:issuedAtMillis`. 소비 때 sessionId가 다르면 거부 + `[IDOR-ATTEMPT]`(시청 티켓과 같은 처리) |
| 수명·소비 | 60초(`camera.upload.ticket-ttl`, 기본 60s), `GETDEL` 1회 |
| 키 공간 | 접두사 **`ut_`**, 형식 `^ut_[A-Za-z0-9_-]{43}$`, Redis 키 `camera:upload:ticket:`(`RedisKeys.CAMERA_UPLOAD_TICKET`). 시청 티켓(`st_`, `camera:stream:ticket:`)과 **형식·키 모두 서로 소비 불가** |
| 대상 플래그 | 서버 설정에서 ws-h264가 켜진 카메라만 발급(아니면 409 `CAMERA_UPLOAD_NOT_ENABLED`). jpeg 카메라에는 자격을 만들지 않아 ws 경로가 의도치 않게 열리지 않는다 |
| 응답 | `{ ticket, expiresInSeconds, uploadUrl }` - `uploadUrl`은 설정값(`camera.upload.ws-url`, 공개 도메인의 wss 경로). **AI 내부 주소·키는 싣지 않는다**(정책 규칙 ④) |
| 속도 제한 | `CameraStreamRateLimit.UPLOAD_TICKET("camera-upload-ticket", 20, 300)`, 식별자 = **피보호자 ID**, 인가보다 먼저. 근거: 정상 사용은 송출 시작·네트워크 끊김 재연결뿐이라 분 20회면 넉넉하고, 재연결 폭주는 그 위에서 막힌다. 초과 429 + `Retry-After` |
| 캐시 | 응답 `Cache-Control: no-store` |

### 3. 소비·재확인·종료

#### 3-1. 소비 주체 - 결정 요청 D1

송출 연결은 브라우저와 AI 사이에 맺히므로 **AI가 자격이 유효한지 확인할 방법**이 필요하다.

| 안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| **A (추천)** | AI가 BE 내부 API로 소비·재확인 | 1회용·즉시 폐기·계정 상태 재확인이 현행 시청 티켓과 **같은 규칙** 그대로. BE가 단일 진실원 | AI -> BE 호출 의존(연결 수립 시 1회 + 60초). BE 장애 시 새 송출 수립 불가 |
| B | BE가 서명 토큰(HMAC/JWT) 발급, AI가 공유 비밀로 로컬 검증 | AI가 BE 없이 수립 가능 | 1회용을 AI가 직접 지켜야 함(jti 저장·재생 방지). 폐기·계정 재확인이 약해 "정지·삭제 즉시 반영"이 어긋남. 공유 비밀 관리 추가 |
| C | BE가 WS를 중계(BE <-> AI) | 키·자격이 BE 안에서만 존재, 종료도 BE 소관 | 프레임 단위 중계 스레드·CPU(시청 중계와 같은 부담을 송출에도), 단일 인스턴스 메모리 한계 |

추천 A. 구현 요건:
- AI가 BE를 부르는 **새 인바운드 경로** `POST /api/internal/camera/upload-ticket/consume`·`.../verify`. 인증은 `X-API-Key`(`AI_API_KEY`와 같은 값, **상수 시간 비교**), 응답·로그에 키를 남기지 않는다. `permitAll` + 키 검증이라 정책의 "permitAll은 영상 `<img>` 경로 하나" 문장이 바뀐다(4부 갱신 목록). Swagger에는 `@Hidden`.
- 실패 응답은 구분을 줄인다: 티켓 없음·만료·이미 사용·세션 불일치는 모두 401 `CAMERA_UPLOAD_TICKET_INVALID`(시청 티켓과 동일), 계정 상태 불가 403, 카메라 삭제 404.
- 키 오류 반복은 IP 기준 속도 제한(`ClientIpResolver`, `getRemoteAddr()` 금지). 네트워크 수준(nginx에서 내부 경로를 AI 서버 IP로만 허용)은 인프라 몫이라 별도 요청.
- 소비 시 확인 순서: 형식 -> `GETDEL` -> 세션 바인딩 -> 계정 `ACTIVE`·role `WARD` -> 카메라 존재·소유(`is_active` 무시) -> 플래그 -> 발급 뒤 무효화(`isRevokedSince`). 응답 `{wardId, sessionId, issuedAtMillis}`.

#### 3-2. 수립 후 재확인 (60초)

- AI가 연결마다 `revalidateInterval`(현행 60초, `camera.stream.revalidate-interval`을 공유하지 않고 `camera.upload.revalidate-interval` 별도, 기본 60s) 주기로 `verify {sessionId, issuedAtMillis}`를 호출한다. BE는 `isUploadStillValid`(계정 ACTIVE·WARD -> 카메라 존재 -> 플래그 -> 무효화 없음)로 200/410을 준다. **연결 상태를 BE가 저장하지 않는다**(단일 인스턴스 메모리 의존 회피).
- DB 조회 실패 -> 410(확인할 수 없으면 이어 주지 않는다는 시청 규칙과 같음). 무효화 키 Redis 조회만 실패 -> 200(계정·카메라 확인은 통과, Redis 순단으로 전 송출이 끊기지 않게).
- **최대 수명은 두지 않는다**(결정 요청 D4): 송출은 사람이 보는 영상이 아니라 24시간 감시 입력이라 30분마다 끊으면 그 사이 감지 공백이 생긴다. 대신 AI 쪽 무수신 제한(프레임이 일정 시간 없으면 종료)이 유령 연결을 정리한다.

#### 3-3. 정지·삭제 시 연결 종료 수단 - AI 요청 사항

| 방식 | 반응 시간 | AI 작업 | 비고 |
|---|---|---|---|
| **60초 `verify` 폴링 (필수)** | 최대 60초 | `verify` 호출 + 410이면 `1008 AUTH_INVALIDATED`로 닫기 | BE는 상태 없음. 이것만으로 요구 충족 |
| BE가 AI에 종료 요청(선택, 후속) | 즉시 | `DELETE /api/v1/live-streams/{sessionId}/uploader` 같은 종료 API | 카메라 삭제·탈퇴·역할 변경 이벤트(`CameraDeletedEvent`·`UserWithdrawnEvent`·`UserRoleChangedEvent`)의 AFTER_COMMIT 리스너가 호출, best-effort. AI 종료 API가 없으면 만들지 않는다 |

**AI에 종료 요청 API가 필요한가?** 최소 요구로는 **불필요**(폴링으로 60초 안에 끊김, 시청 중 재확인과 같은 허용 지연). 즉시성을 원하면 선택 항목으로 추가한다. AI에 요구하는 필수 항목은 (a) 수립 시 `consume` 호출, (b) 60초 `verify`, (c) 410 시 close `1008`+reason `AUTH_INVALIDATED`, (d) 같은 sessionId의 새 연결은 기존 연결을 대체(탭 2개 동시 송출 방지 - AI 몫), (e) 로그에 티켓 원문·프레임 미기록이다.

연결이 끊긴 FE는 재연결 시 `upload-ticket`을 다시 받는다. 정지·탈퇴·무효화된 계정은 발급 단계에서 막힌다(일반 인증 필터).

### 4. Redis 장애 정책 - 결정 요청 D2

| 상황 | 정책 | 이유 |
|---|---|---|
| 발급 API | **fail-closed**(503) - 별도 코드 불필요 | 인증 필터가 무효화 조회 실패를 이미 503으로 막는다. 발급을 fail-open으로 하려면 `POST /api/ward/sos`처럼 필터의 fail-open 목록에 경로를 넣어야 하는데 정책이 "다른 경로로 넓히지 말 것"이라 못 박았다. 또 티켓은 Redis에 저장하므로 Redis 없이 발급 자체가 불가능하다 |
| 티켓 저장소 오류 | 503 `SERVICE_UNAVAILABLE`(시청 티켓 `storeUnavailable`과 동일, 로그는 클래스명만) | |
| 송출 중인 연결 | **이어 간다** | `verify`의 무효화 키 조회 실패는 200(3-2). 발급·소비만 Redis를 요구한다 |
| 속도 제한 | fail-open(`RateLimitService` 기존) | 보조 방어 |
| 대응책 | FE가 503이면 **jpeg 경로로 폴백**(`transport` 기본값 jpeg 경로는 Redis·티켓과 무관) | Redis 장애가 송출 전체 중단으로 번지지 않게 한다. FE 협의 필요 |

### 5. `transport` 필드와 서버 플래그 - 결정 요청 D3

- `CameraResponse`·`WardLiveCameraView`에 **`transport`**("jpeg" | "ws-h264", 기본 "jpeg") 추가. 의미는 **송출 방식**이다(시청 전송 방식과 혼동되므로 이름을 `uploadTransport`로 하는 안도 있음 - 지시문 이름 `transport`를 따르되 Swagger 설명에 "송출"을 명시).
- 플래그: `camera.upload.ws-h264-session-ids`(env `CAMERA_WS_H264_SESSION_IDS`, 쉼표 목록, **기본 빈 값**). 목록에 든 sessionId만 `transport=ws-h264`와 발급 자격이 열린다. 카메라 1대만 켜는 시험이 가능하고 코드 변경 없이 env 한 줄로 되돌린다(`up -d`).
- 킬 스위치 `camera.upload.ticket-enabled`(기본 true): false면 발급·소비 모두 503 -> FE는 jpeg 폴백. 이미 수립된 연결은 `verify`가 계속 200(킬 스위치는 **새 수립만** 멈춘다 - 클립 킬 스위치와 같은 판단. 즉시 끊는 스위치가 필요하면 별도 결정).
- jpeg 경로의 **무인증(FE 프록시)은 이 설계로 해결되지 않는다.** 해결하려면 jpeg 송출도 같은 자격을 쓰는 별도 작업이 필요하다 -> 후속 결정 D5.

### 6. 로그·테스트

- 로그: `sessionId`·결과 코드만. 티켓·프레임·AI 키·세션 외 식별자 금지. `[IDOR-ATTEMPT]`는 userId·cameraId/sessionId만. 새 파일(`CameraUploadTicketService`, 내부 컨트롤러, 서비스)을 `LogRawExceptionGuardTest` 대상에 추가한다.
- 테스트 계획: 발급(소유·타인 403+IDOR 로그·is_active 꺼진 카메라 발급 가능·플래그 꺼짐 409·보호자·관리자 403·속도 제한) / 소비(1회성·60초 만료·세션 불일치·형식 오류·**`st_` 티켓으로 소비 불가(시청 티켓과 상호 비소비)**·계정 상태·무효화) / `verify`(DB 실패 410·Redis 무효화 실패 200) / 내부 경로 키 검증(없음·틀림·상수 시간) / 응답 `transport` 직렬화(HTTP 직렬화 포함, Jackson 3 규칙) / Redis 통합 테스트(`GETDEL` 원자성은 vkcs에서).
- 구현 모델은 권한·토큰이므로 Opus.

## 결정 요청

| # | 질문 | 추천 |
|---|---|---|
| D1 | 송출 자격 소비 방식 | **A**: AI가 BE 내부 API로 소비·재확인(B 서명 토큰·C BE 중계 기각) |
| D2 | Redis 장애 시 | 발급·소비 **fail-closed**, 송출 중 연결은 이어 감, FE는 jpeg 폴백 |
| D3 | `transport` 이름·플래그 | `transport`(+Swagger에 "송출 방식" 명시) / sessionId 목록 플래그, 기본 빈 값 |
| D4 | 송출 연결 최대 수명 | **두지 않음**(AI 무수신 제한으로 정리, 감시 공백 방지) |
| D5 | jpeg 송출의 무인증(FE 프록시) | 이 작업 범위 밖으로 두되 **위험으로 기록**. 해결은 jpeg 송출에도 같은 자격을 쓰는 후속 설계 |
| D6 | `uploadUrl`을 BE가 내려도 되는가 | 공개 도메인의 wss 경로만 내려준다(정책 규칙 ④의 "AI 주소"는 내부 주소·키 의미로 해석, 명문화 필요) |

## 4부. 정책 문서 갱신 항목 (코드 완료 뒤 한 번에 반영, 지금은 수정하지 않음)

**`.claude/rules/domain-security-policy.md` "보호자 실시간 카메라 보기"**
1. 경위·범위: "송출 업로드는 범위 밖(FE 프록시)" -> ws-h264 송출은 업로드 자격으로 인증됨, jpeg는 프록시 유지(D5 위험 명시)
2. **신규 규칙 - 송출 자격**: 피보호자 본인 카메라만·`is_active` 무시·`ut_`/키 공간 분리·60초 1회·소비 시 재확인·보호자/관리자 발급 불가·시청 티켓으로 송출 불가
3. 규칙 ②: "permitAll은 mjpeg 하나" -> 내부 소비·검증 경로 2개(키 인증) 추가 서술, `access token을 쿼리로 받지 말 것`은 유지
4. 규칙 ③: 송출은 **최대 수명 없음 + 60초 재확인**임을 시청 30분 상한과 구분해 기록
5. 규칙 ④: AI 키는 방향이 양쪽이 됨(BE -> AI 기존, AI -> BE 내부 경로) - 상수 시간 비교·로그 금지
6. 규칙 ⑥: ws 송출은 close `1008 AUTH_INVALIDATED`(MJPEG 닫기 규칙과 별개)
7. "수용한 한계": jpeg 송출 무인증 / 재확인 최대 60초 지연 / 연결 상태를 BE가 모름(AI 종료 API 없을 때)
8. Redis 장애: 발급 fail-closed·송출 중 유지(필터 fail-open 목록은 SOS 하나 그대로)

**`CLAUDE.md`**
1. §1 카메라 항목에 한 줄: "송출 자격(ws-h264): `POST /api/ward/camera/{id}/upload-ticket`, 소비는 AI -> BE 내부 API" + 플래그명
2. §8 "인증 저장소(Redis) 장애" 줄에 "발급은 fail-closed, 송출 중 유지" 한 구절
3. 최종 업데이트 줄 추가

**기타**: Swagger 설명(`CameraResponse` 예시 fps "5"·`transport`) · `application.yaml` `camera.upload.*` 주석 · `(2026-10-13) design-video-transport-be.md` 3-1 "송출 자격" 행에 이 문서 링크 · `docs/progress.md`

## 구현 PHASE 개요 (승인 후, [전환-AI] 설계 확정 뒤)

1. 설정·키·에러 코드(`CAMERA_UPLOAD_TICKET_INVALID`·`CAMERA_UPLOAD_NOT_ENABLED`)·속도 제한 enum
2. `CameraUploadTicketService`(발급·소비) + `CameraService.getUploadableCamera/isUploadable` + 발급 컨트롤러 + `transport` 필드
3. 내부 컨트롤러(`consume`·`verify`) + 키 검증 + SecurityConfig 경로
4. 테스트·로그 가드·문서 갱신(4부) -> 기능 점검(B)·`security-audit`(자격·인가를 건드리므로)
