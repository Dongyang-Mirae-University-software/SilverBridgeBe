# 이상감지 판정 + 관리자 대시보드 - API 계약

> 작성 2026-08-31 / 대상 독자: **프론트엔드 담당자** (백엔드 구현과 병렬 착수용)
> 근거: 관리자 대시보드 프로토타입(2026-08-27 수정본), 설계 합의 세션(2026-08-27~31)
> 상태: **확정 (2026-09-01 재확인)** - 이 문서의 경로·스키마는 구현 전 확정본이며, 변경 시 이 문서를 먼저 갱신한다.
> 이력: 2026-08-31 회의 전 보류로 PR ①(V42)을 되돌렸다가(V43 DROP), 09-01 대시보드를 이 설계 그대로
> 가기로 확정해 **V44로 다시 세웠다**. V42·V43은 두 서버에 적용 이력이 있어 파일을 지우거나 고칠 수 없다 -
> 되돌리기도 되살리기도 앞으로 나아가는 마이그레이션으로 한다.

---

## 0. 왜 이 기능이 필요한가 (한 문단)

AI가 `danger=true`로 올린 감지가 **실제 위험이었는지 오탐이었는지 서버는 알 수 없다.** confidence 숫자는
"얼마나 불꽃처럼 보이는가"이지 "실제로 불이 났는가"가 아니기 때문이다. 그래서 **현장을 아는 보호자가
사후에 판정**하고, 관리자는 보호자들의 응답이 엇갈린 건만 2차 확인·정정한다. 관리자 대시보드의 오탐 지표는
이 판정 데이터 위에서만 성립한다.

---

## 1. 공통 규약

| 항목 | 내용 |
|---|---|
| 인증 | `Authorization: Bearer {accessToken}` (전 엔드포인트 필수) |
| 성공 응답 | `{ "success": true, "data": ... }` (`ApiResponse<T>`) |
| 실패 응답 | `{ "success": false, "message": "..." }` |
| 페이징 | `data`가 `PageResponse<T>` - `content`, `page`, `size`, `totalElements`, `totalPages`, `last` |
| 페이지 크기 | `size` 최대 50 (초과 요청은 50으로 처리) - SOS 이력과 동일 |
| 시각 | 모든 시각은 ISO-8601 오프셋 포함(`2026-08-31T21:03:11+09:00`). **날짜 경계 판정은 KST** |
| 권한 | 클래스 레벨 `@PreAuthorize` - 보호자 API는 `GUARDIAN`, 관리자 API는 `ADMIN`만. 그 외 403 |

### 인가 규칙 (보호자 API)

**요청 시점에 ACTIVE 연결인 피보호자**의 데이터만 조회·판정할 수 있다. 연결이 해제되면 과거 이력도 즉시
비공개다. 위반 시 **403 + 서버에 `[IDOR-ATTEMPT]` WARN**. (SOS 이력·복약과 동일한 기존 정책)

### 에러 코드

| 코드 | 상태 | 메시지 | 발생 |
|---|---|---|---|
| `ANOMALY_NOT_AUTHORIZED` | 403 | 연결된 피보호자의 이상감지 기록만 볼 수 있습니다. | 보호자가 남의 피보호자/상황에 접근 |
| `ANOMALY_INCIDENT_NOT_FOUND` | 404 | 이상감지 기록을 찾을 수 없습니다. | 없는 incidentId |
| `ANOMALY_ALREADY_RESOLVED` | 409 | 관리자가 확정한 기록이라 응답을 변경할 수 없습니다. | 관리자 정정 후 보호자가 판정 시도 |

---

## 2. 도메인 모델 - "상황(incident)"

같은 카메라에서 화재가 3분간 이어지면 이력(`anomaly_event`)은 쿨다운 1분 간격으로 3건이 쌓인다. 이걸
보호자에게 3번 판정하게 하면 안 되므로, **연속 감지를 하나의 "상황"으로 묶어** 판정·통계의 단위로 삼는다.

