# AI 이상감지 WebSocket 연동 — 분석 + 설계

> **목적**: AI 서버의 이상감지 실시간 신호(`latest_analysis`)를 백엔드가 WebSocket 클라이언트로 구독해 받고,
> 해당 피보호자(ward)의 보호자에게 긴급 알림을 보내는 기능의 **연동 방법 확정 + 설계**.
> 이번 단계는 **분석 + 설계 전용**(구현 없음). AI 서버 코드는 **읽기 전용**으로만 분석.

---

## 0. 분석 메타

- **분석 일자**: 2026-05-31 (KST) / **분석자**: Claude Code (읽기 전용 정적 분석)
- **분석 대상(정본)**: **`github.com/Dongyang-Mirae-University-software/SilverBridgeAiServer`** `main`, 최신 push **2026-05-30** — `gh api`로 파일 직접 판독
- **입력 정보 출처**: ① AI 담당자 **이하늘** 메모(배포본 `testai.gosky.kr`), ② 위 정본 저장소 소스 코드, ③ 기존 `docs/(2026-05-27) ai-server-integration-spec.md`
- **근거 라벨**: ✅ **코드검증**(파일:라인 근거) / 🟡 **이하늘제공·코드밖** / ❓ **확인필요**(환경변수·운영합의 등 코드로 확정 불가)

---

## 🟢 쉬운 설명 (먼저 읽기)

> 전문 용어 빼고, 우리가 만들 기능이 뭔지부터.

- **하려는 일**: AI 카메라가 **불/연기를 발견하면**, 그 집(피보호자)의 **보호자에게 "불이야!" 알림**을 자동으로 보내는 것.
- **AI가 신호를 주는 방식**: 옛날엔 우리가 "새 거 있어?" 하고 계속 물어봐야 했는데(폴링), 지금은 **AI가 생기는 즉시 우리한테 쏴 줍니다**(실시간, WebSocket). 우리는 그 연결에 **"나도 들을게" 하고 줄을 서면(구독)** 됩니다.
- **신호 모양**: "지금 stream_009 카메라에서 fire(불)를 0.45 확신으로 봤어, 위치는 여기" 같은 메시지가 옵니다.
- **꼭 알아야 할 함정 3가지**:
  1. 🚨 **`danger`(위험) 값은 믿지 마세요.** 코드상 **항상 "false(아님)"** 로 박혀 있어서, fire(불)인데도 danger=false로 옵니다. 위험 판단은 우리가 `종류(fire/smoke)`와 `확신도(confidence)`를 보고 직접 합니다.
  2. 📢 **알림 폭탄 주의.** 신호가 **1초에 여러 번** 쏟아집니다(영상이 계속 흐르니까). 그대로 알림 보내면 보호자 폰이 1초에 수십 번 울려요. 그래서 **"같은 불은 몇 분에 한 번만 알림"** 같은 거르개가 꼭 필요합니다.
  3. 🔗 **"누구네 집 카메라인지" 연결.** 신호엔 카메라 번호(`stream_009`)만 옵니다. 이게 **어느 피보호자(ward)인지**는 우리가 매핑표로 찾아야 합니다.
- **현재 감지 종류**: **불(fire)·연기(smoke) 둘뿐**입니다. (넘어짐·흉기는 아직 이 경로엔 없음)
- 아래 §2~§6은 위 내용을 **코드 근거와 함께** 자세히 적은 것이고, §6이 실제 **우리가 만들 설계**, §7이 **이하늘에게 물어볼 것**입니다.

---

> ### ⚠️ 이 문서가 이전 초안을 **교정**함
> 같은 파일의 첫 작성본(동일 일자)은 **틀린 저장소**(`github.com/gosky2/SilverBridgeAiServer` fork, 커밋 `62ddc9a`, 2026-05-22 — 로컬 `/home/skarndaudwls/SilverBridgeAiServer`에 클론돼 있던 구버전)를 분석해 두 가지 잘못된 결론을 냈다. **본 버전이 정정한다.**
> | 이전 초안(틀림) | 정본 코드 사실(맞음) |
> |---|---|
> | "WebSocket 코드가 없다" | **있다** — `live_ws_router.py`·`live_stream_router.py`·`live_ws_manager.py` ✅ |
> | "`danger`=`confidence≥threshold`라 false" | **`danger`는 라이브 경로에서 항상 `False` 하드코딩(표시 전용)** ✅ |
>
> 교훈: 로컬 클론(`/home/skarndaudwls/SilverBridgeAiServer`)은 `gosky2` fork였고 정본보다 8일·다수 기능 뒤처져 있었다. 향후 AI 분석은 **`Dongyang-Mirae-University-software` org 저장소**를 기준으로 한다.

