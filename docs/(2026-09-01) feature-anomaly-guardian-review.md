# 보호자 이상감지 이력·오탐 응답 + 미응답 재촉 (PR ②)

> 작성 2026-09-01 / 선행: PR #236(V44, 판정 스키마) / 마이그레이션 **V45**
> 계약: `docs/(2026-08-31) api-contract-anomaly-dashboard.md` §4

---

## 1. 왜 이 기능인가

AI가 `danger=true`로 올린 감지가 **실제 위험이었는지 오탐이었는지 서버는 알 수 없다.** `confidence`는
"얼마나 불꽃처럼 보이는가"이지 "실제로 불이 났는가"가 아니다. 현장을 아는 보호자가 사후에 판정해야
관리자 대시보드의 오탐 지표가 성립한다.

문제는 **아무도 안 누르면 데이터가 안 쌓인다**는 것이다. SOS 처리결과(ACK)가 정확히 그렇게 죽었다 -
보호자 화면이 끝내 붙지 않아 `ack_status`가 전건 NULL이었고 2026-08-26에 기능을 철회했다. 그래서
이번엔 응답 API와 **재촉**을 같은 PR로 넣는다.

---

## 2. 무엇을 만들었나

### 2-1. 이력 조회 · 오탐 응답

| 엔드포인트 | 설명 |
|---|---|
| `GET /api/guardian/anomaly/history?wardId=&page=&size=` | 상황 단위 최신순. `wardId` 생략 시 ACTIVE 연결 전원 합산 |
| `POST /api/guardian/anomaly/{incidentId}/feedback` | `REAL` / `FALSE_ALARM`. 재호출 = 번복(1인 1표) |
| `GET·PUT /api/guardian/anomaly/reminder-setting` | 재촉 수신 ON/OFF |

**인가**는 SOS 이력·복약과 같다 - 요청 시점 **ACTIVE 연결**이 유일한 열람 근거이고, 연결이 해제되면
과거 이력도 즉시 비공개다. 목록은 `getActiveWardIds`, 단건은 `isActiveConnection`만 쓴다
(`getMyWards`는 PENDING이 섞여 수락 전 피보호자의 기록이 샌다). 위반은 403 + `[IDOR-ATTEMPT]` WARN,
없는 상황은 404, 관리자 확정 건은 409다.

**상태 재계산은 다수결이 아니다.** 응답이 들어올 때마다 그 상황의 응답 전체를 다시 집계해
전원 일치면 `REAL`/`FALSE_ALARM`, 갈리면 `CONFLICTED`가 된다. 한 명은 실제 화재로, 다른 한 명은
요리 연기로 봤다면 **그 불일치 자체가 관리자가 확인해야 할 정보**라, 표를 세어 한쪽으로 정하면
그 정보가 사라진다. 관리자가 확정(`resolvedBy`)한 뒤에는 재계산하지 않는다.

### 2-2. 미응답 재촉

| 항목 | 값 | 왜 |
|---|---|---|
| 방식 | 상황이 닫힌 뒤 **1시간** → 건별 FCM 1회 → 이후 **하루 1회 요약**(20:00 KST) | 건별 반복은 알림 피로, 요약만으로는 응답률이 낮다 |
| 시작 | 상황이 **닫힌 뒤**(마지막 감지 + 묶음 간격 10분) | 대응 중인 사람에게 판정을 물으면 안 된다 |
| 마감 | **3일**, 상황 **시작** 기준 | 종료 기준이면 긴 상황이 계속 살아 있고 날짜 소속과 어긋난다 |
| 야간 | 22:00~08:00 KST 억제 | 재촉만. **화재 알림 본체는 밤에도 즉시 나간다** |
| 채널 | **FCM만** | 문자는 반복 과금, 알림톡은 다발성이라 승인 템플릿 없이 금지 |
| 끄기 | `guardian_anomaly_setting.review_reminder_enabled`(기본 ON) | 이것만 못 끄면 앱 알림을 통째로 꺼서 SOS·화재가 같이 죽는다 |
| 킬 스위치 | `anomaly.review-reminder.enabled` | 재촉만 멈춘다. 이력 조회·응답은 그대로 |
| 주기 | 5분 | 기준이 "닫히고 1시간 뒤"라 분 단위 정확도가 불필요 |

**야간 억제는 건너뛰기가 아니라 미루기다** - 후보 조건(PENDING·마감 전)이 그대로 남아 08:00 이후
첫 주기에 다시 잡힌다.

**재촉하지 않는 다섯 가지**: ① 이미 응답한 보호자 ② 누군가 응답해 상황이 PENDING을 벗어난 경우
③ ACTIVE 연결이 아닌 보호자 ④ 수신 설정을 끈 보호자 ⑤ 마감을 지난 상황.

②가 중요하다. 보호자 3명 중 1명이 답하면 **나머지 2명에게는 재촉을 멈추되 응답 API는 계속 열어둔다.**
나중에 다른 보호자가 눌러 `CONFLICTED`가 되는 경로는 살아 있고, 알림량만 1/3이 된다.

---

## 3. V45 스키마

```
anomaly_review_reminder_log   UNIQUE (incident_id, guardian_id)     -- 건별 1차
anomaly_review_summary_log    UNIQUE (guardian_id, summary_date)    -- 하루 1회 요약
guardian_anomaly_setting      UNIQUE (guardian_id)                  -- 재촉 수신 여부
```

