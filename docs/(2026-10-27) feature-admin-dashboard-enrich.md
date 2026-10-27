# 관리자 대시보드 보강 - 디자인에 맞춰 빠진 값 채우기 (2026-10-27)

> 브랜치 `feature/admin-dashboard-enrich` · 마이그레이션 없음 · JPQL 변경 없음 · 기존 응답 칸은 이름·뜻 그대로(추가만)

## 1. 배경

claude.ai/design 프로젝트의 관리자 콘솔 "대시보드"(SilverBridge-app.html `AdminDashboard`, 안전 현황·운영 현황 탭)를 현재 BE 응답과 비교했다.
비어 있던 값은 다섯 가지다. 전달 실패한 알림 수, 평균 AI 신뢰도, AI 서비스 상태, "N분 전 확인" 기준 시각, 카메라 상한이다.
디자인 칸 가운데 지금 BE가 사실대로 답할 수 없는 것은 만들지 않았다(§5).

## 2. 디자인 대조

| 디자인 칸 | BE | 이번 작업 |
|---|---|---|
| AI 서비스 상태 - 이상감지(영상 분석) | `aiConnected`(안전 현황) | `GET /ai-status`의 `anomalyDetection` |
| AI 서비스 상태 - AI 의료 챗봇 | 없음 | `GET /ai-status`의 `chatbot`(AI `/health`) |
| AI 서비스 상태 - AI 안부 말벗·정서 상태 분석 | BE에 없는 서비스 | **만들지 않음** |
| "1분 전 확인" | 없음 | `generatedAt`·`checkedAt` |
| 안전 이벤트 - 연결이 끊긴 카메라 | `disconnectedCameras` | 그대로(설명 문구는 §5) |
| 안전 이벤트 - 보호자 미연결 / 카메라 미등록 / 7일 방치 요청 | 있음 | 그대로 |
| 안전 이벤트 - 전달 실패한 알림 | 없음 | `safetyEvents.todayFailedNotifications` |
| 오늘 이상감지 - 총계·유형 도넛 | `total`·`byType` | 그대로 |
| 오늘 이상감지 - 평균 AI 신뢰도 | 없음 | `todayAnomaly.aiConfidence` |
| 오늘 이상감지 - 위험 판정·오탐 판정 | `review` 4값 | 그대로(동수는 §5) |
| 서비스 이용 현황 - 게임·제휴 병원 | 대시보드 밖 | 이번 범위 아님(§5) |
| 운영 - 전체 회원·연결 대기·미처리 문의·회원 구성·7일 가입·처리 대기 | 있음 | 그대로 |
| 운영 - 카메라 등록 "29 / 34대" | `registered`·`wards` | `cameras.maxTotal` 추가(단위 분리, §5) |

## 3. 결정 (2026-10-27 사용자 결정)

1. **AI 상태는 별도 경로**(`/ai-status`). AI 호출이 최대 5초 걸릴 수 있어서 안전 현황 응답이 같이 늦어지지 않게 했다.
2. **챗봇 상태는 AI `/health` 응답 기준**이다. AI에 챗봇 전용 상태 경로가 없다. 그래서 이 칸은 "챗봇을 서빙하는 AI 서버 프로세스가 응답하는가"까지만 답하고, 모델 응답이 성공하는지는 보장하지 않는다. AI 서버는 바꾸지 않았다.
3. **이상감지 구독이 끊기면 `DISCONNECTED`**를 내린다. 우리가 확실히 아는 사실이기 때문이다. `null`(확인 불가)은 확인 자체가 실패했을 때만 쓴다.
4. **평균 AI 신뢰도는 판정 난 상황(위험+오탐)만으로 계산**한다. 이상감지 로그 요약과 같은 계산을 그대로 쓰기 때문에 판정 칸과 분모가 같다. 디자인 주석은 "오늘 감지 건 전체 평균"이었지만 채택하지 않았다.

## 4. API 계약 (관리자 웹 전달용)

공통: ADMIN 전용(비로그인 401, 보호자·피보호자 403), 서버 캐시 없음, 조회라 감사 로그 없음, 날짜는 KST, 시각은 오프셋 포함 ISO 8601(`2026-10-27T14:05:00+09:00`).

### 4-1. `GET /api/admin/dashboard/safety` - 추가 칸

