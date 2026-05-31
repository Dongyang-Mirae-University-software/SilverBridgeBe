# testai.gosky.kr — 시스템 구성 상세 (AI 서버 + 테스트 FE)

> **이 문서는** `https://testai.gosky.kr/` 데모/테스트 환경이 **`SilverBridgeAiServer`(백엔드)** 와
> **`SilverBridgeStreamTestFe`(프론트)** 두 저장소로 어떻게 구성·배포되는지 정리한다.
> 우리 백엔드(`SilverBridgeBe`)가 이상감지 WS 연동(별도 문서)을 설계할 때의 환경 레퍼런스.

- **작성일**: 2026-05-31 (KST) / **작성자**: Claude Code (읽기 전용 정적 분석)
- **근거**: ① `Dongyang-Mirae-University-software/SilverBridgeAiServer`(main, 2026-05-30, `gh api`), ② 로컬 `SilverBridgeStreamTestFe`, ③ 운영 서버 `.env`(사용자 제공) — **시크릿 값은 본 문서에 평문 미기재**.
- **호스트**: 운영 서버 `skyserver`, 경로 `/home/apps/SilverBridgeSky/`.

---

## 🟢 쉬운 설명 (먼저 읽기)

> 어려운 용어 없이, 이게 뭔지부터.

- `testai.gosky.kr`은 **AI팀이 만든 "데모/실험용 웹사이트"** 입니다. 우리(SilverBridge) 서비스가 아니라, **AI 기능을 테스트해 보려고 만든 별도 사이트**예요.
- 이 사이트 하나 안에 사실 **프로그램 두 개**가 같이 돌아갑니다:
  1. **AI 두뇌(서버)** — 카메라 영상을 보고 "불이야/연기야"를 판단하고, 의료 챗봇 답변도 만드는 진짜 인공지능 부분.
  2. **테스트 화면(프론트)** — 그 두뇌를 사람이 눌러볼 수 있게 만든 웹 화면(버튼·영상·결과 표시).
- 한 컴퓨터(서버)에서 둘을 켜 놓고, **"문지기"(리버스 프록시)** 가 주소를 보고 알맞은 쪽으로 안내합니다. 그래서 사용자는 주소 하나(`testai.gosky.kr`)만 알면 됩니다.
- 우리 입장에서 **중요한 건 딱 하나**: AI 두뇌가 "불/연기 감지" 결과를 **실시간으로 쏴 주는데, 우리 서버가 그걸 받아서 보호자에게 알림을 보내면 된다**는 것. (그 방법은 별도 문서 `ai-anomaly-websocket-integration-spec.md`)
- 나머지(의료 챗봇, 병원 예약)는 같은 사이트에 얹혀 있을 뿐 **이번 알림 기능과는 상관없습니다**. 헷갈리지 마세요.

> 아래부터는 개발자/이하늘과 맞춰볼 때 쓰는 **상세 표·근거**입니다. 안 읽어도 위 6줄이면 전체 그림은 충분합니다.

---

## 0. 한 줄 요약

`testai.gosky.kr`은 **한 서버에서 AI 추론 서버(FastAPI/GPU, :6017)와 테스트 프론트(React/Vite, :6018)를
함께 띄우고, 리버스 프록시로 한 도메인(HTTPS)에 합쳐 놓은 통합 데모 환경**이다. 기능은 ① **화재/연기 실시간 감지(WS)**,
② **의료 챗(MedGemma + GPT)**, ③ **예약 자격증명 연동**, ④ 카메라/모델 관리.

---

## 1. 물리 구성 / 포트 맵

```
                    인터넷
                      │  HTTPS 443 (TLS)
                      ▼
        ┌─────────────────────────────┐
        │  Reverse Proxy (nginx 추정)  │  testai.gosky.kr
        │  - /api/* , /api/v1/* , ws   │ ──► AI 서버  127.0.0.1:6017
        │  - 그 외(/, 정적 자산)        │ ──► 테스트 FE 127.0.0.1:6018
        └─────────────────────────────┘
                      │ (같은 서버 skyserver 내부)
   ┌──────────────────┼───────────────────────────────────┐
   ▼                  ▼                                     ▼
[AI 서버 :6017]   [테스트 FE :6018]                    [부속 서비스]
 FastAPI/GPU       Vite preview                         · MedGemma LLM  127.0.0.1:6012
 (network_mode      (정적 빌드 서빙)                      (CHAT_UPSTREAM_URL)
  host)                                                  · 예약 API      127.0.0.1:6015
   │                                                       (RESERVATION_API_BASE_URL)
   ▼
[PostgreSQL 17 :6019→5432]  container silverbridge-ai-postgres
```

