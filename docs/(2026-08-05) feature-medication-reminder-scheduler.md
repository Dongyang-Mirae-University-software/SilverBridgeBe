# 복약 알림 발송 스케줄러 (2026-08-05, 2차)

> 1차(V35)는 등록·체크·조회까지였고 **정해진 시각에 먼저 울려주는 주체가 없었다**.
> 이제 스케줄러가 복용 시각에 피보호자에게 알림을 보내고, 체크되지 않으면 한 번 더 보낸다.
> 1차 문서: `docs/(2026-08-04) feature-medication-reminder.md`

---

## 1. 결정 사항

| # | 항목 | 결정 | 이유 |
|---|---|---|---|
| Q1 | 중복 발송 방지 | **DB 테이블**(`medication_reminder_log`) | Redis 쿨다운은 이상감지처럼 fail-open이라 장애 시 중복 발송. 이력이 남아 "왜 안 왔지" 추적도 가능 |
| Q2 | 유예 창 | **30분** | 재배포·순단으로 놓친 정각은 덮되, 밤늦게 "아침 약 드세요"는 막는다 |
| Q3 | 재알림 | **사용자 선택**(기본 켜짐, 15분 뒤 1회) | 어르신은 한 번으로 자주 놓친다. 다만 문자까지 켜면 한 번 복용에 2건이 나가므로 끌 수 있어야 한다 |
| Q4 | 채널 | **FCM + 문자만** | 사용자 결정. 알림톡은 템플릿을 두지 않아 스킵 |
| Q5 | 수신자 | **피보호자 본인만** | "약 드세요"는 본인용. 보호자용은 별개(미복용 알림, 3차) |

---

## 2. 발송 판정

기준 시각은 **항상 KST**(`MedicationClock`). 스케줄러는 **1분 주기**(`fixedDelay = 60_000`) — 복용 시각이 분 단위이기 때문이다.

**최초 알림(attempt=1)**
```
삭제되지 않음  &&  복용 시각 ∈ [now-30분, now]  &&  오늘 미체크
&&  alarm_enabled  &&  attempt=1 미발송
```

**재알림(attempt=2)**
```
attempt=1 발송 시각 ∈ [now-60분, now-15분]  &&  attempt=2 미발송
&&  여전히 미체크  &&  약이 살아 있음  &&  alarm_enabled && remind_again_enabled
```

- **재알림 마감 60분**을 둔 이유는 유예 창과 같다 — 서버가 오래 내려갔다 올라왔을 때 한참 지난 재알림이 튀어나오지 않게 한다.
- 설정이 뒤집힌 경우(마감 < 지연)에는 구간이 성립하지 않아 **조회 자체를 하지 않는다**.

### 수용한 한계 — 유예 창은 자정을 넘지 않는다
`graceWindowStart()`는 00:00에서 자른다. 예를 들어 23:50 복용 건을 놓친 채 자정을 넘기면 그 건은 발송되지 않는다. 하루를 되감으면 `dose_date`가 달라져 **어제 약을 오늘 날짜로** 보내는 셈이 되기 때문이다. 같은 이유로 재알림도 오늘 날짜의 최초 발송만 대상으로 한다.

---

## 3. 중복 발송 방지 — "선점 후 발송"

스케줄러가 1분마다 도는데 기록이 없으면 유예 창(30분) 내내 같은 알림이 30번 나간다. 그래서:

1. `MedicationReminderPlanner`가 **트랜잭션 안에서** 대상을 고르고 `medication_reminder_log`에 행을 남긴다(선점) → 커밋
2. `MedicationReminderService`가 **트랜잭션 밖에서** 실제로 발송

이 순서의 대가는 **발송 실패 시 그 회차가 유실**된다는 것이다. 반대로 하면(보내고 기록) 발송 직후 앱이 죽었을 때 다음 주기에 또 보낸다. **알림이 두 번 가는 쪽이 더 나쁘다**고 판단했고, 최초 알림이 실패해도 재알림이 두 번째 기회가 된다.

`UNIQUE (medication_id, dose_date, attempt)`가 최종 방어선이다. 사전 조회가 놓친 중복은 저장 시점에 막히며, 그 경우 해당 주기는 롤백되지만 중복을 만든 쪽이 이미 커밋했으므로 다음 주기의 사전 조회에서 걸러져 **스스로 회복**된다.

FCM·SMS 발송을 트랜잭션 밖으로 뺀 덕에 네트워크 지연이 DB 커넥션을 붙잡지 않는다.

---

## 4. 채널 — FCM과 문자뿐

`NotificationType.MEDICATION_REMINDER(SETTINGS_ONLY)` — 사용자가 켠 채널로만 나간다.

- **알림톡은 `templates` 매핑을 두지 않아 스킵**된다(`ANOMALY_DETECTED_SELF`와 같은 구조).
  ⚠️ 복약은 매일 반복되는 전형적인 **다발성 메시지**라, 알림톡으로 보내려면 "반복 수신에 동의했음"을 고정 문구로 고지한 **별도 템플릿 승인**이 필요하다(이상감지 2차 반려 사유). 승인 전에 매핑을 추가하면 채널 제재 대상이 된다.
- **WebSocket은 보내지 않는다** — 복용 체크 반영용 `medication-taken`과 성격이 다르다.
- 강제 발송(`FORCED_*`)이 아닌 이유: 복약은 SOS·화재 같은 즉시 대응이 아니라 매일 반복되는 일상이라, 끄고 싶은 사용자의 선택을 뒤집을 근거가 없다.

