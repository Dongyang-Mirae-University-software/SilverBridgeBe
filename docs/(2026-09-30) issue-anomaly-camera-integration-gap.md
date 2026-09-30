# 이상감지 통합 경로 - FE가 백엔드 카메라 등록을 거치지 않는다 (2026-09-30)

> **상태: FE 반영 대기** - 백엔드 코드 변경 없음.
> 근거: `docs/audit-index.md` "이상감지 통합 경로" ❌ 행을 확인하다가 발견했다.
>
> **정정 (2026-09-30 같은 날)**: 처음에는 이 문서를 "새로 합의할 선택지"로 썼으나, 같은 흐름(피보호자가 `POST /api/ward/camera`로 등록 → 발급 `sessionId`로 송출)은 **이미 2026-07-31에 FE에 안내된 설계**였다(Notion `DMU / 프론트엔드 전달 내용 / 📹 이상감지 카메라 소유권 — 카메라 등록 UX & 보호자 허용 목록 연동 안내`). 새 결정이 필요한 것이 아니라 **안내된 작업이 FE에 반영되지 않은 상태**다. 아래 "선택지" 절은 그 설계를 왜 유지하는지의 근거로만 남긴다.
>
> **FE 전달**: Notion `DMU / 프론트엔드 전달 내용 / 백엔드 점검 결과 FE 확인 요청 (2026-09-30)` - 1순위로 요청, 송출 기기의 로그인 계정(피보호자인지) 답변도 요청했다.

## 한 줄 요약

지금 구조로는 **실제 카메라 송출에서 화재가 감지돼도 보호자에게 알림이 한 건도 가지 않는다.**
FE가 카메라를 **AI 서버에 직접 등록하고 직접 송출**하고 있어서 백엔드 `camera` 테이블이 비어 있고,
백엔드는 **자기 `camera` 테이블에 등록된 `session_id`만** AI WS에서 구독하기 때문이다.

## 확인한 사실 (2026-09-30)

### 백엔드 - 등록된 세션만 구독한다

- `AiLiveStreamSubscriber`: AI WS 연결 후 `{"action":"list"}` → 받은 `live_streams` 중 **`CameraService.findOwnerBySessionId()`로 찾아지는 세션만** `subscribe`. 미등록 세션은 `[ANOMALY] 미등록 세션 — 구독하지 않음`(DEBUG)으로 건너뛴다.
  - 의도된 설계다(2026-07-14): 감지 신호를 **어느 피보호자의 어느 방**인지로 바꿀 수 있어야 알림 수신자(ACTIVE 보호자 전원 + 본인)를 정할 수 있다. 주인 없는 세션의 화재는 보낼 곳이 없다.
- 카메라 등록 `POST /api/ward/camera`(피보호자 전용)는 **백엔드가 `sessionId`를 발급**한다(`CameraIdentifierFactory.newSessionId(wardId)`). 기기는 이 값으로 AI에 송출해야 한다.

### FE - AI 서버만 호출한다 (SilverBridgeFe `dc04d2d`, 2026-09-28 기준)

| 동작 | 호출 | 비고 |
|---|---|---|
| 카메라 등록 | `POST /api/streams/v1/cameras` (AI 서버 프록시) | `src/service/api/streamSession.ts` `registerCamera`·`ensureCameraRegistered` |
| 송출 시작 | `POST /api/streams/v1/stream-sessions` `{sessionId, cameraIdentifier, deviceType:'web'}` | `sessionId`는 **사용자가 입력한 세션 이름**(`Stream.tsx` `liveSessionName`) |
| 프레임 업로드 | `POST /api/streams/v1/stream-sessions/{sessionId}/frame` | |
| 백엔드 카메라 API | **호출 없음** | `/api/ward/camera` 사용처 0곳 |

- 송출 화면은 `(guardian)/guardian/stream` 경로에 있다 - 백엔드 카메라 등록은 **피보호자 전용**이라 역할도 어긋난다.

### 결과

- 백엔드 `camera` 행이 없으니(2026-09-10 대장 기준 gosky 0행) 어떤 송출도 구독되지 않는다 → 감지 → `anomaly_event` 적재 → 알림으로 이어지지 않는다.
- 단위·통합 테스트는 각 구간을 따로 검증해 통과한다. **끝에서 끝까지 이어 본 적이 없어서** 드러나지 않았다.

## 설계 근거 (2026-07 확정안 = A, 대안은 거부)

| 안 | 내용 | FE | BE | AI | 평가 |
|---|---|---|---|---|---|
| **A (권장)** | 피보호자 화면에서 `POST /api/ward/camera`로 등록 → 응답의 `sessionId`로 AI 송출 시작 | 송출 흐름 변경: 세션 이름 입력 대신 백엔드 발급값 사용 | 없음 | 없음 | 백엔드 설계(2026-07-03) 그대로. 소유자·설치 위치를 백엔드가 알게 되어 알림·이력·관리자 화면이 모두 동작 |
| B | FE가 AI에 등록할 때 백엔드에도 같은 `sessionId`로 등록(이중 등록) | 두 곳 호출 | `sessionId`를 요청으로 받는 API 추가 | 없음 | 두 저장소가 어긋날 수 있다(한쪽만 성공). 세션 ID를 클라이언트가 정하면 남의 세션을 자기 것으로 등록하는 경로가 생긴다 → 비권장 |
| C | 백엔드가 AI 카메라 목록(`targetUserId`)을 신뢰해 소유자를 매핑 | 등록 시 `targetUserId`를 정확히 채움 | 구독 로직 변경 | 목록 API 계약 확정 | AI 서버가 인가 원본이 된다. 우리 연결 인가(ACTIVE)와 별개로 누구나 `targetUserId`를 적을 수 있어 **남의 보호자에게 알림을 보내는 경로**가 된다 → 비권장 |

**A가 2026-07에 확정·안내된 설계다.** 백엔드는 이미 준비돼 있고, 바뀌는 곳은 FE 송출 화면 하나다. B·C는 위 이유로 거부안이다.

## FE 반영 시 확인할 것

1. **송출 화면의 역할** - 지금은 보호자 경로(`/guardian/stream`)다. 카메라는 피보호자 자산(2026-09-10 M-3 결정)이므로 등록은 피보호자 계정으로 한다. 송출 기기(iPad 등)가 피보호자 계정으로 로그인하는지, 등록만 피보호자가 하고 송출은 별도 기기가 하는지 정한다.
2. **세션 ID 입력란 제거** - 송출 시작 시 `GET /api/ward/camera`로 받은 `sessionId`를 쓴다. 사용자가 이름을 입력하면 백엔드 등록값과 달라져 구독되지 않는다.
3. **AI 쪽 카메라 등록(`/v1/cameras`)을 계속 할지** - AI가 송출 세션 생성에 카메라 등록을 요구하면 `cameraIdentifier`에 백엔드 `deviceId`를 쓰는 식으로 맞춘다(AI 팀 확인).
4. **검증 방법** - 연결 후 `docker logs dmu-dev-api | grep "\[ANOMALY\] 세션 구독"`에 그 `sessionId`가 찍히면 구독 성공. 이후 화재 영상으로 `anomaly_event` 1행·보호자 FCM 1건을 확인한다(AI `danger` 배포 여부는 별개 - `[ANOMALY-DANGER-MISMATCH]` 참고).

## 대장 반영

- `docs/audit-index.md` "이상감지 통합 경로": ❌ 유지, 메모 "FE 반영 대기(2026-07-31 안내 완료)".
- 다음 점검 트리거 "FE 카메라 연동 완료 시"는 그대로 유효하다.