| 구성요소 | 포트 | 컨테이너/프로세스 | 근거 |
|---|---|---|---|
| AI 서버(FastAPI) | **6017** | `silverbridge-ai-server`, `network_mode: host`, `gpus: all` | `.env APP_PORT=6017`, `docker-compose.yml` |
| 테스트 FE(Vite) | **6018** | `silverbridge-stream-test-fe`, `ports 6018:6018` | FE `docker-compose.yml`, `package.json` |
| PostgreSQL 17 | **6019**(host)→5432 | `silverbridge-ai-postgres` | AI `docker-compose.yml` |
| MedGemma 업스트림 | 127.0.0.1:**6012** | (별도 서비스, 이 repo 밖) | `.env CHAT_UPSTREAM_URL` |
| 예약 API(**`SilverBridgeReservation`**) | 127.0.0.1:**6015** | NestJS+Prisma, 도메인 `reservation.dmu.gosky.kr` | `.env RESERVATION_API_BASE_URL` |
| 리버스 프록시 | 443 | nginx 등 (repo 밖) ❓ | 도메인·경로 분기 정황 |

> ⚠️ **리버스 프록시 설정은 두 저장소에 없음**(서버 인프라). 위 경로 분기는 정황 추정 — FE가 자기 호스트(`https://testai.gosky.kr`)를 **API base로도** 쓰므로(아래 §3), 프록시가 **경로(`/api/*` vs `/`)로 분기**한다고 보는 것이 자연스럽다. **정확한 라우팅·TLS는 서버 nginx 확인 필요.**

---

## 2. AI 서버 (`SilverBridgeAiServer`) 상세

### 2.1 스택 / 기동
- **FastAPI + uvicorn**, **GPU 필수**(`REQUIRE_GPU=true` → CUDA 없으면 기동 실패, `main.py:log_gpu_status`). `gpus: all`, `shm_size 2gb`.
- 의존성: `ultralytics`(YOLO)·`torch`·`transformers`·`accelerate`(실모델 추론), `opencv-python-headless`, `sqlalchemy`+`psycopg2-binary`. (`requirements.txt`)
- **기동 시퀀스**(`main.py:lifespan`): ① `Base.metadata.create_all`(테이블 자동 생성 — Flyway 아님, SQLAlchemy `create_all`) → ② 디렉터리 보장 → ③ `log_gpu_status`(GPU 점검) → ④ **MedGemma 로드+warmup**(`get_medgemma_loader().load()/.warmup()`) → ⑤ **fire_smoke 모델 로드**(`get_fire_smoke_detector().try_load()`).
- **상태 저장**: 세션 상태 `STREAM_STATE_BACKEND=memory` → **in-memory**(재시작 시 라이브 세션 소실). 분석 결과 일부만 DB.
- **API 문서**: `/api/docs`(Swagger), `/api/redoc`, `/api/openapi.json` (`.env DOCS_PATH` 등).
- **CORS**: `allow_origins=["*"]` 전면 허용 (`main.py`). → 내부망/프록시 전제.

### 2.2 라우터(기능) — `main.py` 등록 순서
| 라우터 | 인증(X-API-Key) | 역할 |
|---|---|---|
| `health` | ❌ 무인증 | 헬스체크 |
| `model` | ✅ | AI 모델 메타 CRUD |
| `camera` | ✅ | 카메라 등록(`identifier`/`targetUserId`/`guardianUserId`) |
| `analysis` | ✅ | (구) 카메라 스트림 분석 start/stop, 결과 조회 REST |
| `chat` | ✅ | 의료 챗(MedGemma/GPT) |
| `game` | ❌ 무인증 | 인지 게임 임베드 |
| `live_stream` | ✅ | **iPad 송출 세션 생성·프레임 인제스트·MJPEG·최신분석/프레임** |
| **`live_ws`** | ❌(자체 인증) | **`/api/v1/ws/live` WebSocket** — §이상감지 문서 |
| `reservation_credential` | ✅ | 예약 자격증명 저장(예약 API 6015 연동) |

- **인증**: 단일 정적 키 `API_KEY`(`.env`). REST는 `X-API-Key` 헤더(`require_api_key`), **WS는 `x-api-key` 헤더 또는 `apiKey` 쿼리** 자체 검증(불일치 시 close 1008). `game`·`health`는 무인증.