| 칸 | 타입 | 뜻 |
|---|---|---|
| `safetyEvents.todayFailedNotifications` | number | 오늘(KST 00:00부터) 전달 실패(FAILED) 알림 수. 수신자 1명이 1건이다. 알림 이력 요약 `period=TODAY`의 `failed`와 **같은 값**이다. 발송 안 함(NOT_SENT: 정지 계정 차단·수신자가 채널을 꺼 둔 경우)과 문자 대체로 전달된 건은 세지 않는다 |
| `todayAnomaly.aiConfidence.average` | number \| **null** | 판정 난 상황(위험+오탐)의 AI confidence 평균(0.0~1.0). 판정 난 상황이 0건이면 null. 화면에는 ×100 해서 % 로 표시한다. 이상감지 로그 요약 `period=TODAY`의 값과 같다 |
| `todayAnomaly.aiConfidence.basis` | number | 분모 = 위험 + 오탐 상황 수. 판정 칸의 "위험 판정 %"는 `review.real / basis`, "오탐 판정 %"는 `review.falseAlarm / basis`로 계산한다 |
| `generatedAt` | string | 응답을 만든 시각. "N분 전 확인" 표시에 쓴다 |

### 4-2. `GET /api/admin/dashboard/operation` - 추가 칸

| 칸 | 타입 | 뜻 |
|---|---|---|
| `cameras.maxTotal` | number \| **null** | 서비스 전체 카메라 등록 상한(`camera.max-total`, 현재 3). 상한이 꺼져 있으면 null |
| `generatedAt` | string | 응답을 만든 시각 |

카메라 칸은 단위가 다르다. `registered`는 대수, `wards`는 카메라를 등록한 피보호자 수(명), `maxTotal`은 대수다.
"등록 대수 / 상한"은 `registered / maxTotal`로 쓴다. 디자인의 "29 / 34대"처럼 대수와 사람 수를 한 분수로 섞지 말 것.

### 4-3. `GET /api/admin/dashboard/ai-status` (신규)

```json
{
  "data": {
    "anomalyDetection": { "status": "OK", "checkedAt": "2026-10-27T14:05:00+09:00" },
    "chatbot":          { "status": null, "checkedAt": "2026-10-27T14:05:00+09:00" },
    "generatedAt": "2026-10-27T14:05:00+09:00"
  }
}
```

| 칸 | 값 | 화면 표시 |
|---|---|---|
| `anomalyDetection.status` | `OK` / `DISCONNECTED` | 정상 / 연결 끊김. 끊기면 이상감지 알림이 나가지 않는 상태다. 안전 현황 `aiConnected`와 같은 값이다 |
| `chatbot.status` | `OK` / **null** | 정상 / 확인 불가. null이 되는 경우는 시간 초과(5초), 통신 오류, AI 5xx, 형식 이상, 설정 없음이다. 원인을 알 수 없어 DISCONNECTED로 단정하지 않으므로 이 칸에는 DISCONNECTED가 오지 않는다 |
| `*.checkedAt` | string | 그 항목을 확인한 시각 |

- AI가 응답하지 않으면 이 경로는 최대 약 5초 걸린다. 안전 현황과 따로 부르고, 폴링은 60초 이상을 권장한다.
- "AI 안부 말벗 · 정서 상태 분석" 행은 BE에 없는 서비스라 값이 없다. 화면에서 행을 빼거나 "미연동"으로 표시할 것("정상"으로 표시하면 안 된다).

## 5. 디자인과 다르게 처리한 칸