**묶음 규칙 (확정)**
- 같은 `(wardId, sessionId, detectedType)`이고, 직전 상황의 마지막 감지로부터 **10분 이내**면 같은 상황으로 승계
- **상황은 KST 자정을 넘기지 않는다** - 통계가 일자별이라 23:55~00:10 상황의 소속 날짜가 모호해지기 때문
  (복약 유예 창의 "자정에서 자른다"와 같은 판단). 드물게 한 사건이 두 건으로 기록된다.
- **모든 통계의 단위는 상황 수**다(이력 행 수가 아니다). 관리자·보호자 화면 모두 동일하다.

**판정 상태 `reviewStatus`**

| 값 | 의미 | 화면 표기(권장) |
|---|---|---|
| `PENDING` | 아무도 응답하지 않음(기본값) | 확인 필요 |
| `REAL` | 응답한 보호자 전원이 "실제 위험" | 실제 위험 |
| `FALSE_ALARM` | 응답한 보호자 전원이 "오탐" | 오탐 |
| `CONFLICTED` | 보호자끼리 엇갈림 | 관리자 확인 대기 |

**전이 규칙**
- 보호자 응답이 들어올 때마다 **그 상황의 응답 전체를 다시 집계해 상태를 재계산**한다(번복 포함).
- **관리자가 정정하면 그 상태가 최종**이다. 이후 보호자 응답으로 덮이지 않는다(`resolvedBy`가 채워지면 재계산 스킵).
  이 규칙이 없으면 관리자 확정이 조용히 뒤집힌다.
- 보호자 응답 원본은 관리자 정정 후에도 **지우지 않는다**.
- 판정은 **이미 나간 알림을 되돌리지 않는다** - 오탐으로 표시해도 정정 알림은 발송하지 않는다.

---

## 3. PR ① 이상감지 판정 스키마 (API 없음 / FE 영향 = 알림 payload 1개)

서버 내부 스키마 작업이라 새 엔드포인트가 없다. **FE가 알아야 할 변경은 하나뿐이다.**

### 알림 payload에 `incidentId` 추가

FCM `data` / WebSocket `anomaly-detected` 페이로드에 **`incidentId`가 추가**된다. 보호자가 알림에서 바로
"오탐이었어요"를 누르려면 상황 식별자가 필요하기 때문이다.

```jsonc
{
  "type": "ANOMALY_DETECTED",     // 기존 - 변경 없음
  "wardId": "A1B2C3",
  "wardName": "김영희",
  "location": "거실",
  "sessionId": "...",
  "detectedType": "FIRE",
  "detectedTypeLabel": "화재",
  "detectedAt": "2026-08-31 21:03",
  "anomalyEventId": "1024",       // 기존 - 이력 행 ID
  "incidentId": "37"              // 신규 - 판정 단위(상황) ID
}
```

기존 키는 **이름·의미 모두 그대로**다. `incidentId`만 늘어나므로 기존 프론트는 깨지지 않는다.

---

## 4. PR ② 보호자 - 이상감지 이력 조회 + 오탐 응답

### 4-1. 이력 목록

```
GET /api/guardian/anomaly/history?wardId={id}&page=0&size=20
```

