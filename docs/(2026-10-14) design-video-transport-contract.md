# 영상 전송 H.264 PoC - 통합 계약 (2026-10-14)

> 상태: **GO 승인됨(2026-10-14). AI·BE 구현 완료(플래그 꺼짐), FE·시험·측정 대기. `.env`·배포 변경 없음**
> 범위: 송출(피보호자 기기 -> AI)만 H.264로 바꾼다. 시청(BE의 MJPEG 중계)은 변경 없음. **안 B-lite**.
> 근거: BE `(2026-10-13) design-video-transport-be.md`·`(2026-10-14) design-video-transport-be-ticket.md`, FE `(2026-10-14) design-video-transport-fe.md`(develop), AI `(2026-10-14) design-video-transport-ai.md`(브랜치 `docs/video-transport-ai-design`).
> 이 문서가 세 저장소 문서와 어긋나면 **이 문서가 우선**한다(4절 정정 표).

## 1. 아키텍처

```
FE(피보호자) --wss, 1회용 업로드 티켓--> BE /ws/camera-upload --내부 WS + X-API-Key--> AI /api/v1/stream-sessions/{id}/ingest-ws
                                                                               -> ffmpeg 디코드 -> JPEG -> 기존 분석·방송·클립 파이프라인
시청: AI -> BE(MJPEG 중계, 변경 없음) -> FE <img>
```

- **AI는 브라우저에 노출하지 않는다.** AI 키는 BE -> AI 내부 헤더(`X-API-Key`)로만 쓴다.
- 기존 JPEG 송출(`POST /api/v1/stream-sessions/{id}/frame`, FE 프록시 경유)은 **무변경**이며 폴백 경로다.

## 2. 계약

### ① 경로 P1
브라우저 -> BE -> AI. FE 설계 문서 4절 P1과 같다. P2(브라우저 -> AI 직결)·P3(Next 중계)는 채택하지 않는다.

### ② 업로드 티켓 (BE)
`POST /api/ward/camera/{id}/upload-ticket`

| 항목 | 규칙 |
|---|---|
| 주체 | 피보호자 본인(`hasRole('WARD')`). 보호자·관리자용 발급 API를 만들지 않는다 |
| 소유 | `getOwnedCamera(wardId, cameraId)`. 타인 403 `CAMERA_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`, 없음 404. **`is_active`는 보지 않는다**(꺼진 카메라도 감지는 계속, ANOM-G08) |
| 티켓 | 접두사 `ut_`, 형식 `^ut_[A-Za-z0-9_-]{43}$`, Redis 키 `camera:upload:ticket:`. 값 = `wardId:sessionId:issuedAtMillis`. 60초·1회(`GETDEL`)·sessionId 바인딩. 시청 티켓(`st_`)과 형식·키 모두 서로 소비 불가(테스트로 고정) |
| 플래그 | sessionId가 `camera.upload.ws-h264-session-ids`에 든 카메라만 발급. 아니면 409 `CAMERA_UPLOAD_NOT_ENABLED` |
| 킬 스위치 | `camera.upload.ticket-enabled`(기본 true). false면 발급·핸드셰이크 모두 503. **새 수립만** 막고 수립된 연결은 이어 간다 |
| 속도 제한 | `CameraStreamRateLimit.UPLOAD_TICKET("camera-upload-ticket", 20, 300)`, 식별자 = 피보호자 ID, 인가보다 먼저, 초과 429 + `Retry-After` |
| 응답 | `{ ticket, expiresInSeconds, uploadUrl }`. `uploadUrl`은 설정값(`camera.upload.ws-url`)인 **공개 wss 경로만**. AI 주소·키 미포함. `Cache-Control: no-store` |

### ③ WebSocket (BE)
`wss://api.devdmu.gosky.kr/ws/camera-upload?ticket=ut_...`