---

## 1. 전체 아키텍처 — iPad 프레임 인제스트 + WS 구독 broadcast

기존 카메라 RTSP 폴링 모델(2026-05-27 분석)과 **다른 신규 라이브 경로**가 추가됐다.

```
[iPad 송출 앱]
   │  ① POST /api/v1/stream-sessions      {sessionId, cameraIdentifier, deviceType:"ipad"}   → 세션 생성
   │  ② POST /api/v1/stream-sessions/{sessionId}/frame  (multipart JPEG)  ← 매 프레임 반복
   ▼
[AI 서버]
   • 프레임 저장(in-memory frame_store) + fps/lastFrameAt 갱신
   • analyze_stream_frame: 15프레임마다 fire_smoke.pt(YOLO) 추론, 결과를 session_analysis_store(in-memory)에 캐시
   • 매 프레임 broadcast_nowait → 해당 sessionId 구독자에게 {session_status} + {latest_analysis} 푸시
   ▼
[WS /api/v1/ws/live]  구독자(보호자 웹 / 백엔드)에게 실시간 푸시
   ▲
   │  connect → action:list → action:subscribe(sessionId) → 이후 latest_analysis 수신
[우리 백엔드(SilverBridgeBe)]  ← 여기에 구독자로 합류
   • sessionId→cameraIdentifier→wardId 매핑 → 이상 판단 → anomaly_events 적재 → 보호자 긴급 알림
   ▼
[보호자 앱/웹]
```

핵심:
- **라이브 분석 결과는 AI DB에 저장되지 않는다**(in-memory `session_analysis_store`만). ✅ `stream_session_service.py:analyze_stream_frame`은 `session_analysis_store.set_result`만 호출, `AnalysisResult` INSERT 없음. → **영속화·이력은 우리 백엔드 책임**.
- 라이브 경로는 **화재/연기 전용**. `fall`/`weapon`은 이 경로에 없음(구 `detection_service` 스텁의 카메라-REST 경로에만 존재).

---

## 2. WebSocket 신호 구조 (코드 ✅)

### 2.1 엔드포인트 / 인증 — `live_ws_router.py`
- `GET(ws) /api/v1/ws/live` ✅ `live_ws_router.py:@router.websocket("/api/v1/ws/live")`
- **인증**: `x-api-key` **헤더** 또는 `apiKey` **쿼리** 둘 다 허용, `settings.api_key`(단일 정적 키)와 비교. 불일치 시 `close(1008, "AUTH_INVALID_KEY")` ✅
- `main.py:124` — `live_ws_router`는 `require_api_key` 의존성 **없이** 등록(WS 내부에서 자체 인증). 반면 `live_stream_router`(프레임 인제스트 등)는 `X-API-Key` 헤더 필수(`main.py:123`).
- ⚠️ apiKey는 `.env`에만. 쿼리로 키가 전달되므로 **접속 URL 로깅 금지**. 헤더 방식(`x-api-key`) 사용 시 URL 노출 회피 가능 → **백엔드는 헤더 방식 권장**.

### 2.2 클라이언트 → 서버 (action 프로토콜) ✅
연결 직후 서버가 `{"type":"connected","data":{...}}` 전송. 이후 클라이언트가 보내는 `action`:

| action | 효과 | 서버 응답 |
|---|---|---|
| `{"action":"ping"}` | 헬스 | `{"type":"pong"}` |
| `{"action":"list"}` | 현재 세션 목록 | `{"type":"live_streams","data":[{sessionId, cameraIdentifier, deviceType, status, lastFrameAt, viewerUrl, hlsUrl, ingestUrl, latestAnalysis}, ...]}` |
| `{"action":"subscribe","sessionId":"stream_009"}` | 세션 구독 | `{"type":"subscribed"}` → `{"type":"session_status",...}` → `{"type":"latest_analysis",...}` (스냅샷 1회) |
| `{"action":"unsubscribe","sessionId":...}` | 구독 해제 | `{"type":"unsubscribed"}` |
| 그 외 | — | `{"type":"error","data":{"message":"unsupported action"}}` |