- `wardId` **지정**: 그 피보호자만 (ACTIVE 연결이 아니면 403)
- `wardId` **생략**: ACTIVE 연결된 피보호자 전원을 합쳐 최신순 (연결이 없으면 빈 페이지)

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "incidentId": 37,
        "wardId": "A1B2C3",
        "wardName": "김영희",
        "cameraLabel": "거실",          // 카메라 삭제 시 null (이력은 남는다)
        "detectedType": "FIRE",          // FIRE | SMOKE
        "detectedTypeLabel": "화재",
        "startedAt": "2026-08-31T21:03:11+09:00",
        "lastDetectedAt": "2026-08-31T21:06:02+09:00",
        "eventCount": 4,                 // 이 상황에 묶인 감지 횟수
        "maxConfidence": 0.87,
        "reviewStatus": "PENDING",       // PENDING | REAL | FALSE_ALARM | CONFLICTED
        "myVerdict": null,               // 내가 낸 응답 (REAL | FALSE_ALARM | null)
        "resolvedByAdmin": false         // true면 관리자 확정 - 응답 버튼 비활성
      }
    ],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "last": true
  }
}
```

### 4-2. 오탐 응답 (보호자 전용)

```
POST /api/guardian/anomaly/{incidentId}/feedback
{ "verdict": "FALSE_ALARM" }        // REAL | FALSE_ALARM
```

- **재호출 = 번복**(UPDATE). 보호자 1인당 1표다.
- 응답 후 그 상황의 `reviewStatus`가 재계산된 결과를 그대로 돌려준다.

```jsonc
{ "success": true,
  "data": { "incidentId": 37, "reviewStatus": "FALSE_ALARM", "myVerdict": "FALSE_ALARM" } }
