# 설계안 — 이상감지 알림 (FCM 고정 + 알림톡·문자 선택)

> 작성 2026-07-13 · 대상 저장소 SilverBridgeBe · 상태 **1단계 구현 완료 / 2단계(알림 발송) 진행 중**
> 목적: AI 서버가 감지한 화재/연기 등 이상 상황을 **연결된 보호자에게 즉시 알림**한다.
> 채널 정책(요구): **FCM = 고정(끌 수 없음)**, **카카오 알림톡 · SMS = 사용자가 선택**해서 추가 수신.
> 선행: `camera` 도메인(PR #213, 소유권·SessionID 발급) — 이 설계는 그 위에 얹는다.
> 갱신 이력: 초안 → 현판(트리거 모드 설정 전환 수용) → **2026-07-14 정정**(아래 §-1 — 1단계 구현 결과와 문서를 일치시킴).

---

## -1. 정정 (2026-07-14) — 이 문서와 실제 구현의 차이

**1단계(수신·판정·이력)는 2026-07-13에 이미 머지됐다.** 그 과정에서 아래 항목이 설계와 달라졌으므로, **이 문서의 §2-2·§2-3·§3·§8을 그대로 읽고 구현하면 중복 생성·설계 되돌림이 발생한다.** 실제 코드가 기준이다.

| 항목 | 이 문서(초안) | **실제 구현(기준)** |
|---|---|---|
| 트리거 모드 | `BACKEND_JUDGE`(기본) / `BOTH` / `AI_DANGER` 3개 | **`DANGER`(기본) / `CONFIDENCE`(폴백) 2개** — 위험 판정 책임을 AI로 확정(담당자 협의). 백엔드 판정은 AI danger 미배포 공백용 폴백으로 격하 |
| 판정 필터 | 종류 + 종류별 임계(fire 0.60/smoke 0.70) + 지속성(15초 내 3회) | **종류 필터 + 모드별 조건만.** 지속성·종류별 임계는 **미구현**(단일 `confidence-threshold: 0.6`, CONFIDENCE 모드에서만 사용) |
| 중복 제거 | `analyzedAt` 기준 dedup + 해시 폴백 | **미구현.** 매 프레임 반복은 **Redis 쿨다운**(`AnomalyEventCooldown`, `(sessionId, detectedType)` 5분)이 흡수 |
| 이력/쿨다운 관계 | "이력은 항상 저장, 알림만 쿨다운" | **쿨다운이 이력 적재도 억제**한다(초당 수 회 broadcast라 동일 행 폭주를 막기 위함). 따라서 2단계 알림은 "이력이 적재된 건"에만 발송되며 **알림 쿨다운이 자동으로 따라온다** |
| `anomaly_event` 스키마 | `camera_id`·`notified` 컬럼 | **없음.** 대신 `danger`(판정 근거 기록) 추가, `detected_at` nullable, 단수형 테이블명(V31 규칙) |
| 마이그레이션 | V30 | **V30 적용 완료**(현 최신 V31). 새 테이블 필요 없음 |
| 클래스명 | `AiLiveAnalysisSubscriber` | **`AiLiveStreamSubscriber`** |

### 2단계 잔여 범위 (이번 PR)

1. `AnomalyDetectedEvent` 발행 (`AnomalyDetectionService` 이력 저장 직후)
2. `AnomalyNotificationCooldown` — **알림** 쿨다운(이력 쿨다운과 별개). 보호자 5분 / **본인 1분**
3. `AnomalyNotificationListener` — AFTER_COMMIT + `@Async("notificationExecutor")`, WebSocket + 디스패처
4. `NotificationType` **Policy 도입** + `NotificationDispatcher` 3분기 (§4)
5. 테스트 (디스패처 정책·리스너)

알림톡은 **2차 PR**로 분리(§5·D-4). 필요한 값: `pfId`, `templateId`, 승인 템플릿 원문(변수 포함), 버튼 유무, 발신번호, **대체발송 OFF 확인**.

---

## 0. 지금 있는 것 / 없는 것 *(초안 시점 기준 — 현재 상태는 §-1)*

| | 상태 |
|---|---|
| 카메라 소유권(`cameras`, ward당 방별 N행, SessionID 발급) | ✅ 있음 (PR #213) |
| FCM·SMS 발송 인프라(`NotificationDispatcher` + 채널 전략) | ✅ 있음 |
| 긴급 알림 강제 발송(`WARD_SOS`, mandatory) | ✅ 있음 |
| **AI → 백엔드 이상감지 전달 경로** | ✅ **구현됨**(1단계, `AiLiveStreamSubscriber`) |
| **위험 판정 로직** | ✅ **구현됨**(1단계, `AnomalyJudge` — DANGER/CONFIDENCE) |
| **이상감지 이력** | ✅ **구현됨**(1단계, `anomaly_event` V30) |
| **이상감지 알림 타입·발송** | ❌ 없음 — **2단계 = 이번 PR** |
| **카카오 알림톡 채널 구현체** | ❌ 없음 (enum 값만 존재 → 켜도 조용히 스킵됨) — 2차 PR |

---

## 1. 가장 큰 제약 — AI 서버는 알림을 "밀어주지" 않는다

AI 서버(배포본) 실측 스펙(`docs/프로젝트_설명_AI서버.txt` §5·§6):

- 웹훅 **없음**. 결과는 자체 WS(`/api/v1/ws/live`)로 **구독자에게 broadcast**만 한다.
- `latest_analysis` = `{detectedType(normal|fire|smoke|unknown), confidence, danger, detections[], analyzedAt}`.
- **`danger`는 현재 판정값이 아니라 표시용 더미 — 라이브 경로에서 항상 false.** 지금 이 값으로 알림을 트리거하면 **알림이 영원히 0건**이 된다(§2-1).
- broadcast는 **매 프레임(초당 수 회)**, 분석 재계산은 5프레임마다 → **같은 `analyzedAt`이 반복 전송**된다.
- 모델 임계값 `FIRE_SMOKE_CONF_THRESHOLD=0.35`(낮음, "표시"용) → 그대로 쓰면 오탐 폭주.
- **캐시 미스 시 fallback 페이로드는 `{detectedType, confidence, danger}`만** 올 수 있다 — `analyzedAt`·`detections`가 **빠진다**(중복 제거 키 설계에 영향, §2-3).
- 인증은 단일 정적 `API_KEY`(헤더 `x-api-key`).

→ **결론: 위험 "판정"과 "알림"은 (적어도 현재는) 전적으로 백엔드 책임.** AI는 원자료(detectedType·confidence)만 준다.

### 수집 경로 — 세 안 비교

| 안 | 방식 | AI 서버 변경 | 평가 |
|---|---|---|---|
| **A (권장)** | 백엔드가 AI WS(`/ws/live`)를 **상시 구독**하는 클라이언트를 띄우고 `latest_analysis`를 받아 판정 | **불필요** | camera 설계의 "AI = 무변경 멍청한 내부 서비스" 원칙과 일치. 지금 당장 구현 가능 |
| B | AI가 백엔드로 웹훅 POST | 필요(팀 간 조율·배포) | 이상적이지만 AI 팀 작업 대기 → 일정 리스크 |
| C | 백엔드가 `GET /live-streams/{id}/latest-analysis` 주기 폴링 | 불필요 | 카메라 수×주기만큼 요청 폭증, 지연 큼 → 기각 |

**A로 진행하되, 판정 이후 로직은 수집 경로와 분리**해 둔다(아래 §3의 `AnomalyReport`가 유일한 진입점). 나중에 B(웹훅)가 준비되면 **인제스트 어댑터만 추가**하면 되고 도메인·알림 로직은 그대로다.

---

## 2. 위험 판정 정책 — 트리거 모드

오탐(false positive)이 곧 "양치기 소년"이라 시니어 타깃에서 특히 치명적이다. 판정의 **주체를 누구로 둘 것인가**부터 정한다.

### 2-1. `danger == true`로 트리거하는 안 — 지금은 성립하지 않는다

AI 코드 실측:

| 경로 | `danger` |
|---|---|
| **라이브**(iPad 프레임 → WS broadcast) | `fire_smoke_detection_service.py:121`, `stream_session_service.py:178` → **`"danger": False` 하드코딩**. docstring: *"danger 는 항상 False (표시 전용)"* |
| 구(舊) 경로(단일 이미지·RTSP) | `detection_service.py:27` → `danger = confidence >= model.threshold and detected_type not in {"normal","unknown"}` (모델 기본 threshold 0.75) |

즉 **판정식 자체는 이미 있으나 라이브 경로에 연결되어 있지 않다.** 따라서 `danger == true`를 트리거로 삼는 설계는 **AI 서버가 라이브 경로에 danger 판정을 정식 탑재하는 변경(AI 팀 작업)을 전제로만 성립**한다. 그 전에 이 조건으로 배포하면 알림이 **한 건도 발송되지 않으며, 아무도 고장을 눈치채지 못한다**(가장 나쁜 실패 모드 — 조용한 침묵).

**중요 — danger로 옮겨가도 백엔드 작업량은 거의 안 줄어든다.** 없어지는 건 아래 2-2의 판정 필터 하나뿐이고, ① AI가 웹훅을 안 쏘므로 **WS 상시 구독자**는 그대로 필요하고, ② `danger:true`도 **매 프레임 반복 broadcast**되므로 **중복 제거·쿨다운**이 그대로 필요하며, ③ 이력·디스패처 정책·리스너·문구는 전부 동일하다. 게다가 AI 판정식에는 **지속성(연속 N회) 개념이 없어** 연기가 한 프레임 스치기만 해도 `true`가 되고, **임계값 튜닝 권한이 AI 서버 `.env`/모델 메타데이터로 넘어가** 오탐이 터졌을 때 백엔드가 즉시 조일 수 없다.

### 2-2. 백엔드 판정 필터 (3중)

1. **종류 필터** — `detectedType ∈ {fire, smoke}`만 후보. `normal`·`unknown`(프레임 없음/모델 미로드/디코드 실패)은 무시.
2. **신뢰도 임계** — AI의 0.35(표시용)보다 **높게** 재설정. 기본값 `fire ≥ 0.60`, `smoke ≥ 0.70`(연기는 오탐이 잦음). `application.yaml`로 튜닝 가능하게.
3. **지속성(sustain)** — 최근 `15초` 내 **서로 다른 분석 결과 3회 이상**이 임계를 넘어야 확정.

### 2-3. 트리거 모드 (설정 전환)

`danger` 정식화는 AI 팀 일정에 달렸으므로, **백엔드를 그 일정에 묶지 않는다.** 트리거 조건을 설정값 `silverbridge.anomaly.trigger-mode`로 전환 가능하게 둔다.

> ⚠️ **아래 3모드 표는 초안이며 채택되지 않았다.** 실제 구현은 **2모드**다(§-1). 위험 판정 책임을 AI 서버로 확정하고, 백엔드 판정은 폴백으로 격하했다.
>
> | 모드 | 트리거 조건 | 언제 | 상태 |
> |---|---|---|---|
> | **`DANGER`** (기본) | AI `danger == true`만 신뢰 | 현재 기본값. 위험 판정 책임 = AI | ✅ 구현 |
> | **`CONFIDENCE`** (폴백) | `confidence >= confidence-threshold`(0.6) | AI danger 미배포로 이력이 0건일 때만 임시 전환 | ✅ 구현 |
>
> AI 라이브 `danger`는 현재 항상 false라 **DANGER 모드에서 이력 0건이 정상**이며, 이 침묵은 `[ANOMALY-DANGER-MISMATCH]` WARN(세션당 1분 스로틀)으로 드러난다.

*(이하 초안 — 기록용)*

| 모드 | 트리거 조건 | 언제 |
|---|---|---|
| **`BACKEND_JUDGE`** (기본) | `danger` **무시**, 2-2 필터로 백엔드가 판정 | **지금 당장 — AI 무변경으로 오늘 배포 가능** |
| **`BOTH`** (권장 전환 경로) | `danger == true` **AND** 2-2 필터 통과 | AI가 danger를 정식화한 뒤. AI 판정을 신호로 받되 백엔드 가드(임계·지속성) 유지 |
| `AI_DANGER` | `danger == true`만 신뢰 | AI 판정이 현장 검증된 후에만. 백엔드는 중복 제거·쿨다운만 담당 |

- 세 모드 모두 **중복 제거·쿨다운·이력·알림 경로는 공통**이다. 모드는 `AnomalyJudge` 내부의 트리거 조건만 바꾼다.
- **운영 가드(불일치 감지)** — 모드가 `BOTH`/`AI_DANGER`인데 `detectedType ∈ {fire,smoke}` + 높은 confidence가 지속되는데도 `danger=false`면 `[ANOMALY-DANGER-MISMATCH]` WARN 로깅. AI 배포 누락·롤백으로 알림이 조용히 죽는 상황을 침묵 대신 로그로 드러낸다.
- **AI 팀에 요청할 계약(간단)**: 라이브 경로에서도 `danger = detectedType ∈ {fire,smoke} && confidence ≥ threshold`로 채울 것(구 경로 `detection_service.py:27`과 동일식). 페이로드 스키마 변경은 불필요 — **기존 필드를 실제 값으로 채우기만** 하면 된다.

### 2-4. 중복 제거 · 쿨다운 (모드 무관 공통)

- **중복 제거 키 = `analyzedAt`** — 같은 `analyzedAt`의 반복 broadcast는 1회로 카운트. ⚠️ 캐시 미스 fallback 페이로드에는 **`analyzedAt`이 없을 수 있으므로**(§1), 없으면 **수신 시각 + 페이로드 해시**로 대체한다. `analyzedAt`에만 의존하면 이 경로에서 중복 제거가 무너진다.
- **쿨다운** — 확정 후 동일 `(sessionId, detectedType)`에 대해 **5분간 알림 억제**. SOS와 동일 원칙: **이력(`anomaly_events`)은 항상 저장, 억제되는 건 "알림"뿐**. 구현은 `SosNotificationCooldown`(Redis) 패턴 재사용. 인프라 장애 시 fail-open(긴급 우선).

확정되면 → `AnomalyReport(sessionId, detectedType, confidence, detectedAt)` 1건 생성.

> 값(0.60/0.70/3회/15초/5분)은 **초기 추정치**다. 실제 카메라·환경에서 오탐률을 보고 조정할 것을 전제로 전부 설정값으로 뺀다.

---

## 3. 도메인 설계

새 바운디드 컨텍스트 `domain/anomaly` (카메라 "소유권"은 `camera`, "감지·알림"은 `anomaly`로 분리 — 책임이 다르다).

```
[AI WS /ws/live]
   │ latest_analysis (초당 수 회, 같은 analyzedAt 반복)
   ▼
AiLiveAnalysisSubscriber        (domain/anomaly/client) — WS 상시 구독·재접속, sessionId → 우리 cameras 매칭
   │ 판정(§2: 종류·임계·지속성)  AnomalyJudge (domain/anomaly/service)
   ▼ AnomalyReport
AnomalyService.report()          @Transactional — anomaly_events 저장 + AnomalyDetectedEvent 발행
   ▼ (AFTER_COMMIT, @Async)
AnomalyNotificationListener      쿨다운 확인 → 보호자별 발송
   ├─ WebSocket "anomaly-detected"   (추상화 밖, 항상 발송)
   └─ NotificationDispatcher(ANOMALY_DETECTED)
         ├─ FCM        강제(설정 무시)           ← 요구: 고정
         ├─ SMS        사용자 설정 ON일 때만      ← 요구: 선택
         └─ KAKAO_ALIMTALK  사용자 설정 ON일 때만 ← 요구: 선택 (※ 채널 구현체 필요, §5)
```

SOS(`SosService` → `SosTriggeredEvent` → `SosNotificationListener`)와 **동일 패턴**이다: 이력 저장 커밋 후 비동기 발송, 보호자별 try/catch 실패 격리.

### 수신자
- **연결된 ACTIVE 보호자 전원** — `connectionService.getActiveGuardianIds(wardId)` 재사용.
- **피보호자 본인에게도 보낼지는 결정 필요**(§7 D-1). 화재라면 집 안 당사자가 가장 먼저 알아야 하므로 **본인 FCM 포함을 권장**.

### 엔티티 `anomaly_events` (V30)

| 컬럼 | 설명 |
|---|---|
| `id` | PK |
| `camera_id` | FK → cameras(id), ON DELETE CASCADE |
| `ward_id` | 소유 피보호자(6자리, 문자열 — 프로젝트 관례) |
| `session_id` | 감지 시점 SessionID(카메라 삭제 후에도 이력 판독용, 비정규화) |
| `detected_type` | `FIRE` / `SMOKE` (확장: FALL/WEAPON) |
| `confidence` | 확정 시점 최고 신뢰도 |
| `detected_at` | AI `analyzedAt`(naive UTC → 변환) |
| `notified` | 쿨다운으로 알림을 생략했는지 구분 |
| `created_at` | BaseTimeEntity |

조회 API(보호자 이상감지 이력 목록)는 **이번 범위 밖** — 이벤트 저장까지만 하고, 필요해지면 별도 PR.

---

## 4. 채널 정책 확장 — 여기가 이번 설계의 핵심 변경점

현재 `NotificationType`은 `boolean mandatory` 하나뿐이고, 디스패처 동작은 **둘 중 하나**다.

- `mandatory=false` → 사용자 설정 활성 채널로만 발송
- `mandatory=true` → **사용자 설정 전부 무시**, FCM 강제 + (FCM 전달 실패 시에만) SMS 폴백

요구사항 "**FCM은 고정, 알림톡·문자는 선택**"은 **둘 다 아니다.** → `NotificationType`에 채널 정책(policy)을 도입한다.

```java
public enum NotificationType {
    CONNECTION_REQUEST(Policy.SETTINGS_ONLY),
    CONNECTION_ACCEPTED(Policy.SETTINGS_ONLY),
    CONNECTION_REFUSED(Policy.SETTINGS_ONLY),
    CONNECTION_DISCONNECTED(Policy.SETTINGS_ONLY),
    INQUIRY_ANSWERED(Policy.SETTINGS_ONLY),

    WARD_SOS(Policy.FORCED_PUSH_WITH_SMS_FALLBACK),   // 기존 동작 100% 보존

    ANOMALY_DETECTED(Policy.FORCED_PUSH_PLUS_SETTINGS); // 신규: FCM 고정 + 나머지 채널은 설정대로

    public enum Policy {
        /** 사용자 설정 활성 채널로만 발송 (기존 mandatory=false). */
        SETTINGS_ONLY,
        /** FCM 강제 + 전달 실패 시에만 SMS 폴백 (기존 mandatory=true, WARD_SOS 전용). */
        FORCED_PUSH_WITH_SMS_FALLBACK,
        /** FCM은 설정 무시하고 항상 발송 + SMS·알림톡·이메일은 사용자 설정 ON일 때만 추가 발송. */
        FORCED_PUSH_PLUS_SETTINGS
    }
}
```

디스패처는 `switch (type.policy())` 3분기. `FORCED_PUSH_PLUS_SETTINGS`의 발송 대상 채널 집합은:

```
targets = {FCM} ∪ (settingService.enabledChannels(userId) \ {FCM})
```

즉 FCM은 무조건 넣고, 나머지는 설정대로. 채널별 try/catch 실패 격리·미구현 채널 스킵은 기존 로직 그대로 재사용한다.

**하위 호환**: `isMandatory()`는 `policy != SETTINGS_ONLY`로 유지하거나(호출부 있으면) 제거. 기존 6개 타입의 런타임 동작은 **바뀌지 않는다**(SOS 폴백 로직 포함).

### 사용자 설정 API 영향
`/api/user/me/notification-settings`(GET·PUT)는 **변경 불필요**. 설정은 "채널 단위"(FCM/SMS/ALIMTALK/EMAIL)이고, 이상감지에서 FCM을 강제하는 건 발송 시점 정책이기 때문. 단 **FE는 FCM 토글을 "이상감지·긴급 SOS는 항상 발송됩니다" 문구와 함께 표시**해야 사용자가 "껐는데 왜 와?"로 혼란을 겪지 않는다. (설정을 끄면 연결·문의 알림은 실제로 안 온다 — 거짓말이 아님)

> ⚠️ 알아둘 점: 지금 설정 모델은 **채널 단위 전역**이라 "이상감지는 문자로, 연결 알림은 문자 말고"처럼 **타입별 채널 선택은 불가**하다. 필요하면 `(user, type, channel)` 3축으로 확장해야 하는데, 시니어 UX상 설정 화면 복잡도가 급증하므로 **현 단계에서는 권하지 않는다**.

---

## 5. 카카오 알림톡 — "선택 가능"하게 만들려면 채널 구현체가 필요하다

`KAKAO_ALIMTALK`은 **enum 값만 있고 구현체가 없어**, 사용자가 켜도 디스패처가 조용히 건너뛴다. 즉 지금 상태로는 "선택은 되는데 아무것도 안 오는" UI가 된다. 실제 발송하려면:

1. **`KakaoAlimtalkNotificationChannel implements NotificationChannel`** 추가 — 디스패처는 빈만 추가하면 자동 수집(전략 패턴, 디스패처 코드 수정 불필요).
2. **발송 경로**: 이미 쓰는 **Solapi가 알림톡을 지원**하므로 기존 SMS 연동(`SmsNotificationChannel`)과 같은 계정·SDK로 처리 가능 → 신규 벤더 계약 불필요.
3. **선행 작업(코드 아님, 리드타임 있음)**:
   - 카카오 비즈니스 채널 개설 + Solapi에 **발신 프로필(pfId)** 등록
   - **알림톡 템플릿 사전 심사** — 알림톡은 자유 문구 발송 불가. 예: `[SilverBridge] #{피보호자명}님 댁 #{위치}에서 #{감지종류}가 감지되었습니다. 앱에서 확인해 주세요.` (변수 3개)
   - 템플릿 승인 후 `templateId`를 설정값으로 주입
4. **주의 — Solapi "알림톡 실패 시 SMS 대체발송" 옵션은 끈다.** 켜면 SMS를 선택하지 않은 사용자에게 문자가 나가 **"문자는 선택"이라는 요구·과금 의사에 반한다**.

템플릿 심사가 며칠 걸리므로 **단계 분리**를 권장한다:
- **1차 PR**: 판정·이력·디스패처 정책 확장 + **FCM 고정 + SMS 선택**(둘 다 이미 구현체 존재) → 바로 동작.
- **2차 PR**: 알림톡 채널 구현체 + 템플릿 연동(승인 나오는 대로).

---

## 6. 알림 문구 (초안)

- **FCM/WebSocket**: 제목 `이상 상황 감지`, 본문 `홍길동님 댁 거실에서 화재가 감지되었습니다.`
  - data: `{type: "ANOMALY_DETECTED", wardId, cameraSessionId, detectedType, anomalyEventId}` → FE가 해당 카메라 라이브 화면으로 딥링크.
- **SMS**: `[SilverBridge] 홍길동님 댁 거실에서 화재가 감지되었습니다. 앱에서 확인해 주세요.`
- 문구 원칙(SOS 정책과 동일): 시니어/4050 대상 — **모호한 완곡어법 금지**, "감지되었습니다"로 명확히. 이름이 비면 `보호 대상자` 폴백(`SosService.FALLBACK_WARD_NAME`과 동일).
- ⚠️ **오탐 가능성을 문구가 과잉 확신하지 않도록** 할지 결정 필요(§7 D-2): "화재가 감지되었습니다" vs "화재가 의심됩니다".

---

## 7. 결정 사항 (2026-07-14 확정)

| # | 질문 | **결정** |
|---|---|---|
| **D-0** | **트리거 모드**(§2-3) — `danger==true`로 알릴 것인가? | ✅ **`DANGER`(AI 판정 신뢰) 유지** — 1단계에서 확정·구현됨. 초안의 `BACKEND_JUDGE`는 채택되지 않았고, 백엔드 임계 판정은 `CONFIDENCE` 폴백으로만 남는다. AI가 danger를 정식화하면 그대로 동작 |
| **D-1** | 피보호자 **본인에게도** 이상감지 알림? | ✅ **보낸다.** 화재는 당사자 대피가 최우선. 수신자 = ACTIVE 보호자 전원 + **피보호자 본인**. 알림 쿨다운은 **본인 1분 / 보호자 5분** |
| **D-2** | FCM **전달 실패**(토큰 만료 등) 시 SMS 강제 폴백? | ✅ **하지 않는다.** 문자는 사용자 선택(과금·수신 동의) — 폴백은 그 의사를 무시한다. 전달 실패 시 WARN 로깅으로 침묵만 드러낸다. ※ SOS(`WARD_SOS`)의 기존 폴백은 **그대로 유지** |
| **D-3** | 알림 문구 톤 — "감지되었습니다" vs "의심됩니다" | ✅ **"감지되었습니다"** — 시니어 대상 모호한 완곡어법 금지 원칙. 오탐률 확인 후 재검토 |
| **D-4** | 알림톡 1차 포함? | ✅ **2차 PR로 분리.** 카카오 알림톡은 이 프로젝트에서 **사용 이력이 없다**(카카오 연동은 OAuth 로그인뿐). 채널 개설·발신 프로필·**템플릿 사전 심사**가 선행돼야 하며, 구현체 없이 enum만 켜면 "선택했는데 아무것도 안 오는" UI가 된다 |
| **D-5** | 백엔드 인스턴스가 늘어나면 WS 구독자도 중복 → 알림 중복 | ✅ **현재 단일 컨테이너 전제.** Redis 쿨다운 키가 1차 방어. 스케일아웃 시 구독자 leader election 또는 웹훅(안 B) 전환 |

---

## 8. 작업 목록

### 1단계 — 수신·판정·이력 ✅ 완료 (2026-07-13)

- ✅ `V30__add_anomaly_events.sql` (테이블명은 V31에서 단수형 `anomaly_event`로 통일)
- ✅ `domain/anomaly/entity/AnomalyEvent.java`, `repository/AnomalyEventRepository.java`
- ✅ `domain/anomaly/service/AnomalyJudge.java` — DANGER/CONFIDENCE 분기 + 종류 필터 + danger 불일치 WARN (**지속성·종류별 임계는 미채택**)
- ✅ `domain/anomaly/service/AnomalyDetectionService.java` — 판정 → 쿨다운 → sessionId→wardId 매핑 → 이력 적재
- ✅ `domain/anomaly/service/AnomalyEventCooldown.java` — Redis, **이력** 쿨다운 5분(매 프레임 broadcast 흡수)
- ✅ `domain/anomaly/client/AiLiveStreamSubscriber.java` + `AnomalySignalParser` — WS 상시 구독·재접속(지수 백오프), 등록된 `session_id`만 구독
- ✅ 설정: `anomaly.{enabled, ws-url, api-key, trigger-mode, confidence-threshold, cooldown-minutes, reconnect-*}`, `AI_API_KEY`·`AI_WS_URL`(`.env.dev` 주입)
- ✅ 테스트: `AnomalyJudgeTest`, `AnomalyDetectionServiceTest`, `AnomalySignalParserTest`

### 2단계 — 알림 발송 (이번 PR)

- `domain/anomaly/event/AnomalyDetectedEvent.java` — 이력 저장 직후 발행(`AnomalyDetectionService`)
- `domain/anomaly/service/AnomalyNotificationCooldown.java` — Redis, **알림** 쿨다운(보호자 5분 / 본인 1분). 이력 쿨다운(`AnomalyEventCooldown`)과 별개 컴포넌트
- `domain/anomaly/listener/AnomalyNotificationListener.java` — AFTER_COMMIT + `@Async("notificationExecutor")`, WebSocket(`anomaly-detected`) + 디스패처, 수신자별 try/catch 실패 격리
- `notification/dispatch/NotificationType.java` — `Policy` 도입 + `ANOMALY_DETECTED(FORCED_PUSH_PLUS_SETTINGS)` (+`NotificationDispatcher` 3분기). **기존 6타입 런타임 동작 100% 보존**(`WARD_SOS`의 SMS 폴백 포함)
- 테스트: `NotificationDispatcher`(FORCED_PUSH_PLUS_SETTINGS — FCM OFF여도 FCM 발송 / SMS OFF면 미발송 / SMS ON이면 추가 발송 / **FCM 미전달이어도 SMS 폴백 없음**), `AnomalyNotificationListener`(보호자 전원 + 본인 발송, 쿨다운 시 생략, 한 명 실패가 나머지를 막지 않음)

### 3단계(2차 PR) — 카카오 알림톡

- `KakaoAlimtalkNotificationChannel implements NotificationChannel` (빈 추가만 — 디스패처 수정 불필요)
- 선행(코드 밖): 카카오 비즈니스 채널 + Solapi 발신 프로필(`pfId`) + **템플릿 사전 심사**(`templateId`, 승인 문구 원문·변수·버튼), **Solapi 대체발송(SMS) OFF**

### AI 팀 요청 계약 (병렬)

라이브 경로에서 `danger = detectedType ∈ {fire,smoke} && confidence >= threshold`로 채울 것(구 경로 `detection_service.py:27`과 동일식). **페이로드 스키마 변경 불필요 — 기존 필드를 실제 값으로 채우기만 하면 된다.** 배포 전까지 DANGER 모드 이력은 0건이 정상이며, 공백을 메워야 하면 `anomaly.trigger-mode=CONFIDENCE`로 임시 전환한다.

---

## 9. 함께 지켜야 할 기존 불변 규칙

- **AI `danger` 필드를 무조건 신뢰해 트리거하지 말 것** — 라이브 경로에서 **현재 항상 false인 더미**다. AI가 정식화하기 전까지 이 값에 의존하는 트리거는 알림 0건(조용한 침묵)을 뜻한다. 전환은 `trigger-mode` 설정으로만(§2-3).
- ~~**이력은 항상, 알림은 쿨다운**~~ → **정정(§-1)**: 1단계는 매 프레임 broadcast로 인한 동일 행 폭주를 막기 위해 **이력 적재 자체에 쿨다운**(`AnomalyEventCooldown`, 5분)을 뒀다. 2단계 알림은 "이력이 적재된 건"에서만 발행되므로 알림 쿨다운은 그 위에 **추가로**(본인 1분) 얹는다.
- **WebSocket은 채널 추상화 밖** — 사용자 설정과 무관하게 항상 발송(`CLAUDE.md` §1).
- **도메인 로직을 `global`에 넣지 말 것** — AI WS 클라이언트도 `domain/anomaly/client`에 둔다.
- **알림 발송은 AFTER_COMMIT + `@Async`** — 발송 실패가 이력 트랜잭션을 롤백시키면 안 된다.
