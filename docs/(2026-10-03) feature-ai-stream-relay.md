# 보호자 실시간 카메라 보기 - AI 영상 백엔드 중계 + 분석 상태 STOMP 전환 (2026-10-03)

> 브랜치 `feature/camera-stream-relay` · 마이그레이션 없음 · FE 전달: Notion "DMU / 프론트엔드 전달 내용 / 실시간 카메라 보기 (보호자) - 영상·분석 상태를 백엔드 경유로 전환 안내"

## 1. 왜 바꿨나 (gosky 조사, 2026-10-02)

| 문제 | 실제 상태 |
|---|---|
| FE 프록시 `/api/streams/**` 무인증 | 서버 측 AI 키를 붙여 **AI 전 경로·전 메서드**로 넘긴다 - 로그인 없이 영상 시청뿐 아니라 남의 세션 `stop`, AI `/v1/cameras` 수정·삭제까지 가능 |
| 등록 여부와 무관하게 시청 | 보호자 이상감지 화면이 AI 전체 세션 목록을 거르지 않고 보여 준다 |
| AI 키 브라우저 노출 | `NEXT_PUBLIC_STREAM_WS_URL`에 `?apiKey=` - REST와 같은 키(AI REST 전체 호출 가능) |

백엔드는 원래 `x-api-key` **헤더**로만 AI WS에 붙고 있었다(노출 없음). 노출은 브라우저 직결 경로 쪽이다.

**정책 변경**: `docs/(2026-07-03) design-camera-domain.md`의 "백엔드는 영상을 프록시하지 않는다(FE↔AI)"를 뒤집는다. 시청은 **FE → 백엔드 → AI 완전 중계**(방식 A), 인가는 백엔드가 한다.

## 2. 범위

- ① 영상 보기: MJPEG 스트림, 최신 프레임(스냅샷)
- ② 송출 중 세션 목록·상태: 보호자 allowlist(ACTIVE 연결 + 백엔드 등록 카메라)로 거른다
- ③ 실시간 분석 상태: 브라우저 → AI WS 직결을 백엔드 STOMP `camera-analysis`로 대체

**범위 밖(별도 작업)**: 송출 업로드(세션 생성·프레임·종료) 중계 · 5초 클립(AI에 기능 자체가 없음 - 다음 작업으로 설계) · FE 카메라 등록 일원화(FE 몫, 안내 완료) · nginx 접근 제한(인프라) · AI 키 교체 · 챗봇·게임·예약 경로.

## 3. 결정 (PHASE 1, 2026-10-02 사용자 승인 "추천대로")

| # | 항목 | 결정 |
|---|---|---|
| 1 | `<img>` 인증 | **1회용 스트림 티켓**(쿼리, 60초, 한 사용자·한 세션). access token을 쿼리에 싣지 않는다(프록시·접속 로그 유출) |
| 2 | 시청 주체 | **보호자 전용**(피보호자 본인은 송출 기기에서 미리보기) |
| 3 | 연결 해제·정지 시 | 시청 중 **60초마다 재확인**해 끊음 + **최대 30분** |
| 4 | 상한·타임아웃 | 전체 20 / 1인 2, connect 3초, 목록·상태 응답 5초, MJPEG 무수신 15초 |
| 5 | 분석 이벤트 | 이름 `camera-analysis`(판정 알림 `anomaly-detected`와 구분), **상태 변화 시만**·같은 카메라 최소 2초 간격, bbox 제외 |
| 6 | 미등록 세션 | 없는 세션과 같은 404 `CAMERA_NOT_FOUND` + INFO `[CAMERA-UNKNOWN-SESSION]` |
| 7 | QA 머지 순서 | QA 브랜치 먼저 → 최신 dev에서 분기. **확인 결과 QA 내용은 #275~#289로 모두 dev 반영**(패치 대조, 2026-10-03) |

## 4. API 계약

모두 GUARDIAN 전용(클래스 레벨 `@PreAuthorize`), `Authorization: Bearer` - 영상 경로만 티켓.