```

| 상태 | 사유 |
|---|---|
| 403 | 연결되지 않은 피보호자의 상황 (`ANOMALY_NOT_AUTHORIZED`) |
| 404 | 없는 incidentId (`ANOMALY_INCIDENT_NOT_FOUND`) |
| 409 | 관리자가 이미 확정 (`ANOMALY_ALREADY_RESOLVED`) |

> **판정은 보호자만 한다.** 피보호자 본인·관리자용 1차 판정 API는 만들지 않는다(설계 확정 사항).

---

### 4-3. 미응답 재촉 (2026-09-01 결정)

판정 데이터는 관리자 운영 자료라 **응답률이 곧 자료의 질**이다. 그렇다고 답할 때까지 계속 쏘면 보호자가
앱 알림을 통째로 꺼버리고, **그 순간 그 집의 SOS·화재 알림까지 함께 죽는다**(복약 미복용 요약에서 이미
내린 판단 - 불변 규칙 ⑨). 그래서 강제가 아니라 **절제된 재촉**으로 간다.

| 항목 | 확정 |
|---|---|
| 방식 | 하이브리드 - 상황이 닫힌 뒤 **1시간** 경과 + 여전히 PENDING이면 건별 FCM 1회, 그 뒤로는 **하루 1회 미응답 요약** |
| 시작 시점 | **상황이 닫힌 뒤**(마지막 감지 + `incident-merge-minutes`). 진행 중인 화재에 "오탐인가요?"를 물으면 안 된다 |
| 마감 | **3일**. 이후 재촉 중단, PENDING 확정. 무한 재촉을 막는 안전판이다 |
| 야간 억제 | 22:00~08:00 KST는 다음 아침으로 미룬다. **화재 알림 본체는 그대로 밤에 나간다** - 재촉만 미룬다 |
| 다중 보호자 | 한 명이라도 응답해 PENDING을 벗어나면 **나머지 재촉은 중단**한다. 다만 응답 API는 계속 열어둔다 |
| 채널 | **FCM만**. 문자는 반복 과금이라 부적합, 알림톡은 다발성이라 승인 템플릿 없이 금지(불변 규칙 ⑤) |
| 정책 | `ANOMALY_REVIEW_REQUIRED(SETTINGS_ONLY)` |
| 끄기 | 보호자별 ON/OFF 설정을 둔다(`guardian_medication_setting`의 전례) |
| 중복 방지 | **선점 후 발송** + `UNIQUE (incident_id, guardian_id, attempt)`(불변 규칙 ④) |
| 킬 스위치 | `anomaly.review-reminder.enabled` - 복약 2차·3차 스위치와 독립 |

**바꾸지 말 것**

- **강제 채널로 승격시키지 말 것.** `FORCED_PUSH_*`로 올리면 보호자가 이 알림만 끌 수 없게 되고,
  그때 앱 알림을 통째로 꺼서 필수 알림(SOS·이상감지)이 같이 죽는다. 재촉은 운영 편의지 생명 알림이 아니다.
- **한 명 응답 시 나머지 재촉 중단**을 "전원 응답을 받아야 엇갈림을 알 수 있다"는 이유로 뒤집지 말 것.
  응답 API는 열려 있으므로 나중에 다른 보호자가 눌러 `CONFLICTED`가 되는 경로는 그대로 살아 있다.
  우리가 먼저 찔러 캐내지 않을 뿐이다(알림량 1/3).
- **재촉 시작을 상황이 닫히기 전으로 당기지 말 것.** 대응 중인 사람에게 판정을 묻는 꼴이 된다.

> ⚠️ 재촉은 **PR ②(응답 API)와 같은 PR로** 간다. 누를 화면이 없는데 재촉만 보내면 받은 사람이 갈 곳이 없다.

---

## 5. PR ③ 관리자 대시보드 집계

탭이 둘이라 **엔드포인트도 둘**이다(한쪽만 열어도 다른 쪽 쿼리가 돌지 않게).

### 5-1. 안전 현황

```
GET /api/admin/dashboard/safety
```

```jsonc
{
  "success": true,
  "data": {
    "aiConnected": true,              // AI WebSocket 구독 연결 상태
    "subscribedSessions": 12,         // 현재 구독 중인 세션 수
    "totalCameras": 29,               // 등록 카메라 대수
    "streamingCameras": 27,
    "safetyEvents": {                 // 프로토타입 4개 항목 (CONFLICTED는 넣지 않는다)
      "disconnectedCameras": 2,       // 현재 스트리밍이 잡히지 않는 카메라
      "wardsWithoutGuardian": 3,      // ACTIVE 연결이 하나도 없는 피보호자
      "wardsWithoutCamera": 5,
      "stalePendingConnections": 1    // 오래 방치된 연결 요청
    },
    "todayAnomaly": {                 // KST 오늘, 단위 = 상황 수
      "total": 4,
      "byType": [ { "detectedType": "FIRE", "count": 3 },
                  { "detectedType": "SMOKE", "count": 1 } ],
      "review": { "pending": 2, "real": 1, "falseAlarm": 1, "conflicted": 0 }
    }
  }
}
```

- **낙상·흉기는 응답에 넣지 않는다** - AI 미탑재라 항상 0이고, 0을 보여주면 "안전하다"로 오독된다.
- `byType`은 **실제로 집계된 유형만** 담는다(0건 유형은 항목 자체가 없다).
- AI 미연결이면 `aiConnected=false`, `subscribedSessions=0`이고 나머지 집계는 정상 응답한다(500 아님).
- **CONFLICTED 건은 대시보드 안전 이벤트에 넣지 않는다**(결정 2026-08-31). 관리자는 이상감지 로그 페이지의
  상태 필터(`status=CONFLICTED`)로 본다. `todayAnomaly.review.conflicted`로 오늘치 건수는 확인된다.
- **오탐률은 응답률과 함께 표시한다**(결정 2026-09-01). 재촉을 해도 응답률은 100%가 되지 않으므로,
  오탐 건수만 띄우면 분모가 거짓이 된다. "오탐 6건"이 아니라 **"응답 9건 중 오탐 6건 (전체 15건, 응답률 60%)"**
  형태여야 관리자가 그 숫자를 믿을 수 있다. `review`의 네 값으로 프론트에서 계산 가능하다
  (응답 = total - pending). SOS 처리결과(ACK)가 전건 NULL인 채 "무응답 100%"로 읽힐 뻔한 것과 같은 함정이다.

### 5-2. 운영 현황

```
GET /api/admin/dashboard/operation
```

```jsonc
{
  "success": true,
  "data": {
    "totalUsers": 13,                 // ADMIN 제외
    "newUsersToday": 2,
    "memberComposition": { "wards": 6, "guardians": 7 },
    "cameras": { "registered": 29, "wards": 6 },
    "pendingConnections": 4,
    "unansweredInquiries": { "count": 2, "longestWaitingHours": 31 },
    "signupTrend": [ { "date": "2026-08-25", "count": 1 } ],   // 최근 7일, KST
    "pendingItems": { "connectionRequests": 4, "todayInquiries": 1, "announcementDrafts": 3 }
  }
}
```

- 데이터가 0건이어도 **키는 항상 존재**한다(0 / 빈 배열). 프론트에서 존재 여부를 분기할 필요가 없다.
- 폴링은 **30초 이상** 권장. 서버 캐시는 두지 않는다(집계가 카운트 쿼리 수준이고, 캐시를 넣으면
  "방금 처리했는데 안 줄어든다"가 생긴다).

---

## 6. PR ④ 관리자 - 이상감지 로그 + 정정

```
GET /api/admin/anomaly?status=CONFLICTED&wardId=&page=0&size=20
```

- `status` 생략 = 전체. 값은 `PENDING | REAL | FALSE_ALARM | CONFLICTED`
- 목록 항목은 4-1과 같되 **보호자 응답 내역이 붙는다**:

```jsonc
{
  "incidentId": 37, "wardId": "A1B2C3", "wardName": "김영희", "cameraLabel": "거실",
  "detectedType": "FIRE", "startedAt": "...", "lastDetectedAt": "...",
  "eventCount": 4, "maxConfidence": 0.87, "reviewStatus": "CONFLICTED",
  "feedbacks": [ { "guardianId": "X9Y8Z7", "guardianName": "김철수",
                   "verdict": "REAL", "respondedAt": "..." } ],
  "resolvedBy": null, "resolvedAt": null, "reviewNote": null
}
```

```
PATCH /api/admin/anomaly/{incidentId}/review
{ "reviewStatus": "FALSE_ALARM", "note": "보호자 통화 확인 - 요리 연기" }
```

- 지정 가능한 값은 `REAL | FALSE_ALARM`뿐이다(`PENDING`·`CONFLICTED`로 되돌리지 않는다).
- `note`는 선택, 200자.
- 정정은 **상태만 바꾸고 보호자 응답 원본은 지우지 않는다.** 감사로그(`admin_audit_log`)에 남는다.

---

## 7. (별건) 보호자 대시보드 통계 - 계약만

프로토타입의 보호자 웹 "이상감지 추이 / 카테고리 분포"용이다. **이번 범위 밖**이고 계약만 확정해 둔다.

```
GET /api/guardian/ward/{wardId}/anomaly/statistics?period=WEEK   // WEEK | MONTH
```

```jsonc
{ "success": true,
  "data": {
    "period": "WEEK",
    "trend": [ { "date": "2026-08-25", "count": 0 } ],            // 0건 날짜도 채워서 준다(그래프 X축)
    "byType": [ { "detectedType": "FIRE", "count": 2 } ],
    "total": 2
  } }