- `/ws/**`는 이미 `permitAll`이고 nginx도 같은 `location /ws` 아래에 있을 가능성이 높다(**[추정]**, PHASE 5에서 확인). 새 SecurityConfig 매처를 추가하지 않는다.
- 핸드셰이크 인터셉터 순서: 형식 -> **Origin**(있으면 `APP_CORS_ORIGINS`에 있어야 함, 없으면 403) -> `GETDEL` 소비 -> sessionId 바인딩(불일치 시 `[IDOR-ATTEMPT]`) -> 계정 `ACTIVE`·role `WARD` -> 카메라 소유(`is_active` 무시) -> 플래그 -> 발급 뒤 무효화(`isRevokedSince`). 모든 실패는 HTTP 401/403/404/503로 거절(WS 수립 전).
- 기존 STOMP `/ws`의 JWT 핸드셰이크 인터셉터와 `StompSubscriptionAuthorizationInterceptor`를 **공유하지 않는다**. 이 경로는 STOMP가 아니다.
- sessionId는 **티켓에서만** 얻는다. URL·메시지로 받지 않는다.
- 티켓은 쿼리로 오지만 60초·1회·소비 시점에 폐기되므로 로그에 남아도 재사용할 수 없다(시청 티켓과 같은 수용). **access token은 쿼리로 받지 않는다**.

### ④ 메시지
| 방향 | 형식 | 내용 |
|---|---|---|
| 클라이언트 -> 서버, 첫 메시지 | 텍스트 | `{type:'config', codec, width, height, framerate, format:'annexb'}`. 해상도·회전 변경 시 새 `config` 후 키프레임 |
| 클라이언트 -> 서버 | 바이너리 | `[8B 타임스탬프(us)][1B 플래그(bit0=키프레임)][H.264 AnnexB]` |
| 서버 -> 클라이언트 | 텍스트 | `{type:'keyframe_request'}` (디코더 복구용) |

- **BE는 바이트를 해석하지 않고 중계한다**(텍스트·바이너리 구분만). 한도: 메시지 최대 256KB, 초당 메시지 `recommendedFps x 2`. 초과는 close `1009`(크기)·`1008`(속도)이며 해당 연결만 끊는다.
- **AI는 도착 시각을 수신 시각으로 쓴다.** 타임스탬프는 참고용(지터 진단)이다.
- AI 한도(256KB, `recommendedFps x 1.5` 토큰 버킷 드롭, 디코드 연속 실패 30회·키프레임 5초 없음이면 종료)는 BE 한도보다 **안쪽**이다. BE는 방어선, AI는 품질선이다.

### ⑤ 수명
- **최대 수명 없음**(24시간 감시 입력이라 끊으면 감지 공백). AI 설계의 `maxSec`(30분)은 쓰지 않는다.
- BE가 60초마다 재확인한다(계정·카메라·플래그·무효화). **DB 실패는 끊고**, 무효화 키(Redis) 조회 실패만 이어 간다.
- 무수신 15초면 BE가 종료(AI 유휴 제한과 같은 값).
- 종료는 close `1008`, reason 고정 `AUTH_INVALIDATED`. FE는 재연결하지 않고 티켓을 한 번만 다시 받아 시도한다. 실패하면 JPEG 폴백.
- 한 연결의 종료는 반대편을 닫는다(BE가 AI 연결을 정리 -> AI가 ffmpeg 프로세스를 kill).

### ⑥ `transport`
- `CameraResponse`·`WardLiveCameraView`에 가산: `"jpeg"`(기본) | `"ws-h264"`. 의미는 **송출 방식**(Swagger에 명시).
- 서버 플래그 = `camera.upload.ws-h264-session-ids`(env `CAMERA_WS_H264_SESSION_IDS`, 쉼표 목록, 기본 빈 값). 목록 변경 = `up -d`, 코드 변경 없음.
- FE 선택 규칙: `server.transport == 'ws-h264'` AND `VideoEncoder` 지원 AND `isConfigSupported` AND 티켓 발급 성공 -> h264. 그 밖은 전부 jpeg(현행 코드 무변경). PoC 플래그가 꺼진 FE는 어떤 서버 값에도 jpeg.