| Method / Path | 응답 |
|---|---|
| `GET /api/guardian/camera/live` | `[{sessionId, wardId, wardName, label, status, lastFrameAt}]` - `status` = `running`·`disconnected`·`offline`, **`null` = AI 장애로 확인 불가**(꺼짐으로 채우지 않는다) |
| `GET /api/guardian/camera/{sessionId}/status` | `{status, lastFrameAt, fps, isAnalyzing, analysis}` - AI가 세션을 모르면 `offline`. `analysis`는 받아 둔 최근 분석(없으면 null) |
| `GET /api/guardian/camera/{sessionId}/latest-frame` | `image/jpeg`, `Cache-Control: no-store` |
| `POST /api/guardian/camera/{sessionId}/stream-ticket` | `{ticket, expiresInSeconds: 60}` |
| `GET /api/camera/stream/{sessionId}/mjpeg?ticket=` | `multipart/x-mixed-replace` 중계, `X-Accel-Buffering: no` (SecurityConfig에서 이 GET 하나만 permitAll) |

**STOMP** `/topic/{guardianId}/camera-analysis` - `{sessionId, wardId, status, detectedType, detectedTypeLabel, confidence, danger, analyzedAt(KST ISO)}`. 세션 종료 시 `status: offline`(분석 필드 null).

**에러**

| 상황 | 코드 |
|---|---|
| 티켓 없음·만료·재사용·다른 카메라용 | 401 `CAMERA_STREAM_TICKET_INVALID` |
| 연결 없음·PENDING·해제 | 403 `CAMERA_NOT_CONNECTED`("연결된 피보호자의 카메라만 볼 수 있습니다.") + `[IDOR-ATTEMPT]` |
| 정지 계정이 티켓으로 영상 요청 | 403 `INACTIVE_USER` |
| 없는·꺼진·미등록 카메라 | 404 `CAMERA_NOT_FOUND` |
| AI가 세션을 모름(송출 안 함)·프레임 없음 | 404 `CAMERA_NOT_STREAMING` |
| 동시 시청 상한 | 429 `CAMERA_STREAM_LIMIT_EXCEEDED` |
| AI 장애·키 미설정·서버 종료 중 | 503 `CAMERA_STREAM_UNAVAILABLE` / 티켓 저장소(Redis) 장애 503 `SERVICE_UNAVAILABLE` |

## 5. 구조

```
보호자 브라우저
  ├─ GET  /api/guardian/camera/live ─┐
  ├─ GET  .../{sessionId}/status     ├─ CameraStreamService ─ CameraService.getViewableCamera (인가 단일 지점)
  ├─ GET  .../{sessionId}/latest-frame│                       └ AiStreamClient (X-API-Key 헤더, 서버 안에서만)
  ├─ POST .../{sessionId}/stream-ticket ─ CameraStreamTicketService (Redis camera:stream:ticket:, GETDEL)
  ├─ <img> /api/camera/stream/{id}/mjpeg?ticket= ─ relay: 티켓 소비 → 계정·무효화·연결 재확인
  │        → CameraStreamSlots(상한·종료) → AI MJPEG를 요청 스레드에서 복사(pump)
  └─ STOMP /topic/{me}/camera-analysis ◀ WebSocketEventPublisher.sendToUser(정지 계정 게이트)
                                         ◀ LiveAnalysisBroadcaster(anomaly) ◀ AiLiveStreamSubscriber(기존 AI WS 구독)
```

- **도메인 경계**: 분석 상태는 이상감지 수신기가 받으므로 broadcaster는 `domain/anomaly`. 카메라 도메인은 `LiveAnalysisSnapshotPort`(camera에 인터페이스, anomaly가 구현)로만 최근 분석을 읽는다 - anomaly가 이미 camera를 쓰므로 반대 import는 순환이 된다(`WebSocketRecipientStatusPort`와 같은 방식). 라벨은 `DetectedTypeLabel` 한 곳(`ofLive` 추가: 정상·확인 불가).
- **스레드**: 영상은 요청 스레드에서 동기 복사(MVC async·전역 executor 설정 불필요, OSIV off라 DB 커넥션을 붙들지 않음). 분석 팬아웃은 전용 `liveAnalysisExecutor`(1/2/100, 포화 시 폐기) - 알림 풀(일반·긴급)을 쓰지 않는다.
- **종료**: `CameraStreamSlots`가 `SmartLifecycle`(phase 최댓값)로 웹 서버 우아한 종료보다 먼저 열린 AI 연결을 끊어 중계 루프를 빠져나오게 한다.