⭐ **중요**: `latest_analysis`는 **구독자에게만** 푸시된다(`broadcast(..., session_id=...)` → 해당 세션 구독자 집합). 따라서 **백엔드는 감시할 모든 세션에 `subscribe` 해야** 분석을 받는다. 단 `live_streams`(세션 목록) broadcast는 **전체 연결**에 감(세션 생성/종료 시) → 백엔드가 신규 세션 자동 발견 후 구독 가능. ✅ `live_ws_manager.broadcast`(session_id None=전체), `live_stream_router.create_stream_session`/`stop_stream_session`.

### 2.3 서버 → 클라이언트 신호 ✅

**① `session_status`** (`live_stream_router.get_status_payload`)
```jsonc
{ "type":"session_status", "sessionId":"stream_009",
  "data": { "sessionId":"stream_009", "status":"running|stopped|disconnected",
            "lastFrameAt":"...|null", "fps":12.0, "viewerCount":1, "isAnalyzing":true } }
```
- `status`: `running`(정상) / `stopped`(종료) / `disconnected`(무프레임 `LIVE_STREAM_DISCONNECT_TIMEOUT_SEC`=기본10초 초과) ✅ `stream_session_service.refresh_disconnect_status`. → 세션 헬스 모니터링·끊김 감지에 활용.

**② `latest_analysis`** (`stream_session_service.analyze_stream_frame`) — 이상감지 판단 대상
```jsonc
{ "type":"latest_analysis", "sessionId":"stream_009",
  "data": {
    "detectedType":"fire",          // normal | fire | smoke | unknown
    "confidence":0.4547,            // YOLO score, 소수 4자리 (top-level = 최고 confidence 객체)
    "danger":false,                 // ⚠️ 라이브 경로 항상 false (표시 전용, 무시할 것)
    "detections":[ { "detectedType":"fire", "confidence":0.4547,
                     "bbox":{"x1":..,"y1":..,"x2":..,"y2":..} } ],
    "analyzedAt":"2026-05-30T10:20:22.939247"   // naive UTC (detectedAt)
  } }
```
- ⚠️ **subscribe 시점/캐시 결과가 없을 때**의 fallback은 다른 모양일 수 있음: `latest_analysis_for_session`이 캐시 없으면 `latest_analysis(cameraIdentifier)`(구 DB `AnalysisResult`)로 폴백 → `{detectedType, confidence, danger}`만(있고 `detections`/`analyzedAt` 없음), 이 `danger`는 구 스텁값(True 가능). 라이브 세션은 첫 분석 후 캐시가 차므로 통상 위 풀스키마. **백엔드 파서는 두 형태 모두 방어적으로 처리**. ✅ `stream_session_service.latest_analysis_for_session`/`latest_analysis`

### 2.4 소비자 측 교차검증 — `SilverBridgeStreamTestFe` (참조 WS 클라이언트) ✅
AI팀 테스트 프론트(`/home/skarndaudwls/SilverBridgeStreamTestFe`, React/Vite, 접속 `testai.gosky.kr`, AI 서버 포트 6017)가 위 프로토콜을 **그대로 사용**해 분석이 확정된다. `src/App.jsx`:
- **연결/프로토콜**: `makeWsUrl`이 `/api/v1/ws/live?apiKey=...`(쿼리 방식). `ws.onopen`에서 `{action:'list'}`+`{action:'ping'}`, `live_streams` 수신 시 첫 세션 `{action:'subscribe',sessionId}`. 메시지 분기 = `live_streams`/`session_status`/`latest_analysis`/`error` — §2.2/2.3과 정확히 일치.
- **구독 1개만**: FE는 "선택된 세션 1개"의 `latest_analysis`만 처리(단일 뷰어 UI). → **백엔드는 전 세션을 구독해야 함**(차이점).
- **재연결 없음**: `ws.onclose`는 상태만 갱신, **자동 재연결 미구현**. → 참조 클라이언트에 재연결이 없으므로 **백엔드가 직접 구현**해야 함(§6.1).
- **danger 사실상 미사용**: `guardianAnalysisCardClass`는 `isFireSmokeDetected(detectedType)`(=fire|smoke)를 1순위로 보고, `danger`는 2순위인데 라이브에선 항상 false라 **실질 미발동** → §3.2 정정 뒷받침. `normalizeDetectedType`은 weapon/knife·fall도 한글 매핑을 갖지만(향후 대비) 라이브 산출은 fire/smoke뿐.
- **REST 폴백 엔드포인트 확인**(README §4): `GET /live-streams/{sessionId}/status`·`/latest-analysis`·`/latest-frame`(JPEG). → 백엔드 폴백·스냅샷에 활용 가능.
- 🔒 **보안 발견(별도 보고)**: `App.jsx`에 **실제 형태의 API Key가 소스에 하드코딩**된 fallback 존재(테스트 FE 리포지토리에 평문 커밋). 운영 키와 동일하면 즉시 회전 필요 — 본 문서엔 값 미기재. AI팀 공유 권장.