```

- `trend`는 **빈 날짜를 0으로 채워** 보낸다(프론트에서 날짜 축을 다시 만들지 않게).
- 정서 상태(emotion)는 미개발 영역이라 계약에 포함하지 않는다.

---

## 8. 구현·머지 순서

| PR | 내용 | 마이그레이션 | FE 영향 |
|---|---|---|---|
| ① | 판정 스키마 + 상황 묶음 + 알림 payload `incidentId` | **V44** | 알림 키 1개 추가(하위호환) |
| ② | 보호자 이력 조회 + 오탐 응답 **+ 미응답 재촉(§4-3)** | **V45**(재촉 로그·보호자 설정) | 화면 신규 |
| ③ | 관리자 대시보드 집계 | 없음 | 화면 신규 |
| ④ | 관리자 로그 목록 + 정정 | **V46**(감사로그 CHECK) | 화면 신규 |

> 마이그레이션 번호가 V42→V44로 밀린 이유는 머리말 참조(V42 추가 → V43 되돌리기가 이미 적용돼 있다).

> ⚠️ **PR ② 머지 시점에 보호자 앱의 오탐 응답 버튼 작업을 함께 잡을 것.** API만 머지되고 누르는 화면이
> 오래 없으면 SOS 처리결과(ACK)와 같은 결말(전건 NULL → 기능 철회)이 된다.