## 6. 정책 (rules 파일에 반영)

- 인가 근거는 `isActiveConnection`뿐(`getMyWards` 금지), 위반 403 + `[IDOR-ATTEMPT]`, 응답에 소유자·방 이름 미포함.
- AI 키는 서버 안 헤더로만, 로그·응답 금지. AI REST는 쿼리 키를 받지 않는다.
- `camera-analysis`는 **WS 전용** - 디스패처를 거치지 않아 푸시·문자·알림 이력이 없다. 화면 표시용이라 FE는 토스트를 띄우지 않는다.
- 꺼진(`is_active=false`) 카메라는 시청 대상이 아니다 - 기존 보호자 카메라 목록과 같은 모집단. **감지·알림은 그대로**다(ANOM-G08, `is_active`는 표시용).

## 7. 구현 중 발견·수정한 결함

- **MJPEG 닫기가 영원히 멈출 뻔했다**: Spring `SimpleClientHttpResponse.close()`는 연결 재사용을 위해 남은 본문을 끝까지 읽는다(`StreamUtils.drain`, spring-web 7.0.9 바이트코드로 확인). AI MJPEG는 0.2초마다 프레임을 보내며 끝나지 않아, 시청자가 떠난 뒤 닫는 순간 그 요청 스레드가 멈추고 자리도 반납되지 않는다(20건이면 영상 전면 불가). 목·끝 있는 스트림 단위 테스트로는 드러나지 않았다. → MJPEG만 `HttpURLConnection`을 직접 쓰고 `disconnect()`로 소켓을 바로 끊는다(`AiMjpegStream`). 실제 HTTP로 "끝없는 스트림이 2초 안에 닫힌다"·"다른 스레드에서 닫으면 읽던 쪽이 빠져나온다"를 회귀 테스트로 고정.
- 세션 ID 경로 인코딩: `encode()`를 값 치환 **전**에 두어 `/`까지 인코딩(세션 ID로 다른 AI 경로를 가리킬 수 없게). 인가가 DB 등록 세션만 통과시키므로 이중 방어다.

## 8. 검증

- `./gradlew build` **BUILD SUCCESSFUL** - 단위 **1168건 / 실패 0**(skip 16은 기존, `clean build`). 카메라·이상감지 관련 116건 중 **신규 63건**:
  - `CameraServiceTest$ViewableCamera` 4 - 연결/PENDING(403)/미등록(404, 연결 조회 안 함)/비활성(404)
  - `CameraStreamTicketServiceTest` 7 - 형식·고유성·GETDEL·없음·다른 세션·형식 오류(Redis 미조회)·Redis 장애 503
  - `CameraStreamSlotsTest` 7 - 1인·전체 상한, 이중 반납, AI 연결 정리, 서버 종료, 동시 10요청에도 상한 2
  - `CameraStreamServiceTest` 21 - 목록 상태 구분·AI 장애 null, 인가 실패 시 AI·티켓 미호출, **정지 계정·연결 없음·무효화 토큰 차단(AI 연결·자리 없음)**, 복사·헤더·정리, **시청자 이탈 시 AI 연결·자리 정리**, 최대 시간·연결 해제·정지·무수신·서버 종료, 재확인 장애 정책
  - `LiveAnalysisBroadcasterTest` 9 - ACTIVE 보호자만, **정지 보호자 미발송(실제 `WebSocketEventPublisher` 게이트)**, 같은 상태 미발송, 2초 간격, 송출 상태, 세션 종료 offline, 주인 없음, 스냅샷·clear, 오류 격리
  - `AiStreamClientTest` 8(실 HTTP) - 헤더 키·쿼리 없음, 404/장애 구분, MJPEG, **끝없는 스트림 즉시 닫힘**, 다른 스레드 닫기, 키 없음, 서버 꺼짐, 경로 인코딩
  - `AiLiveStreamSubscriberTest` +4, `CameraControllerSecurityTest` +3(WARD·ADMIN 403)