---

## 3. detectedType / danger / confidence (코드 ✅)

### 3.1 detectedType 가능한 값 (라이브 fire/smoke 경로)
- `fire_smoke_detection_service.py`: `TARGET_CLASSES = {"fire", "smoke"}`. 결과:
  - **`normal`** — 타깃 미감지(기본)
  - **`fire` / `smoke`** — YOLO가 `conf ≥ FIRE_SMOKE_CONF_THRESHOLD`로 감지한 클래스 중 최고 confidence
  - **`unknown`** — 프레임 없음 / 모델 미로드 / 디코드 실패 / torch·cv2 미설치 (이 경우 `loadError` 동반)
- ⚠️ **`fall`/`weapon`은 라이브 경로에 없음.** (구 `detection_service.py` 스텁의 카메라-REST 경로에만 `fall/fire/weapon/normal/unknown`)
- 이하늘 기준 "`detectedType != normal` → 이상" 은 라이브 경로에선 사실상 **`fire`/`smoke`** 를 의미. `unknown`은 **무효(에러)** → 이벤트 제외.

### 3.2 danger 결정 로직 — `false`인데 `fire`인 이유 (정정)
- **라이브 경로 `danger`는 항상 `False`** ✅:
  - `fire_smoke_detection_service.detect_from_jpeg` docstring `"danger 는 항상 False (표시 전용)"`, 반환 dict `"danger": False` 고정.
  - `stream_session_service.analyze_stream_frame` payload `"danger": False` 하드코딩.
- **결론**: "fire인데 danger=false"는 임계값 문제가 아니라 **danger가 판정 필드가 아니라 더미(표시 전용)**이기 때문. → **백엔드는 `danger`를 신뢰/사용하지 말 것.** 이상 판단은 `detectedType` + `confidence`로.
- (참고: 첫 초안의 "threshold 미달이라 false" 설명은 fork의 구 스텁 로직이었고 라이브 경로엔 적용 안 됨.)

### 3.3 confidence 임계값 (코드 ✅)
- 모델 추론에 적용되는 임계값 = `FIRE_SMOKE_CONF_THRESHOLD` **기본 0.35** (`config.py:69`), IOU `0.45`(`:70`). → **0.35 이상이면 detections에 포함**. 이하늘 예시 `0.4547`이 통과한 이유.
- ⚠️ **0.35는 낮음** → 오탐 가능. **백엔드는 "표시"가 아닌 "알림"용 별도(더 높은) 임계값**(예 `ai-anomaly.alert-confidence` 기본 0.6~0.7)을 둬 단발 저신뢰를 거르는 것을 권장.
- 운영 환경변수로 임계값 override 가능 ❓(`.env` 미열람) — 배포 실제값 이하늘 확인.

---

## 4. sessionId → wardId 매핑 (코드 ✅ + 합의 ❓)

연결 고리가 코드로 확인된다:
1. **sessionId ↔ cameraIdentifier**: iPad가 `POST /stream-sessions`에서 **둘 다 직접 제공** ✅ `StreamSessionCreate{sessionId, cameraIdentifier, deviceType}`. 세션 상태에 `camera_identifier` 보관. `live_streams` 목록 item에 `sessionId`·`cameraIdentifier` **둘 다 포함** → 백엔드가 매핑표 구성 가능. ✅ `stream_session_service.list_live`
2. **cameraIdentifier ↔ targetUserId(=wardId)**: `Camera` 테이블의 `target_user_id`(자유 문자열 VARCHAR(64), FK·검증 없음) ✅ (구 분석 `camera.py:20`). 카메라 등록 API로 설정.
3. 따라서 **백엔드 매핑**: `latest_analysis.sessionId` → (live_streams에서) `cameraIdentifier` → (Camera/우리 매핑) `targetUserId` = 우리 `users.id`(role WARD).