### 문구
| 회차 | 제목 | 본문 |
|---|---|---|
| 최초 | 복약 시간이에요 | 혈압약 (암로디핀 5mg) 드실 시간입니다. 아침 08:00 · 1정 |
| 재알림 | 약 드셨나요? | 혈압약 (암로디핀 5mg)이(가) 아직 복용 체크되지 않았어요. |

재알림을 "안 드셨다"가 아니라 **"체크가 안 되어 있다"** 로 적는다 — 실제로는 드시고 체크만 안 한 경우가 많다.

문구를 서버가 만들어야 해서 `MedicationTimeSlot.label()`("아침" 등)을 추가했다. **발송 문구 전용**이며, 화면 표기는 여전히 프론트가 조립한다(API는 슬롯 코드만 준다).

---

## 5. 설정 API 확장 (하위호환)

`GET·PUT /api/guardian/ward/{wardId}/medication-setting`에 `remindAgainEnabled`가 추가됐다.

- **PUT의 두 필드 모두 선택**이며 `null`은 "변경하지 않음"이다. 기존 프론트가 보내던 `{alarmEnabled}`만으로도 그대로 동작하고, 재알림 설정이 초기화되지 않는다.
- 이를 위해 `alarmEnabled`의 `@NotNull`을 **뗐다** — 필수로 두면 재알림만 바꾸려는 요청이 알림 ON/OFF까지 함께 보내야 한다.
- 설정 행이 없으면 기본값은 **둘 다 켜짐**(`MedicationPreference.DEFAULT`) → 백필 불필요.

---

## 6. 운영 설정 (`medication.reminder.*`)

| 키 | 기본 | 환경변수 |
|---|---|---|
| `enabled` | true | `MEDICATION_REMINDER_ENABLED` |
| `grace-minutes` | 30 | `MEDICATION_REMINDER_GRACE_MINUTES` |
| `retry-delay-minutes` | 15 | `MEDICATION_REMINDER_RETRY_DELAY_MINUTES` |
| `retry-deadline-minutes` | 60 | `MEDICATION_REMINDER_RETRY_DEADLINE_MINUTES` |

`enabled=false`는 **킬 스위치**다 — 운영에서 문구·빈도 문제가 드러났을 때 배포 없이 즉시 멈춘다. 등록·체크·조회(1차 기능)는 영향받지 않는다.

코드 기본값과 서버 값을 **같게** 유지한다 — 이상감지에서 서버만 환경변수로 덮어써 로컬만 다르게 동작하던 문제(2026-07-28)를 반복하지 않기 위함이다.

---

## 7. 변경 파일

**신규** — `V36__add_medication_reminder.sql`, `MedicationReminderLog`+repository, `MedicationProperties`, `MedicationPreference`, `MedicationReminderTarget`, `MedicationReminderPlanner`, `MedicationReminderService`, `MedicationReminderScheduler`, 테스트 3개

**변경** — `MedicationSetting`(+컬럼), `MedicationSettingService`(설정 2종을 함께 다루도록 재구성), `GuardianMedicationService`·설정 DTO 2개·컨트롤러 문서, `MedicationTimeSlot`(+label), `MedicationRepository`(+조회 1개), `NotificationType`(+1), `application.yaml`

**무변경** — 복용 체크 흐름·`medication-taken` WS·탈퇴 정리·SOS·이상감지·디스패처·채널 구현체·SecurityConfig

---

## 8. 검증

- `./gradlew build` **379건 / 실패 0**(복약 50건 = 1차 32 + 신규 18).
  신규 축: 대상 선정(미복용·설정 ON) · 이미 발송한 회차 재발송 안 함 · 유예 창 구간 · 재알림 구간(지연 15분/마감 60분) · 재알림 OFF · 중간 체크 시 재알림 취소 · 삭제된 약 스킵 · 설정 역전 방어 · 문구/타입 · 발송 실패 격리 · **킬 스위치**
- **마이그레이션 실적용 검증**(gosky `dmu-dev-db`, V35 때와 동일 방식 — 스키마 복제본 `v36_verify`에 적용 후 임시 DB 삭제, **운영 dev DB 무변경**):
  - 적용 오류 0, 타입 일치(`attempt`=integer, `sent_at`=timestamptz, `remind_again_enabled`=boolean NOT NULL DEFAULT true)
  - `uq_medication_reminder` UNIQUE · FK CASCADE · 재알림 조회 인덱스 확인
  - 실데이터 확인 — ① `alarm_enabled`만 넣어도 `remind_again_enabled`가 true로 백필 ② **같은 회차 중복 기록 거부** ③ attempt=2는 허용 ④ 약 삭제 시 발송 기록도 CASCADE 삭제
  - ※ 복제본은 `--schema-only`라 flyway 이력 행은 비어 있다. DDL 호환성 검증용이며, Flyway 자체 적용은 배포 시 기동 로그로 확인한다.

---

## 9. 이번 범위 밖 (후속)

1. **미복용 시 보호자 알림** — "안 드셨어요"는 수신자·판단 시점이 달라 별개 기능이다.
2. **복약 알림톡 템플릿 심사** — 다발성 메시지 고지 문구가 필요하다.
3. 복약 순응도 통계 화면 · 약봉투 OCR · 약 수정 API.
