# 미복용 시 보호자 알림 (2026-08-05, 3차)

> 2차(V36)로 피보호자 쪽 고리는 닫혔다 — 복용 시각 알림 + 재알림. 하지만 그 뒤로도 끝내 체크하지 않으면
> 아무 일도 일어나지 않아, 보호자는 앱을 열어야만 알 수 있었다. 이제 저녁에 한 번 요약해 알린다.
> 이전 문서: `docs/(2026-08-04) feature-medication-reminder.md`, `docs/(2026-08-05) feature-medication-reminder-scheduler.md`

---

## 1. 이 기능의 위험은 기술이 아니라 신뢰다

설계 내내 두 가지를 우선했다.

**① "체크 안 함"은 "안 드심"이 아니다.** 실제로는 복용하고 체크만 안 한 경우가 흔하다. 보호자에게 "약을 안 드셨습니다"로 통보하면 사실이 아닌 정보로 걱정시키고 전화하게 만든다. 그래서 문구는 **"아직 체크되지 않았습니다"** 이며, 이를 테스트로 고정했다(`doesNotContain("안 드셨", "복용하지 않")`).

**② 알림 피로가 필수 알림을 죽인다.** 보호자는 피보호자를 여럿 볼 수 있고, 체크가 습관화되지 않으면 매일 알림이 쌓인다. 보호자가 앱 알림을 통째로 꺼버리면 **SOS·이상감지까지 함께 죽는다.** 그래서 ⓐ 건별이 아니라 **하루 1건 요약**, ⓑ **이 알림만 따로 끄는 설정**을 함께 넣었다.

---

## 2. PHASE 0 관측 — 데이터가 0이었다

설계 근거로 체크율을 보려 했으나, 양 서버 모두 **등록된 약 0건 / 복용 체크 0건 / 알림 발송 0건**이었다. 1차는 하루 전, 2차는 당일 배포됐고 **FE 복약 화면이 아직 없다**(9월 시작 예정). 따라서 "실사용 데이터로 건별/요약을 결정한다"는 계획은 성립하지 않았고, **추천안(하루 요약·21:00)을 채택하되 판정 시각·마감·ON/OFF를 모두 환경변수로 빼서** 실사용 후 코드 수정 없이 조정할 수 있게 했다.

---

## 3. 판정 — 분모는 "저녁까지 예정된 약"

**판정 시각(기본 21:00 KST)까지 복용 시각이 지난 약만 집계한다.**

취침 전 22:00 약은 아직 먹을 때가 아니므로 제외된다 — 포함하면 매일 "체크되지 않았다"는 거짓 알림이 나간다. 대신 그 약은 **그날 요약에서 아예 빠진다**(미복용으로도 잡히지 않는다). 이 한계는 문구로 해소한다:

> "○○님의 **오늘 저녁까지 예정된** 복약 3건 중 1건이 아직 체크되지 않았습니다."

취침 전 약까지 포함하려면 판정을 23시 이후로 미뤄야 하는데, 그 시간엔 보호자가 대응할 수 없어 채택하지 않았다.

- 발송 창 = `[21:00, 21:00+120분]`. 서버가 늦게 복구돼도 자정 직전에 요약이 튀어나오지 않는다. 마감이 자정을 넘기면 그날 끝에서 끊는다(날짜가 바뀌면 요약 대상 자체가 달라지므로).
- 미체크가 **하나도 없으면 발송하지 않는다.**

---

## 4. 수신자·중복 방지

- **수신자** = 그 피보호자의 **ACTIVE 보호자 중 수신 설정이 켜진 사람.** 피보호자 본인에게는 보내지 않는다(2차가 이미 담당).
- **중복 방지** = `medication_missed_alert_log`의 `UNIQUE (guardian_id, ward_id, dose_date)` → 하루 한 번.
  축이 **약 단위가 아니라 (보호자, 피보호자, 날짜)** 인 것이 2차의 `medication_reminder_log`와 다른 점이다 — 요약이라 약 3건이 미체크여도 알림은 1건이다. 그래서 테이블을 분리했다.
- **선점 후 발송** — 2차와 동일하다. 로그를 먼저 커밋하고 트랜잭션 밖에서 발송하며, 발송 실패 시 재시도하지 않는다.

---

## 5. 채널·설정