⚠️ 주의:
- `latest_analysis`/`session_status` 메시지 자체엔 **sessionId만** 있고 cameraIdentifier 없음. → 백엔드는 `action:list`·`live_streams` broadcast로 **sessionId→cameraIdentifier 맵을 미리/지속 유지**해야 함.
- **`targetUserId`에 우리 6자리 `users.id` 저장 합의 필요 ❓** — 코드는 검증 안 함. cameraIdentifier→wardId를 AI의 `targetUserId`에 의존할지, **우리 DB에 자체 매핑 테이블**(camera_identifier→ward_id)을 둘지 결정 권장(후자가 안전 — AI 자유문자열에 비의존).

---

## 5. 신호 빈도 & 중복 (코드 ✅)

- **broadcast는 매 프레임 인제스트마다** 발생 ✅ `live_stream_router.ingest_stream_frame`이 프레임마다 `session_status`+`latest_analysis`를 `broadcast_nowait`. → iPad 송출 프레임레이트만큼(초당 여러 번 = 이하늘 관찰과 일치).
- **분석 재계산은 N프레임마다** ✅ `analyze_stream_frame` → `should_analyze(every_n=STREAM_SAMPLE_EVERY_N_FRAMES)`. **운영값 `N=5`**(`.env`, 코드 기본 15) → 사이 4프레임은 **동일 캐시 결과 재broadcast**(같은 `analyzedAt`). ✅
- **함의(중복 방지 설계 근거)**:
  - 같은 `analyzedAt`이 ~5프레임(운영값) 연속 반복 → **백엔드 dedup 키 `(sessionId, analyzedAt)`** 로 프레임-반복을 자연히 1건으로 수렴.
  - 신호당 고유 ID 없음(라이브 경로엔 `analysisNo` 없음) → dedup은 `(sessionId, detectedType, analyzedAt)` 조합.
  - 그래도 화재가 지속되면 5프레임마다 새 `analyzedAt`로 계속 옴 → **알림 폭탄 방지엔 추가 쿨다운 필수**(§6.3).
- 운영 `.env` 확인값: `STREAM_SAMPLE_EVERY_N_FRAMES=5`. iPad 송출 프레임레이트(캡처 최대 30fps, 업로드 큐) → broadcast는 그 속도. (시스템 구성은 `docs/(2026-05-31) testai-gosky-kr-system-overview.md`)

---

## 6. 백엔드 연동 설계 (PHASE 1 제안)

### 6.1 통신 — 백엔드가 WS 클라이언트로 구독
- **방향**: 백엔드 → `wss://testai.gosky.kr/api/v1/ws/live`에 **클라이언트 접속**(헤더 `x-api-key` 권장, 쿼리 `apiKey`도 가능).
- **위치**: `global/aiserver/`(도메인 무관 인프라). ⚠️ 도메인 로직 금지(CLAUDE.md §1). Spring `WebSocketClient`(`StandardWebSocketClient`) + `WebSocketHandler`(텍스트/JSON). 기존 STOMP는 **서버**이고 이건 **아웃바운드 클라이언트**라 별도.
- **구독 라이프사이클**:
  1. connect → `{"action":"list"}` 로 현재 세션 수신 → sessionId→cameraIdentifier 맵 구축
  2. 각 세션 `{"action":"subscribe","sessionId":...}`
  3. `live_streams` broadcast 수신 시 신규 세션 구독 / 종료 세션 해제(동적)
  4. 주기적 `{"action":"ping"}` → `pong` 미수신/끊김 시 **지수 백오프 재연결**(예 1s→…→30s+지터), 재연결 후 1~2 재수행
- **다중 인스턴스 주의 ❓**: replica가 여럿이면 같은 세션을 중복 구독 → 알림 중복. **단일 구독 워커**(리더 선출/단일 인스턴스 지정) 또는 §6.3 Redis 쿨다운으로 흡수.
- **app 라이프사이클**: `ApplicationReadyEvent`/`SmartLifecycle`로 기동·종료 연동.