- 통합 테스트(vkcs): 마이그레이션·JPQL 변경 없음 - 기존 쿼리(`findBySessionId`·`findStatusById`)만 사용.
- **미검증(배포 후 확인)**: 실제 AI 서버·nginx를 거친 MJPEG 중계(브라우저 `<img>`), nginx가 `X-Accel-Buffering: no`를 따르는지.

## 9. 수용한 한계

- 동시 시청 상한·티켓은 **단일 인스턴스** 기준(WS 세션 종료와 같은 전제). 여러 대로 늘리면 공유 카운터로 옮긴다.
- 연결 해제·정지는 **최대 60초** 늦게 반영된다(이벤트 구독 대신 주기 재확인 - 이벤트 계약을 건드리지 않으려는 선택).
- 최근 분석 스냅샷은 AI WS가 붙어 있고 그 세션을 구독 중일 때만 있다(끊기면 비운다 - 옛 결과를 현재처럼 보이지 않게).
- **FE가 `/api/streams` 프록시를 줄이고 `NEXT_PUBLIC_STREAM_WS_URL`을 지우기 전까지 무인증 구멍은 그대로**다(백엔드만으로 막을 수 없음). 송출 업로드 경로는 범위 밖이라 프록시에 남는다.

## 10. 전달

### FE (Notion 페이지 작성 완료 2026-10-02, 계약 동일)

| 용도 | 지금 | 바뀐 뒤 |
|---|---|---|
| 목록+상태 | `/api/streams/v1/live-streams` + `/api/guardian/camera` 필터 | `GET /api/guardian/camera/live` |
| 상태·분석 | `/api/streams/v1/live-streams/{id}/status`·`/latest-analysis` | `GET /api/guardian/camera/{sessionId}/status` |
| 영상 | `<img src="/api/streams/.../mjpeg">` | 티켓 발급 → `<img src="{API}/api/camera/stream/{id}/mjpeg?ticket=">` |
| 스냅샷 | `/api/streams/.../latest-frame` | `GET /api/guardian/camera/{sessionId}/latest-frame` (fetch→blob) |
| 분석 상태 | `new WebSocket(NEXT_PUBLIC_STREAM_WS_URL)` | 기존 STOMP에 `camera-analysis` 토픽 추가 |

- 선행: 카메라 등록을 `POST /api/ward/camera`(피보호자 페이지, 방 이름 `label`, 응답 `sessionId`·`deviceId`로 송출, 방 이름 검증 400은 `message` 표시)로 교체 - 안 하면 새 API가 전부 빈 목록/404.
- 제거: `NEXT_PUBLIC_STREAM_WS_URL`(.env·.env.dev), `liveStreamSocket.ts`, 프록시는 송출 3경로(`POST v1/stream-sessions`·`/{id}/frame`·`/{id}/stop`)만 남김.

### AI 서버 (코드 수정 요청 없음 - 안내)

- 보호자 시청 요청의 호출 주체가 **브라우저 → 백엔드**로 바뀐다. AI 키는 백엔드 서버 안에서만 쓰고(`X-API-Key` 헤더), 브라우저 번들의 키는 FE 정리 후 사라진다.
- 시청 연결은 시청자 1명당 MJPEG 1개로 **백엔드에서 동시 최대 20개**(1인 2개), 영상 1건 최대 30분 후 재연결. `GET /api/v1/live-streams`는 보호자 목록 조회마다(FE 15초 폴링 수준) 호출된다.
- 참고(범위 밖 확인 사항, 조치는 AI 팀 판단): `config.py`의 API 키 하드코딩 기본값 / WS `subscribe`가 세션 존재 확인 전에 구독 집합에 들어가 누수 / CORS `*` / MJPEG가 세션 종료 후에도 끝나지 않음(백엔드가 30분 상한으로 대응).

### 배포

- 환경변수: `AI_API_KEY`(기존, 이상감지와 공용) · `AI_HTTP_BASE_URL`(선택, 기본 `https://testai.gosky.kr`). 나머지 `CAMERA_STREAM_*`는 기본값으로 충분.
- API 도메인 nginx는 인프라 관리 밖 - 응답 헤더 `X-Accel-Buffering: no`로 버퍼링을 끈다. 프레임이 0.2초마다 오므로 `proxy_read_timeout` 기본값으로 충분.