### 2.3 AI 기능 3종
1. **화재/연기 감지(YOLO `fire_smoke.pt`)** — 라이브 경로 핵심. `FIRE_SMOKE_CONF_THRESHOLD=0.35`, IOU `0.45`. detectedType ∈ {normal, fire, smoke, unknown}, **`danger`는 항상 false(표시 전용)**. 상세는 **`docs/(2026-05-31) ai-anomaly-websocket-integration-spec.md`**.
2. **의료 챗** — `CHAT_ENABLE_LLM=true`, 로컬 **MedGemma**(`google/medgemma-1.5-4b-it`, GPU 로드) + 업스트림 `127.0.0.1:6012`, 보조로 **OpenAI `gpt-4o-mini`**(`GPT_API_KEY`). intent/riskLevel/recommendedAction 산출(FE 챗 탭).
3. **예약 자격증명** — **`SilverBridgeReservation`**(NestJS+Prisma 예약 API, `127.0.0.1:6015`, 도메인 `reservation.dmu.gosky.kr`, MCP 브릿지 `src/mcp/reservation-mcp.ts` 보유)에 로그인/키 발급·저장 프록시(FE 예약 탭). ⚠️ 우리 백엔드는 병원 예약 기능을 2026-05-30 제거(`V24`)했으므로 **이 서비스는 우리와 분리된 별도 시스템**.

### 2.4 운영 `.env` 핵심값 (코드 기본값과 다른 것 ★)
| 키 | 운영값 | 의미 / 기본값과 차이 |
|---|---|---|
| `APP_PORT` | 6017 | (기본 9000) ★ |
| `STREAM_SAMPLE_EVERY_N_FRAMES` | **5** | **분석 5프레임마다 재계산** (코드 기본 15) ★ — 빈도 산정에 중요 |
| `SAVE_NORMAL_RESULTS` | true | normal 결과도 저장 허용 |
| `FIRE_SMOKE_CONF_THRESHOLD` | 0.35 | 낮음 → 알림용은 별도 상향 권장 |
| `STREAM_STATE_BACKEND` | memory | 세션 in-memory(재시작 소실) |
| `MEDIAMTX_ENABLED` | false | WebRTC/HLS 미사용 → viewerUrl=MJPEG |
| `REQUIRE_GPU` | true | CUDA 필수 |
| `STREAM_FALLBACK_INTERVAL_SEC` | 2 | (구 스트림 워커용) |
| `DATABASE_URL` | psql @ localhost:6019 | DB silverbridge_ai |

> 🔑 **시크릿 키**(값 마스킹, §5 회전 대상): `API_KEY`, `GPT_API_KEY`(OpenAI), `HF_TOKEN`, `POSTGRES_PASSWORD`.

---

## 3. 테스트 FE (`SilverBridgeStreamTestFe`) 상세

- **React 19 + Vite**, 단일 페이지(`src/App.jsx` ~862줄). `npm run preview`로 **빌드 정적 자산을 6018에 서빙**(Dockerfile runner, `--strictPort`). `vite.config.js`에 `allowedHosts:['testai.gosky.kr']`.
- **역할(탭)**: ① **피보호자(송출/ward)** — 세션 생성·카메라 송출·프레임 업로드, ② **보호자(모니터링/guardian)** — 라이브 세션 목록·최신 프레임/상태/분석·위험 카드, ③ **의료 챗**, ④ **예약**. (`App.jsx`, README §3)
- **통신 대상**:
  - AI 서버: `VITE_API_BASE_URL=https://testai.gosky.kr`(자기 도메인 = 프록시 경유 AI). WS는 `wss://testai.gosky.kr/api/v1/ws/live?apiKey=...`.
  - 예약 API: `VITE_RESERVATION_API_BASE_URL=https://reservation.dmu.gosky.kr/api/v1`(운영) — 별 도메인.
  - 기본값: 세션 `stream_001`, 카메라 `ipad-room-001`, 챗 userId `1`.
- **WS 클라이언트 동작**(교차검증 근거): connect→`{action:list}`+`{action:ping}`, `live_streams` 수신 시 첫 세션 `{action:subscribe}`, 메시지 `live_streams`/`session_status`/`latest_analysis`/`error` 처리. **자동 재연결 없음**. (상세는 이상감지 문서 §2.4)
- ⚠️ **빌드타임 주입**: `VITE_*`는 Dockerfile `ARG/ENV`로 **빌드 시 정적 번들에 인라인**된다. → **`VITE_API_KEY`가 브라우저로 배포되는 JS에 그대로 박힘**(누구나 추출 가능). §5 보안.

---

## 4. 데이터 흐름 (라이브 이상감지, 우리 연동 관점 요약)