**선점 후 발송** - 기록을 먼저 커밋하고 보낸다. 순서를 뒤집으면 발송 직후 앱이 죽었을 때 다음 주기에
또 보내고, 스케줄러가 5분마다 돌기 때문에 마감(3일) 내내 같은 재촉이 반복된다. 대가로 발송 실패 시
그 회차는 유실되지만, **재촉이 두 번 가는 쪽이 한 번 빠지는 쪽보다 나쁘고** 하루 1회 요약이 두 번째
기회다(복약 2차·3차와 같은 판단).

**설계 초안에서 바꾼 것**: 초안은 `UNIQUE (incident_id, guardian_id, attempt)` 한 테이블이었으나
**두 테이블로 나눴다**. 요약은 축이 (보호자, 날짜)라 상황 단위 키에 담기지 않고, 그러고 나면 건별의
`attempt`는 항상 1인 죽은 컬럼이 되어 "여러 번 보낼 수 있다"는 잘못된 여지만 남는다.

---

## 4. 알림 인프라 변경 (공용)

"FCM만"은 기존 구조로는 표현할 수 없었다. `SETTINGS_ONLY`는 사용자가 켠 **모든** 채널로 나가서,
문자를 켠 보호자에게는 SMS도 갔다.

`NotificationType`에 **종류별 허용 채널**을 선언하고, 디스패처가 *사용자가 켠 채널 ∩ 그 종류의 허용
채널*로 설정 기반 발송을 하도록 했다.

```java
ANOMALY_REVIEW_REQUIRED(Policy.SETTINGS_ONLY, EnumSet.of(NotificationChannelType.FCM))
```

- 기본값은 **전 채널**이라 기존 종류의 동작은 그대로다(테스트로 고정).
- **강제 채널(FCM)은 이 값으로 줄어들지 않는다.** 줄일 수 있게 만들면 SOS·화재의 푸시 보장에 구멍이
  생긴다. 그래서 교집합은 *설정에서 나온 집합*에만 적용한다.
- 호출자가 넘기는 파라미터가 아니라 **타입에 박힌 선언**이라, 2026-07-27에 거부한 "디스패처 제외 채널
  파라미터"(호출자가 강제 발송을 우회할 수 있다)와는 다르다.

---

## 5. 문구

**단정하지 않는다.** "화재가 발생했습니다"가 아니라 **"화재 감지가 있었습니다. 실제 상황이었는지
확인해 주세요"**다. 실제 위험이었는지는 아직 아무도 모르고, 그걸 묻는 것이 이 알림의 목적이다.
단정하면 지난 일로 놀라게 만든다(복약 "체크되지 않았습니다"와 같은 판단).

- 건별: `9월 1일 21:03 · 김영희님 거실에서 화재 감지가 있었습니다. 실제 상황이었는지 확인해 주세요.`
- 요약: `확인이 필요한 이상감지가 3건 있습니다. 실제 상황이었는지 알려주시면 정확도를 높이는 데 도움이 됩니다.`

카메라가 삭제됐으면 위치는 "등록된 카메라"로 폴백한다(이력은 남는다).

---

## 6. 변경 파일

**신규(anomaly)**: `GuardianAnomalyController` · `GuardianAnomalyService` · `GuardianAnomalySettingService` ·
`AnomalyReviewReminderPlanner` · `AnomalyReviewReminderService` · `AnomalyReviewReminderScheduler` ·
`AnomalyReviewClock` · 엔티티 5(`AnomalyIncidentFeedback`·`AnomalyVerdict`·`AnomalyReviewReminderLog`·
`AnomalyReviewSummaryLog`·`GuardianAnomalySetting`) · 리포지토리 4 · DTO 6

**수정**: `NotificationType`·`NotificationDispatcher`(허용 채널) · `AnomalyProperties`(재촉 설정) ·
`AnomalyIncident`(`applyReviewStatus`·`isAdminResolved`) · `CameraService`·`CameraRepository`
(sessionId→위치 벌크 조회) · `ErrorCode` 3종 · `SwaggerConfig` · `application.yaml` · `V45`

---

## 7. 검증

```
./gradlew build   461건 / 실패 0     (기존 426 + 신규 35)
V45 (vkcs-linux dev DB, 트랜잭션 실행 후 ROLLBACK)
  테이블 3 · FK 4 · UNIQUE 3 · 인덱스 1 생성
  같은 (상황, 보호자)에 두 번 INSERT → uq_anomaly_review_reminder가 거부 확인
  롤백 후 원상 확인
```

테스트가 고정한 것: 인가 우회(타 피보호자 이력·응답 403, 없는 상황 404, 관리자 확정 409) /
상태 재계산 4종(PENDING·REAL·FALSE_ALARM·CONFLICTED) / 번복이 행을 쌓지 않음 /
재촉 제외 5종 / 야간 억제 경계(22:00 포함·08:00 제외·자정 넘김) / 요약 하루 1건 /
요약은 1차 재촉 이후만 / 종류별 허용 채널이 문자를 막되 강제 FCM은 못 줄임 / 설정 기본 ON·부분 수정

---

## 8. 남은 것

- **보호자 앱의 응답 버튼(FE)** - 이게 없으면 재촉을 받아도 누를 곳이 없다. 이 PR 머지와 함께 잡을 것.
- PR ③ 관리자 대시보드 - **오탐률은 응답률과 함께** 표시해야 분모가 정직하다.
- PR ④ 관리자 로그 + 정정 - `resolvedBy`를 채우는 경로가 여기서 생긴다(현재는 아무도 못 채운다).