### ⑦ 폴백
티켓·WS 실패, 인코더 오류, 비정상 close 2회, 서버 `transport=jpeg` -> **같은 세션을 JPEG로 되돌린다. 세션 내 재승격은 하지 않는다**(다음 송출 시작에서만 다시 판정).

### ⑧ Redis 장애
- 발급·핸드셰이크 소비: **fail-closed 503**(필터가 무효화 조회 실패를 이미 503으로 막는다. fail-open 목록은 `POST /api/ward/sos` 하나 그대로).
- 수립된 연결: 이어 간다(재확인의 무효화 조회 실패는 통과).
- FE: 503이면 JPEG 폴백.

### ⑨ 연결 상한·역압(back-pressure) - 이 문서에서 새로 정한 것
| 항목 | 값 | 이유 |
|---|---|---|
| 같은 sessionId 연결 | 1개. 새 연결이 오면 **기존을 닫고 교체**(close `1013`) | 탭 2개 동시 송출 방지. AI의 "세션당 1연결"과 같은 규칙 |
| 전체 동시 연결 | 5 (카메라 등록 상한 3 + 여유) | AI 동시 5와 동일 |
| BE -> AI 전송 버퍼 | 상한 초과 시 연결 종료(close `1011`, 폴백 유도). 프레임을 쌓지 않는다 | 지연 누적이 감지 지연을 만든다 |
| 스레드 | 연결당 요청 스레드를 점유하지 않는다(비동기 전송) | MJPEG 중계와 달리 SOS·로그인까지 멈추지 않게 |
| 로그 | `sessionId`·집계 수치·종료 코드만. 티켓·프레임·AI 키 원문 금지 | `LogRawExceptionGuardTest` 대상 추가 |

## 3. 합격 기준 (통합본)

| 기준 | 값 |
|---|---|
| 전송량 | JPEG 경로의 50% 이하 |
| 감지 일치 | `detectedType`·`danger` 시퀀스 95% 이상 일치, **위험 구간 누락 0** |
| 화재 유지 조건 전환 | 같은 시점 +-1초 |
| 최대 프레임 간격 | 1초 미만(10분, 네트워크 중단 제외) |
| AI CPU | 카메라당 +10%p(코어 1개 기준) 이내 |
| BE CPU | 카메라당 +5%p 이내 |
| 변환 지연 | WS 수신 -> `set_frame` p99 100ms 이하 |
| 안정성 | 30분 연속, ffmpeg 프로세스 누수 0, 재연결 5회 후 클립 정상 |
| 폴백 | 강제 시 기존 동작과 동일 |

시험 영상은 **가상 장면만**(실제 가정 영상 금지). 15fps 시험 기준, JPEG -> H.264 -> JPEG 3구간.

## 4. 정정 표 (세 저장소 문서와의 차이)