`NotificationType.MEDICATION_MISSED(SETTINGS_ONLY)` — **FCM·문자만**. 알림톡은 `templates` 매핑을 두지 않아 스킵된다(불변 규칙 유지: 복약은 다발성 메시지라 별도 템플릿 승인 필요).

**보호자 설정 API** (신규, 축이 보호자 본인이라 `wardId`가 없다)
```
GET·PUT /api/guardian/medication-alert-setting     바디 { missedAlertEnabled }  (선택 — null이면 기존값 유지)
```
피보호자별 설정(`/api/guardian/ward/{wardId}/medication-setting`)과 구분된다 — 저쪽은 "피보호자에게 무엇을 보낼지", 이쪽은 "내가 무엇을 받을지"다.

**기본값 ON**: 기본 OFF면 아무도 켜지 않아 기능이 죽는다. 하루 1건 요약이라 부담이 크지 않고, 끌 수 있으니 "앱 알림 전체를 끄는" 최악은 피한다.

**운영 설정** `medication.reminder.missed-alert.*`
| 키 | 기본 | 환경변수 |
|---|---|---|
| `enabled` | true | `MEDICATION_MISSED_ALERT_ENABLED` |
| `alert-time` | 21:00 | `MEDICATION_MISSED_ALERT_TIME` |
| `deadline-minutes` | 120 | `MEDICATION_MISSED_ALERT_DEADLINE_MINUTES` |

**킬 스위치가 2차와 독립**이다 — 보호자 쪽 문구·빈도 문제로 이걸 꺼도 피보호자의 복용 알림은 계속 나가야 한다(테스트로 고정).

---

## 6. 변경 파일

**신규** — `V37__add_medication_missed_alert.sql`, `GuardianMedicationSetting`·`MedicationMissedAlertLog`(+repository 2), `GuardianMedicationSettingService`, `MedicationMissedAlertPlanner`·`Service`·`Target`, DTO 2, 테스트 2

**변경** — `MedicationReminderScheduler`(요약 단계 추가, 킬 스위치 분리), `MedicationProperties`(중첩 `MissedAlert`), `MedicationRepository`(+조회 1), `GuardianMedicationController`(+엔드포인트 2), `NotificationType`(+1), `application.yaml`

**무변경** — 2차 발송 경로(피보호자 알림)·1차 API·복용 체크·탈퇴 정리·SOS·이상감지·디스패처·채널 구현체

---

## 7. 검증

- `./gradlew build` **393건 / 실패 0**(복약 64건 = 2차 50 + 신규 14).
  축: 발송 창(판정 시각 전/마감 후) · 미체크 있는 피보호자만 · 전부 체크 시 미발송 · 설정 OFF 제외 · 설정 없으면 기본 ON · 하루 한 번(이미 보낸 건 제외) · 보호자 0명 시 기록도 안 남김 · 집계 상한이 판정 시각 · **문구 단정 금지** · 발송 실패 격리 · **킬 스위치 독립** · 신규 엔드포인트 역할 인가
- **V37 실적용 검증**(gosky `dmu-dev-db` 스키마 복제본, 임시 DB 삭제, **운영 dev DB 무변경**: V36 그대로·신규 테이블 0):
  - 적용 오류 0, 타입 일치(`missed_count`/`total_count`=integer, `sent_at`=timestamptz, `missed_alert_enabled`=boolean)
  - `uq_guardian_medication_setting`·`uq_medication_missed_alert` UNIQUE, FK 3개 CASCADE 확인
  - 실데이터 — ① 같은 (보호자, 피보호자, 날짜) **중복 발송 거부** ② 다른 날짜는 허용 ③ 보호자 탈퇴 시 설정·발송 기록 CASCADE 삭제

---

## 8. 이번 범위 밖 (후속)

1. **약 수정 API** — 화면에 수정 UI가 생기면. OCR 인식 결과를 사용자가 고쳐 저장하는 흐름의 전제이기도 하다.
2. **약봉투 OCR** — 이미지 업로드·OCR 엔진·파싱·비동기 처리 인프라가 모두 없어 별도 설계가 필요하다. 약봉투는 환자 이름·병원·질병 추정이 가능한 민감정보라 외부 OCR 전송 시 동의·폐기 정책이 따라온다.
3. 복약 순응도 통계 화면 · 복약 알림톡 템플릿 심사.