### 6.2 이상감지 판단 기준
1. `type=="latest_analysis"`만 처리(`session_status`=헬스, `live_streams`=매핑 갱신).
2. **이상 여부**: `data.detectedType ∈ {"fire","smoke"}` (≠normal·≠unknown). **`danger` 무시**(항상 false).
3. **알림 임계값(백엔드 자체)**: `data.confidence >= ai-anomaly.alert-confidence`(설정값, 기본 0.6~0.7 권장). AI 모델 임계 0.35는 "표시"용이라 그대로 알림화하면 오탐.
4. fallback 스키마(§2.3 ⚠️) 방어: `detections`/`analyzedAt` 없을 수 있음 → null 가드.

### 6.3 중복 알림 방지 (★ 3단 방어)
1. **프레임-반복 dedup**: `(sessionId, analyzedAt)` 이미 처리한 신호는 스킵 → ~15프레임 반복을 1건으로.
2. **지속성 승격**: 같은 (sessionId, detectedType) 이상이 **연속 K회/T초 지속** 시에만 "진짜 이상"으로 승격(단발 오탐 억제).
3. **쿨다운**: 승격·발송 시 Redis `anomaly:cooldown:{wardId}:{eventType}` **TTL N분**(예 3~5분) → TTL 동안 동일 (ward,type)은 **저장만, 재알림 안 함**. 다중 인스턴스 중복도 흡수.

### 6.4 알림 발송 흐름
```
[AI WS] latest_analysis(fire/smoke, conf≥임계)
  → 파싱·판단(§6.2) → dedup·지속성(§6.3-1,2) → sessionId→wardId 매핑(§4)
  → AnomalyDetectedEvent 발행
  → @TransactionalEventListener(AFTER_COMMIT) + @Async:
       ├─ anomaly_events 저장 (항상)
       ├─ 쿨다운 체크(§6.3-3) — 통과 시에만 발송
       ├─ ward의 보호자 조회: connection(status=ACTIVE) → guardianId(들)
       └─ 긴급 알림: WebSocket(anomaly, 항상) + FCM(**설정 무시 강제**)
```
> 🟢 **쉽게**: 위 표시(`@TransactionalEventListener(AFTER_COMMIT)`·`@Async`)는 자바 용어인데, 뜻은 — **"DB에 무사히 저장된 걸 확인한 다음, 알림은 별도 작업으로 뒤에서 보낸다"** 입니다. 저장과 알림을 분리해서, 알림이 느려도 본 처리가 안 막히게 하는 흔한 방식이에요. (구현 단계 디테일이라 지금은 "그렇게 하는구나" 정도면 충분)

- **이상감지 = 필수 알림(사용자 설정 무시)**: CLAUDE.md "SMS 인증번호=디스패처 미경유 필수"와 동일 정책. `NotificationDispatcher`(설정 기반) 우회 또는 `mandatory` 경로 추가. **이 예외 결정을 `.claude/rules/domain-security-policy.md`에 기록**할 것.
  - 🟢 **쉽게**: 보통 알림은 사용자가 "알림 꺼줘" 하면 안 보내지만, **불·연기 같은 위급 알림은 설정과 상관없이 무조건 보낸다**는 뜻(생명 안전 우선). 문자 인증번호를 항상 보내는 것과 같은 예외.
- 보호자 식별: `connection` 도메인 ACTIVE 연결 역조회(기존 패턴).
- FCM 문구(시니어 직관성, 연결거절 선례): 예 `"[긴급] 화재가 감지되었습니다."` / `"[긴급] 연기가 감지되었습니다."`.

### 6.5 anomaly_events 매핑
> ⚠️ `anomaly_events`는 `V17`(2026-05-19)에서 DROP됨 → **재생성 마이그레이션 필요**(구현 단계).

| 컬럼 | 타입 | latest_analysis에서 | 비고 |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | (자체) | |
| `ward_id` | VARCHAR(6) FK→users.id ON DELETE SET NULL | sessionId→cameraIdentifier→wardId(§4) | ❓ 매핑 합의 전제 |
| `event_type` | VARCHAR(20) CHECK `('FIRE','SMOKE')` | `detectedType` 대문자 | **라이브는 FIRE/SMOKE만**. (구 FALL/WEAPON은 별 경로) — CHECK 범위 확정 ❓ |
| `confidence` | DECIMAL(5,2) | `data.confidence`(0~1) | 저장 스케일 통일 |
| `detected_at` | TIMESTAMPTZ | `data.analyzedAt`(naive UTC→UTC 명시) | |
| `session_id` | VARCHAR(64) | `sessionId` | 역추적·dedup |
| `is_confirmed` | BOOLEAN default false | (보호자 확인) | |
| `created_at` | TIMESTAMPTZ default now() | (적재 시각) | |
| ➕ `bbox` | JSONB null | `detections[].bbox` | 화면 표시용, 선택 |
| ➕ `raw_json` | JSONB null | 신호 원본 | 선택 |