```
[iPad(FE ward 탭)]  POST /api/v1/stream-sessions {sessionId, cameraIdentifier} → /frame (JPEG, 송출 FPS)
   → [AI :6017] frame_store 저장 + fps/lastFrameAt, 5프레임마다 fire_smoke YOLO 분석(in-memory 캐시)
   → 매 프레임 broadcast: {session_status}+{latest_analysis} → /api/v1/ws/live 구독자
[보호자(FE guardian 탭) / ★우리 백엔드]  WS subscribe(sessionId) → latest_analysis 수신
   → (우리 백엔드) sessionId→cameraIdentifier→wardId → anomaly_events → 보호자 긴급 알림
```

- **라이브 분석은 DB 미저장(in-memory)** → 이력·영속화는 우리 백엔드 책임.
- 상세 스키마·판단·중복방지·매핑·테이블 설계는 **`docs/(2026-05-31) ai-anomaly-websocket-integration-spec.md`** 참조.

---

## 5. 🔒 보안 이슈 (우선순위순)

1. **실 시크릿 평문 노출(긴급)** — `.env`의 `API_KEY`·`GPT_API_KEY`(OpenAI)·`HF_TOKEN`·`POSTGRES_PASSWORD`가 채팅/로그로 공유됨. **전부 회전(재발급) 권장**, 특히 OpenAI 키(과금). 우리 `.env`에만 보관(카카오 secret 관례).
2. **API Key가 프론트 번들에 인라인** — `VITE_API_KEY`가 빌드 시 정적 JS에 박혀 `testai.gosky.kr` 방문자 누구나 추출 가능. AI 서버 단일 정적 키가 사실상 공개 상태. → **운영 전 키 분리/회전 + AI 서버를 프록시·내부망으로 보호**. (이 키 = `.env API_KEY`와 동일 값 확인됨)
3. **CORS 전면 허용**(`allow_origins=["*"]`) — 공개 노출 금지, 프록시/내부망 전제.
4. **단일 정적 키, 회전·클라이언트별 키 없음** — 유출 시 전면 교체뿐.
5. **테이블 자동 생성**(`create_all`) — 마이그레이션 이력 관리 없음(AI 서버 한정. 우리 백엔드는 Flyway 유지).

---

## 6. 우리 백엔드(`SilverBridgeBe`) 관점 시사점

- **연동 대상 호스트/인증**: `wss://testai.gosky.kr/api/v1/ws/live`, 키는 `.env`로만 주입(헤더 `x-api-key` 권장).
- **빈도 정정**: 분석 재계산 **5프레임마다**(운영값) → 같은 `analyzedAt`이 ~5프레임 반복. dedup·쿨다운 파라미터 산정에 반영.
- **부속 서비스(6012 MedGemma·6015 예약)**는 이상감지 연동과 무관 — 혼동 주의.
- **이미지**: `MEDIAMTX_ENABLED=false`라 라이브 뷰는 MJPEG(`/api/v1/live-streams/{id}/mjpeg`)·최신프레임(`/latest-frame`). 알림 이미지 필요 시 이 엔드포인트(인증 필요).

---

## 7. 확인 필요 (❓)

| # | 항목 | 이유 |
|---|---|---|
| 1 | 리버스 프록시(nginx) 라우팅·TLS 설정 | 경로 분기·도메인 매핑 정확화 |
| 2 | `testai.gosky.kr` = 테스트 전용인가, 운영 전환 계획 | 보안 정책 적용 범위 |
| 3 | 시크릿 회전 일정(특히 OpenAI·API Key) | 노출 대응 |
| 4 | AI 서버를 내부망/프록시 뒤로 보호하는 구성 여부 | 단일 정적 키 노출 대응 |
| 5 | MedGemma(6012)·예약 API(6015 = `SilverBridgeReservation`, NestJS)·`reservation.dmu.gosky.kr` 운영 주체·경계 | 의존성 경계 파악 |

---

<details>
<summary>부록 — 분석 파일</summary>

- AI 서버(`Dongyang-Mirae`@main, gh api): `app/main.py`, `app/core/config.py`, `docker-compose.yml`, `requirements.txt` (+ 이상감지 문서의 라우터/서비스 일체)
- 테스트 FE(로컬): `docker-compose.yml`, `Dockerfile`, `vite.config.js`, `.env.example`, `package.json`, `src/App.jsx`, `README.md`
- 운영 `.env`(사용자 제공, **시크릿 마스킹**). 저장소 수정·커밋 없음.
</details>
