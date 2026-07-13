# AI 이상감지 WebSocket 수신 + 이력 저장 (1단계 — danger 기반)

> 작업 2026-07-13 · 저장소 SilverBridgeBe · 상태 **구현 완료(미커밋)**
> 범위: **수신·판정·이력**까지. **보호자 알림 발송은 2단계**(별도 진행 — 설계는 `docs/(2026-07-13) design-anomaly-notification.md`).
> 선행: `camera` 도메인(PR #213 — 카메라 소유권·SessionID 발급).

---

## 1. 배경 · 확정 사항

| 항목 | 결정 |
|---|---|
| 수신 방식 | 백엔드가 **AI WebSocket을 클라이언트로 구독** (AI 서버 **무변경**) |
| AI WS | `wss://testai.gosky.kr/api/v1/ws/live`, 인증 = `x-api-key` **헤더**(쿼리는 URL 로깅에 키가 남아 배제) |
| 판정 기준 | **`danger == true`** — 위험 판정 책임 = **AI 서버**, 전달·이력 책임 = **백엔드**(AI 담당 이하늘 협의) |
| 폴백 | AI의 danger 정식화 배포 지연에 대비해 **`CONFIDENCE` 모드**(신뢰도 임계) 설정 전환 제공 |
| 알림 | **이번 단계 없음.** 이력 적재까지 |

### AI가 보내는 신호 (문서 §6-3)

```
① session_status  — 세션 헬스(무시)
② latest_analysis — 분석 결과 ★ 판정 대상
   {"type":"latest_analysis","sessionId":"stream_009",
    "data":{"detectedType":"fire|smoke|normal|unknown","confidence":0.4547,
            "danger":false,"detections":[...],"analyzedAt":"2026-05-30T10:20:22.939247"}}
```

**주의해야 할 AI 동작 3가지 (설계에 직접 반영됨)**

1. **`danger`는 현재 라이브 경로에서 항상 false**(`fire_smoke_detection_service.py:121`, `stream_session_service.py:178` 하드코딩). AI가 정식화하기 전까지 `DANGER` 모드에서는 **이력이 0건**이다 — 의도된 동작이며, 조용한 침묵을 막기 위해 아래 `[ANOMALY-DANGER-MISMATCH]` 경고를 둔다.
2. **broadcast는 매 프레임(초당 여러 번)** — 위험 1건이 지속되는 동안 같은 신호가 수백 번 온다 → **쿨다운 필수**.
3. **캐시 미스 fallback 페이로드는 `{detectedType, confidence, danger}`만** 올 수 있다(`analyzedAt`·`detections` 없음) → `detected_at` **nullable**.

---

## 2. 설계

```
[AI WS /api/v1/ws/live]
   │ latest_analysis (초당 여러 번)
   ▼
AiLiveStreamSubscriber      ApplicationReadyEvent에서 접속(x-api-key 헤더) · 지수 백오프 재접속 · @PreDestroy 정리
   │   연결 직후 {"action":"list"} → 응답 세션 중 우리 cameras에 등록된 것만 {"action":"subscribe"}
   │   (세션 생성/종료 시 오는 live_streams broadcast로 신규 카메라도 자동 구독)
   ▼ AnomalySignalParser (JSON → AnomalySignal)
AnomalyDetectionService.handle()
   ├─ ① AnomalyJudge          danger==true (DANGER 모드) 또는 confidence>=임계 (CONFIDENCE 폴백)
   ├─ ② AnomalyEventCooldown  Redis SET NX EX — (sessionId, detectedType) 5분 1회
   ├─ ③ CameraService.findWardIdBySessionId()   미등록 세션 → 스킵 + WARN
   └─ ④ anomaly_events 적재
        ※ 2단계 훅 지점: 이 자리에서 AnomalyDetectedEvent 발행 → AFTER_COMMIT 리스너가 보호자 알림
```

### 판정 모드 (`anomaly.trigger-mode`)

| 모드 | 조건 | 용도 |
|---|---|---|
| **`DANGER`** (기본) | `detectedType ∈ {fire,smoke}` **AND `danger == true`** | 정상 운영. 신뢰도가 아무리 높아도 danger=false면 무시(판정 책임은 AI) |
| `CONFIDENCE` (폴백) | `detectedType ∈ {fire,smoke}` **AND `confidence >= anomaly.confidence-threshold`**(기본 0.6) | AI danger 미배포로 이력 0건일 때 **임시** 전환 |

- `normal`·`unknown`은 두 모드 모두 무시. `fall`·`weapon`은 enum에 자리만 두고(AI 미탑재) 현재 감지 대상 아님 — 라이브 연결 시 `DetectedType.isDetectable()`에 추가.
- **`[ANOMALY-DANGER-MISMATCH]` WARN** — DANGER 모드에서 "fire/smoke + 임계 이상 신뢰도인데 danger=false"가 오면 AI 미배포로 판단해 경고(세션당 1분 스로틀). 이력이 조용히 0건이 되는 상황을 로그로 드러낸다.

### 안전장치

- **기동을 막지 않는다**: WS 접속은 `ApplicationReadyEvent` 이후, 실패해도 WARN + 지수 백오프 재시도(2→4→…→60초 상한). `AI_API_KEY` 미설정이거나 `anomaly.enabled=false`면 **구독만 비활성**(앱은 정상 기동).
- **메시지 실패 격리**: 메시지 1건 처리 실패가 커넥션을 끊지 않도록 try/catch.
- **쿨다운 fail-open**: Redis 장애 시 적재를 막지 않는다(중복 이력 < 이력 유실).
- **미등록 세션**: 애초에 구독하지 않으므로 로그 폭주 없음. 경합으로 들어오면 WARN 후 폐기(소유자 없는 이력은 만들지 않는다).

---

## 3. 구현 — 변경 파일

**신규 (10)**

| 파일 | 역할 |
|---|---|
| `db/migration/V30__add_anomaly_events.sql` | 이력 테이블 |
| `domain/anomaly/entity/AnomalyEvent.java` | 이력 엔티티 |
| `domain/anomaly/repository/AnomalyEventRepository.java` | 조회(피보호자별 최신순) |
| `domain/anomaly/config/AnomalyProperties.java` | `anomaly.*` 설정 + `TriggerMode` |
| `domain/anomaly/dto/AnomalySignal.java` | 파싱된 AI 신호 |
| `domain/anomaly/client/AiLiveStreamSubscriber.java` | AI WS 구독·재접속·세션 구독 |
| `domain/anomaly/client/AnomalySignalParser.java` | `latest_analysis` JSON → 신호 |
| `domain/anomaly/service/AnomalyJudge.java` | 판정(모드 분기 + mismatch 경고) |
| `domain/anomaly/service/AnomalyEventCooldown.java` | Redis 쿨다운 |
| `domain/anomaly/service/AnomalyDetectionService.java` | 판정→쿨다운→매핑→적재 |
| `global/enums/DetectedType.java` | 감지 종류(FIRE·SMOKE·FALL·WEAPON·NORMAL·UNKNOWN) |

**수정 (3)**

- `domain/camera/repository/CameraRepository.java` — `findBySessionId` 추가
- `domain/camera/service/CameraService.java` — `findWardIdBySessionId()` 추가(anomaly 도메인 협력 창구)
- `src/main/resources/application.yaml` — `anomaly.*` 섹션

**테스트 (3)** — `AnomalyJudgeTest`, `AnomalyDetectionServiceTest`, `AnomalySignalParserTest`

### DB — `anomaly_events` (V30)

| 컬럼 | 비고 |
|---|---|
| `ward_id` | FK → users, **ON DELETE CASCADE** (탈퇴 시 이력도 정리) |
| `session_id` | 감지 시점 세션(비정규화 — 카메라 삭제 후에도 판독 가능) |
| `detected_type` | `FIRE`/`SMOKE` |
| `confidence`, `danger` | 판정 근거 기록. CONFIDENCE 폴백으로 적재된 건은 `danger=false`로 남아 사후 구분 가능 |
| `detected_at` | AI `analyzedAt`. **NULL 허용** — fallback 페이로드엔 없다. NULL = "AI 분석 시각 불명"(수신 시각은 `created_at`) |
| 인덱스 | `(ward_id, created_at DESC)` |

### 환경변수 (`.env.dev` 주입 — 평문 커밋 금지)

| 키 | 기본값 | 설명 |
|---|---|---|
| `AI_API_KEY` | (없음) | AI 서버 API Key. **미설정 시 구독 비활성 + WARN**(앱은 기동) |
| `AI_WS_URL` | `wss://testai.gosky.kr/api/v1/ws/live` | AI WS 주소 |
| `ANOMALY_ENABLED` | `true` | 수신 기능 ON/OFF |
| `ANOMALY_TRIGGER_MODE` | `DANGER` | `DANGER` \| `CONFIDENCE` |
| `ANOMALY_CONFIDENCE_THRESHOLD` | `0.6` | CONFIDENCE 모드 임계 |
| `ANOMALY_COOLDOWN_MINUTES` | `5` | 같은 (세션,종류) 이력 최소 간격 |

> `RequiredPropertiesValidator`에는 **넣지 않았다** — 키가 없다고 앱을 죽이면 로컬 개발이 막히고, "WS 연결 실패가 기동을 막지 않는다"는 규칙과도 어긋난다. 대신 구독만 비활성화하고 WARN.

---

## 4. 테스트 결과

`./gradlew build` **BUILD SUCCESSFUL** (전체 회귀 0건). anomaly 테스트 3종 통과.

| 케이스 | 결과 |
|---|---|
| `danger=true` → 이상감지 판정 | ✅ |
| `danger=false` → 무시 (DANGER 모드, 신뢰도 0.95여도) | ✅ |
| CONFIDENCE 모드: 임계 이상 → 감지 / 미만 → 무시 | ✅ |
| `normal`·`unknown` → 무시 (danger=true여도) | ✅ |
| `fall`·`weapon`(미탑재) → 무시 | ✅ |
| 쿨다운 내 중복 → 이력 스킵 | ✅ |
| 미등록 `session_id` → 스킵 + WARN | ✅ |
| fallback 페이로드(`analyzedAt` 없음) → 적재하되 `detected_at`=NULL | ✅ |
| 형식 이상 메시지 → 폐기 | ✅ |

---

## 5. 검증 가이드 (dev)

> dev 서버는 공개 도메인이 없다 — SSH 터널(`211.236.174.4:22001`, LocalForward 6511)로 접근.

**① WS 연결 확인**

```bash
docker logs -f dmu-dev-api | grep '\[ANOMALY\]'
# 정상: [ANOMALY] AI WS 접속 시도: url=wss://testai.gosky.kr/api/v1/ws/live
#       [ANOMALY] AI WS 연결됨 — 세션 목록 요청
#       [ANOMALY] 세션 구독: sessionId=ward_xxx     ← 등록된 카메라가 송출 중일 때
# 키 미설정: [ANOMALY] AI_API_KEY 미설정 — 이상감지 수신 비활성(앱은 정상 기동)
```

**② danger=true 수신 → 이력 적재 확인** (AI danger 배포 후)

```bash
docker logs dmu-dev-api | grep 'ANOMALY] 이상감지 이력 적재'
docker exec -it dmu-dev-db psql -U <user> -d <db> \
  -c "SELECT id, ward_id, session_id, detected_type, confidence, danger, detected_at, created_at
      FROM anomaly_events ORDER BY id DESC LIMIT 10;"
```

**③ AI danger 미배포 상태에서 검증하는 법 (CONFIDENCE 폴백)**
AI가 아직 `danger`를 채우지 않으면 DANGER 모드에서는 이력이 **정상적으로 0건**이다. 기능 자체를 검증하려면 `.env.dev`에 `ANOMALY_TRIGGER_MODE=CONFIDENCE`(필요 시 `ANOMALY_CONFIDENCE_THRESHOLD=0.4`)를 넣고 `docker compose -f docker-compose.dev.yml up -d`(restart 아님 — env 반영은 재생성 필요)로 올린 뒤, 카메라로 화재/연기를 잡아 이력 적재를 확인한다. **검증 후 `DANGER`로 되돌린다.**

**④ 쿨다운 동작 확인** — 화재를 계속 비춘 채 수 분간 두면 이력이 **5분에 1행**만 늘어야 한다(초당 수 행이면 쿨다운 실패).

```bash
docker exec -it dmu-dev-redis redis-cli keys 'anomaly:cooldown:*'
```

**⑤ AI 미배포 감지** — `docker logs dmu-dev-api | grep ANOMALY-DANGER-MISMATCH` 가 찍히면 AI가 아직 danger를 채우지 않는 것.

---

## 6. 2단계(알림) 인계

- **훅 지점**: `AnomalyDetectionService.handle()`의 이력 저장 직후(주석 표시됨) → `AnomalyDetectedEvent(anomalyEventId, wardId, sessionId, detectedType)` 발행 → `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("notificationExecutor")` 리스너가 발송. SOS(`SosNotificationListener`)와 동일 패턴.
- **수신자**: `connectionService.getActiveGuardianIds(wardId)` (+ 피보호자 본인 여부는 D-1 결정 필요).
- **채널 정책(요구)**: **FCM 고정** + **알림톡·SMS 선택**. 현재 `NotificationType`은 `mandatory` boolean(전부 강제 / 전부 설정대로)뿐이라 **`Policy.FORCED_PUSH_PLUS_SETTINGS` 도입이 필요**하다 — 설계 문서 §4 참조.
- **카카오 알림톡**: 채널 구현체 없음(enum만) → 켜도 스킵된다. Solapi 알림톡 + 템플릿 사전 심사 필요(설계 문서 §5).
- **쿨다운 재사용**: 이력 쿨다운(5분)이 곧 알림 쿨다운 역할을 겸한다 — 이력 1건 = 알림 1건.

---

## 7. 알려진 한계

- **단일 인스턴스 전제** — 백엔드를 스케일아웃하면 인스턴스마다 AI WS를 구독해 같은 신호를 중복 처리한다. Redis 쿨다운이 1차 방어(같은 키 경합 → 1건만 적재)지만, 정식 해법은 구독자 leader election 또는 AI 웹훅 전환.
- **AI 세션 상태는 메모리** — AI 서버 재시작 시 진행 중 세션이 사라진다. 백엔드는 재접속 후 `list`로 다시 구독하므로 자동 복구되지만, 그 사이 신호는 유실된다.
- **`danger` 의존** — DANGER 모드의 정확도는 전적으로 AI 임계 설정에 달렸다. 오탐이 잦으면 AI 측 threshold 조정이 필요하다(백엔드에서 조일 수 없음 — 역할 분리의 대가).