| 문서 | 위치 | 기존 | 정정 |
|---|---|---|---|
| BE `design-video-transport-be.md` | 전제 1 | 송출 무인증 | **로그인·WARD 확인됨, sessionId 소유는 미확인**. 새 ws-h264 경로는 티켓으로 소유까지 확인. jpeg 송출의 소유 미확인은 **별도 후속**(D5) |
| BE `design-video-transport-be-ticket.md` | D1·3-1·3-2·3-3 | AI가 BE 내부 API(`consume`·`verify`)로 소비·재확인, 브라우저가 AI로 직결 | **BE가 WS를 중계**(결정안 C 변형). `consume`·`verify` 내부 API를 만들지 않는다. 재확인은 BE 안에서 60초 |
| 〃 | 4부 항목 3 | permitAll에 내부 소비·검증 경로 2개 추가 | **추가 없음**. `/ws/**`는 이미 permitAll이고 티켓 인증은 핸드셰이크 안. 정책 문서는 "permitAll 경로 = mjpeg 하나 + `/ws/**`(인터셉터 인증)"으로 서술 |
| 〃 | 4부 항목 5 | AI -> BE 내부 키 경로(양방향 키) | **BE -> AI 한 방향만**. 상수 시간 비교·로그 금지는 AI 쪽 책임 |
| AI `design-video-transport-ai.md` | 2-2 ① | BE 서명(HMAC) 자격을 AI가 로컬 검증, `INGEST_TICKET_SECRET` | **자격 검증은 BE가 한다.** AI는 기존 `X-API-Key` 헤더 인증. `INGEST_TICKET_SECRET`·jti 메모리 불필요 |
| 〃 | 2-2 ③ | 최대 연결 시간 = 자격 `maxSec`(30분) | **최대 수명 없음**(⑤). 무수신 15초만 |
| 〃 | 2-3 4 | 연결 해제·정지 시 BE가 `/stop`으로 WS를 닫음 | BE가 자기 WS를 닫으면 AI 연결도 따라 닫힌다. `/stop`의 WS 종료 연계는 선택 |
| 〃 | 7절 질문 1 | 브라우저 직결 가능 여부 | **불가로 확정**(AI 비노출). BE 중계 |
| FE `design-video-transport-fe.md` | 4-2 | 경로 `/ws/...` 미정, 메시지 계약 `[미정]` | 경로 `/ws/camera-upload`, 메시지 계약 ④로 확정. 재연결은 티켓 재발급 1회 |
| 〃 | 6-1 | 서버 값 `'h264-ws'` | **`'ws-h264'`**(계약 ⑥. 철자 통일) |

## 5. 위험과 수용

| # | 위험 | 대응·수용 |
|---|---|---|
| R1 | BE 중계가 BE CPU·스레드를 먹는다 | 비동기 전송·한도(⑨), 합격 기준 BE +5%p. 넘으면 NO-GO 후 P2 재검토 |
| R2 | **사용자당 1연결 가정은 틀린다** | 한 피보호자가 방마다 카메라를 가질 수 있어(최대 서비스 3대) 한도는 **세션당 1**이다(⑨). 지시문의 "사용자당 1연결"을 이렇게 바꿨다 |
| R3 | 재확인 최대 60초 지연 | 시청 재확인과 같은 허용 지연(수용) |
| R4 | 단일 인스턴스 메모리 상한 | 현 배포 구조와 같음. 확장 시 재설계 |
| R5 | BE 재시작·배포가 송출 연결을 끊는다 | FE 재연결(티켓 재발급)로 복구. 배포 시 감시 공백이 생기므로 PHASE 5 측정 시간대를 비껴 잡는다 |
| R6 | 티켓이 쿼리에 남음 | 60초·1회·소비 시 폐기(수용). `Sec-WebSocket-Protocol` 전달은 브라우저 호환 확인 후 후속 |
| R7 | jpeg 송출 무인증(FE 프록시)은 해결되지 않음 | D5 위험으로 기록, 별도 후속 |
| R8 | 합성 영상은 실제 화재 영상이 아니다 | 위험 구간 누락 0은 합성 기준이다. 실기기 1대 측정(PHASE 5)에서 보강 |
| R9 | nginx `/ws` 경로의 바이너리 크기·`proxy_read_timeout` | PHASE 5 직전 확인(인프라 관리 밖) |

## 6. 승인 요청

1. **GO**: 위 계약으로 PHASE 1(AI) -> 2(BE) -> 3(FE)로 진행.
2. **NO-GO**: 여기서 종료하고 **C(현행 유지)**를 확정으로 기록한다.
3. 확인해 주실 것: 2절 R2(사용자당 -> 세션당 1연결), 4절의 AI 설계 ①·③ 변경(HMAC 자격·`maxSec` 폐기).

## 7. 구현 순서 요약

AI(ingest-ws, 플래그 off) -> BE(티켓·WS 중계, 플래그 off) -> FE(h264Sender, PoC 플래그 off) -> 시험 인스턴스 합성 영상 -> gosky 실기기 1대 -> 판정 -> 저장소마다 PR(순서 AI -> BE -> FE, 모두 플래그 off로 먼저 배포).

## 8. 구현 중 확정·정정한 것 (2026-10-14, PHASE 1·2)