| 디자인 | 처리 | 이유 |
|---|---|---|
| "AI 안부 말벗 · 정서 상태 분석: 정상" | 만들지 않음 | BE에 없는 서비스다. 확인하지 않은 것을 "정상"으로 보이면 거짓이 된다 |
| 연결이 끊긴 카메라: "24시간 넘게 꺼져 있어…" | 값은 지금 끊긴 대수 그대로, 문구는 FE가 고칠 것 | 끊긴 시각을 저장하지 않아 "24시간 넘게"는 알 수 없다. 권장 문구: "지금 영상이 들어오지 않아 감지가 되지 않습니다" |
| 판정에 동수(conflicted)·미판정 칸 없음 | BE는 이미 `review`에 4값 모두 제공 | 보고만 한다. 위험+오탐만 보이면 동수 건이 화면에서 사라지므로 FE에 동수·미판정 표시를 권장한다 |
| 디자인의 "위험 판정" 계산(`danger=true` 건수) | 따르지 않음 | `danger`는 AI 판정이지 보호자 판정이 아니다. BE `review.real`(보호자 다수결)을 쓴다 |
| 카메라 등록 "29 / 34대" | `maxTotal` 추가, 단위 분리 | §4-2 |
| 서비스 이용 현황(게임 참여·이탈, 제휴 병원) | 이번 범위 아님 | 제휴 병원은 `GET /api/admin/hospital/summary`가 이미 있다. 게임 데이터는 AI 서버에 있어 별도 작업이 필요하다 |
| "안전 현황" 탭 옆 주황 ● | 값 추가 없음 | `/ai-status`의 `anomalyDetection.status=DISCONNECTED`로 판단할 수 있다 |

## 6. 구현

- `AdminDashboardService`
  - `todayFailedNotifications`는 `AdminNotificationService.getSummary(null, TODAY, null).failed()`를 그대로 쓴다.
  - `aiConfidence`는 `AdminAnomalyService.getSummary(TODAY, null, null).aiConfidence()`를 그대로 쓴다. 기준은 첫 감지 시각(KST)이라 `todayAnomaly`와 같다.
  - 계산을 복제하지 않았기 때문에 각 화면 숫자와 어긋나지 않는다.
  - `maxTotal`은 `camera.max-total`이 0 이하이면 null이다(`AdminCameraService` 요약과 같다).
- `AdminAiStatusService`(트랜잭션 없음, DB 조회 없음)와 `AiServerHealthClient`(`admin/client`)
  - 확인 대상은 챗 중계와 같은 `chat.relay.ai-base-url`이고, 거기에 `GET /health`를 보낸다.
  - 연결 3초, 호출 전체 5초 제한을 둔다. 마감은 전용 데몬 스레드 1개가 연결을 끊는다(빈으로 등록하지 않음).
  - 키는 `X-API-Key` 헤더로만 보내고, 리다이렉트는 따라가지 않으며, 본문은 64KB까지만 받는다.
  - 정상은 `data.status == "ok"`인 경우뿐이다. 앞단이 HTML 200을 돌려줘도 정상으로 보지 않는다.
  - 로그 `[ADMIN-AI-STATUS]`에는 결과 코드·상태 코드·예외 클래스명만 남긴다(`LogRawExceptionGuardTest` 대상에 추가).
- 응답 DTO는 새 칸을 추가만 했다. `AdminAnomalySummaryResponse.AiConfidence` 타입을 재사용해 두 화면의 스키마가 같다.

## 7. 테스트

- `AdminDashboardServiceTest` 「디자인 보강」 6건
  - 알림 이력 요약을 TODAY·전체 탭으로 불러 `failed`만 쓰는지(NOT_SENT 7건을 넣어도 2건)
  - 이상감지 요약을 TODAY로 불러 그대로 쓰는지
  - 판정 0건이면 null
  - 카메라 단위 분리, 상한 0이면 null
  - `generatedAt`이 +09:00인지
- `AiServerHealthClientTest` 8건(실제 로컬 HTTP 서버)
  - 정상(키는 헤더), 5xx, HTML 200, status≠ok
  - 시간 초과(마감 500ms → 2초 안에 false)
  - 리다이렉트를 따라가지 않음(대상 경로 0회 호출)
  - 키 없음이면 AI를 호출하지 않음, 닫힌 포트
- `AdminAiStatusServiceTest` 3건: 끊김이면 DISCONNECTED, 챗봇 확인 실패면 null, checkedAt이 KST인지
- `AdminDashboardControllerHttpTest` 3건(실제 직렬화): 기존 칸 이름 하위 호환, 새 칸, null 칸이 빠지지 않는지, 오프셋 포함 시각
- `AdminDashboardControllerSecurityTest` 6건: 세 경로 모두 ADMIN만 허용, GUARDIAN·WARD·익명 거부
- 원래 계산의 경계(NOT_SENT 분리·KST 자정·basis 0이면 null)는 기존 `AdminNotificationServiceTest`·`AdminNotificationFilterTest`·`AdminAnomalyServiceTest`가 이미 고정하고 있다