- dedup 유니크: `(session_id, detected_at)` 또는 별도 처리(분석 고유 ID 없음).
- 인덱스: `ward_id`, `detected_at`, `(ward_id, detected_at)`, `session_id`.
- 스냅샷 이미지: 라이브는 `GET /api/v1/live-streams/{sessionId}/latest-frame`(JPEG) ✅ 또는 `/mjpeg` 제공 → 알림에 이미지 첨부하려면 이 엔드포인트 활용 가능(인증 X-API-Key). 1차 범위에선 메타데이터만 권장.

---

## 7. 미확인 항목 — 이하늘 확인 (❓)

| # | 질문 | 이유 | 막힘 |
|---|---|---|---|
| 1 | 배포본이 정본(`Dongyang-Mirae` main, 05-30)과 동일 코드인가, 추가 변경 있나 | 분석 기준 일치 확인 | ★ |
| 2 | `FIRE_SMOKE_CONF_THRESHOLD`/`STREAM_SAMPLE_EVERY_N_FRAMES`/`STREAM_STATE_BACKEND` **운영 실제값** | 알림 임계·빈도 산정 | ★★ |
| 3 | `cameraIdentifier`→`wardId` 매핑: AI `targetUserId`에 우리 6자리 id 저장 합의 vs 우리 자체 매핑표 | ward 식별 방식 | ★★★ |
| 4 | iPad가 `sessionId`/`cameraIdentifier`를 **어떻게 생성·할당**하나(누가 등록?) | 매핑 신뢰성·등록 흐름 | ★★ |
| 5 | `fall`/`weapon` 라이브 감지 계획 있나(현재 fire/smoke만) | event_type CHECK 범위 | ★★ |
| 6 | 모델 운영 로드 보장(`fire_smoke_enabled`, .pt 배포) — unknown/loadError 빈도 | 오탐·에러 처리 | ★ |
| 7 | WS apiKey = REST X-API-Key 동일 키인가, 회전 절차 | 키 주입·보안 | ★ |
| 8 | 백엔드가 WS 구독자로 합류해도 되는가(설계 의도가 보호자 웹 전용인지) | 연동 방식 승인 | ★★ |
| 9 | 알림용 confidence 권장 임계 협의 | 오탐/미탐 균형 | ★ |

---

## 8. 다음 단계 (승인 후)

1. ❓ #3·#4·#8 우선 확정(매핑·등록 흐름·백엔드 구독 합의).
2. 구현: `global/aiserver/` WS 클라이언트(헤더 인증·재연결·동적 구독) + DTO(2.3 두 스키마 방어) + 판단/dedup/지속성/쿨다운(Redis) + `AnomalyDetectedEvent`(AFTER_COMMIT·@Async) + 보호자 조회 + **긴급 강제 알림** + `anomaly_events` 재생성 마이그레이션 + 테스트.
3. **초기 드라이런 권장**: 임계·매핑 안정화까지 알림 OFF(로그만) → 오탐 검증 후 활성화.

---

<details>
<summary>부록 — 검증한 정본 파일 (gh api, 읽기 전용)</summary>

`github.com/Dongyang-Mirae-University-software/SilverBridgeAiServer` @ main(2026-05-30):
`app/routers/live_ws_router.py`·`live_stream_router.py`·`analysis_router.py`, `app/services/live_ws_manager.py`·`stream_session_service.py`·`session_analysis_store.py`·`fire_smoke_detection_service.py`, `app/schemas/stream_session_schema.py`, `app/core/config.py`(fire_smoke·stream 설정), `app/main.py`(라우터 등록), `gh repo view`/`commits`/`contents`.
대조용(구 fork): `gosky2/SilverBridgeAiServer` @ `62ddc9a`(로컬 클론). `.env` 미열람, 시크릿 평문 미기재. AI 저장소 수정·커밋·푸시 없음.
</details>