| # | 항목 | 내용 |
|---|---|---|
| 1 | ③ sessionId 바인딩 | 핸드셰이크는 sessionId를 **티켓에서만** 얻는다(URL·메시지로 받지 않음). 비교할 외부 값이 없어 "불일치 시 `[IDOR-ATTEMPT]`"는 해당 없다. 소유 확인은 소비 시점에 다시 한다(발급 뒤 삭제·이전이면 404) |
| 2 | ③ Origin 순서 | 등록 API가 붙이는 Origin 검사는 사용자 인터셉터 **뒤**다. 계약 순서(Origin -> 소비)를 지키려고 `OriginHandshakeInterceptor`를 먼저 직접 붙였다 |
| 3 | ③ AI 연결 시점 | AI 연결을 핸드셰이크 안에서 맺는다(최대 3초). AI 장애·플래그 꺼짐이면 연결이 성공하기 전에 503이라 FE가 바로 JPEG로 폴백한다. 대가: AI가 느리면 핸드셰이크가 그만큼 걸린다 |
| 4 | ④ 속도 | 초당 메시지 상한 = `recommended-fps x 2`(최소 5). 초과 close `1008 RATE_LIMITED`. AI의 `x1.5`는 더 안쪽(드롭 + 키프레임 재요청)이다 |
| 5 | ⑤ 재확인에 플래그 포함 | 재확인이 `ws-h264-session-ids`에서 빠졌는지도 본다 - 롤백(env 한 줄 + `up -d`)이 열린 연결도 60초 안에 닫는다. 킬 스위치(`ticket-enabled`)는 재확인에 포함하지 않는다(새 수립만) |
| 6 | ⑤ 즉시 종료 | 핸들러를 세션 추적 데코레이터로 감싸 토큰 무효화(정지·비밀번호 변경·역할 변경·탈퇴) 때 `WebSocketSessionCloser`가 즉시 닫는다(60초 재확인은 이중 방어) |
| 7 | ⑨ 연결 상한 | 같은 세션 교체는 자리를 더 쓰지 않는다. 자리가 없는 새 세션은 수립 전 503 |
| 8 | 종료 코드 | BE 발신: 1008 `AUTH_INVALIDATED`·`RATE_LIMITED` / 1009 `MESSAGE_TOO_LARGE` / 1000 `IDLE_TIMEOUT` / 1013 `REPLACED`·`BUSY` / 1011 `UPSTREAM_BACKPRESSURE`·`UPSTREAM_ERROR`·`UPSTREAM_CLOSED` / 1001 `SERVER_SHUTDOWN`. AI가 먼저 닫으면 그 코드·사유(`INGEST_DISABLED` 등)를 허용 목록 안에서 그대로 전한다 |
| 9 | 1013의 호환성 | Java(Tomcat) 클라이언트는 수신한 1013을 1002로 본다(브라우저는 정상). BE의 AI 연결이 Tomcat 클라이언트라 AI가 보낸 1013은 1011로 바뀐다. FE는 1013을 "교체됨 - 재연결 금지"로 처리하되 1002·1011도 폴백 대상이다 |
| 10 | FE 필요 사항 | 컨테이너 바이너리 버퍼를 `max-message-bytes + 1KB`로 올렸다(기본 8KB면 키프레임이 1009). 텍스트 상한 4KB(config) |
| 11 | AI 실측 | `-fflags nobuffer`는 프레임을 잃는다. 출력은 입력보다 1장 늦다(15fps 약 67ms - 변환 지연 p99 100ms에 포함). WebCodecs가 프레임당 슬라이스 1개를 내는지는 FE 실기기 확인 필요 |

BE 구현: `domain/camera/upload/*`(`CameraUploadRelay`·`CameraUploadConnections`·`CameraUploadHandshakeInterceptor`·`CameraUploadHandler`·`CameraUploadWebSocketConfig`), `domain/camera/service/CameraUpload{Service,TicketService}`·`CameraTransport`. 시험: 단위 + 실제 Tomcat 종단(`CameraUploadRelayEndToEndTest`, 가짜 AI 서버 포함, 12건).
