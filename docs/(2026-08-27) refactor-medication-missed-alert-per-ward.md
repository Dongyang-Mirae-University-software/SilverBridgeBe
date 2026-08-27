# 미복용 요약 설정 - 축을 (보호자) → (보호자, 피보호자)로 (2026-08-27)

> 같은 날 머지한 V40(발송 시각 보호자 선택)의 후속. 마이그레이션 **V41**.
> 시각을 **피보호자별로** 지정할 수 있게 하고, 카드 렌더링용 설정값을 요약 응답에 함께 싣는다.

## 1. 왜 하루 만에 다시 고치나

V40은 발송 시각을 보호자당 **하나**만 가질 수 있었다. 그런데 이 시각은 **집계 상한을 겸한다**
(불변 규칙 ⑧ - 그 시각까지 복용 시각이 지난 약만 센다). 피보호자마다 마지막 복약 시각이 다르면
하나의 값으로는 반드시 누군가 손해를 본다.

| 피보호자 | 가장 늦은 약 | 필요한 시각 |
|---|---|---|
| 김영희 | 수면 보조제 **22:00** | 22:30이어야 이 약이 요약에 들어감 |
| 이순자 | 마지막 약 **12:00** | 20:00이면 충분 |

공통 21:00을 쓰면 김영희의 22:00 약이 **매일 요약에서 빠지고**, 그걸 살리려 22:30으로 올리면
이순자 요약까지 밤 10시 반에 도착한다. 프론트 프로토타입이 카드마다 시각 피커를 둔 것도 같은 직관이다.

## 2. 무엇이 바뀌나

- 설정 단위: **(보호자, 피보호자)당 1행**. 같은 피보호자를 보는 보호자 둘이 서로 다른 시각을 가질 수 있다.
- API 경로: `/api/guardian/medication-alert-setting` → **`/api/guardian/ward/{wardId}/medication-alert-setting`**
- `GET /api/guardian/medication` 응답(카드)에 설정 4종을 동봉 - 카드마다 설정 API를 더 부르지 않게 한다.
- **인가가 새로 필요해졌다** - 이전엔 대상이 본인뿐이라 검증할 게 없었지만, 이제 남의 피보호자 설정을
  건드릴 수 있는 경로가 되었다.

## 3. 스키마 (V41)

```sql
DELETE FROM guardian_medication_setting;   -- 축이 바뀌어 기존 행은 의미를 잃는다

ALTER TABLE guardian_medication_setting
    ADD COLUMN ward_id VARCHAR(6) NOT NULL REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE guardian_medication_setting DROP CONSTRAINT uq_guardian_medication_setting;
ALTER TABLE guardian_medication_setting
    ADD CONSTRAINT uq_guardian_medication_setting UNIQUE (guardian_id, ward_id);

CREATE INDEX idx_guardian_medication_setting_ward ON guardian_medication_setting (ward_id);
```

- `DELETE`가 유일한 비가역 구문이다. **배포 2곳(gosky·vkcs-linux) 모두 0건**임을 확인하고 넣었다
  (`SELECT count(*) → 0`). 행이 있더라도 설정은 기본값(ON · 21:00)으로 되돌아갈 뿐이라
  사용자가 잃는 것은 직접 고른 시각 하나다. 기존 행에는 ward_id를 채울 근거가 없어 복제도 불가능하다.
- `ward_id` 인덱스를 따로 두는 이유: 발송 판정은 "이 피보호자의 보호자들 설정"을 한 번에 읽는데,
  UNIQUE 인덱스는 `guardian_id` 선행이라 `ward_id` 단독 조회에 쓰이지 않는다.
- 피보호자 탈퇴 시 FK CASCADE로 설정도 함께 사라진다.

## 4. API

```
GET  /api/guardian/ward/{wardId}/medication-alert-setting
PUT  /api/guardian/ward/{wardId}/medication-alert-setting
```

```jsonc
// PUT 요청 - 두 필드 모두 선택. null은 "변경하지 않음"(공통 규약, V40과 동일)
{ "missedAlertEnabled": true, "missedAlertTime": "22:30:00" }

// 응답 - 어느 피보호자 건인지 함께 담는다. 시각은 항상 실효값(미설정이면 21:00)
{ "wardId": "A1B2C3", "missedAlertEnabled": true, "missedAlertTime": "22:30:00" }
```

`GET /api/guardian/medication`의 카드(`WardMedicationSummary`)에 4필드 추가:

| 필드 | 축 | 의미 |
|---|---|---|
| `alarmEnabled` | 피보호자 계정 | 복용 시각 알림 (보호자들이 공유) |
| `remindAgainEnabled` | 피보호자 계정 | 15분 뒤 재알림 (보호자들이 공유) |
| `missedAlertEnabled` | (보호자, 피보호자) | 내가 이 피보호자 건 요약을 받을지 |
| `missedAlertTime` | (보호자, 피보호자) | 내가 받을 시각 = 집계 상한 |

`remindAgainEnabled`는 `MedicationPreference`에 이미 있었지만 응답에 실리지 않아 프론트가 볼 수 없었다.
카드에 재알림 토글을 그리려면 필요하므로 함께 노출한다.

## 5. 인가 (신규)

`GuardianMedicationSettingService`의 `getSetting`·`update`가 `isActiveConnection`으로 검증한다.
위반 시 **403 `MEDICATION_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]` WARN** - 복약 도메인 기존 형태 그대로다.

- `getMyWards()`는 PENDING이 섞여 있어 인가 목록으로 쓰지 않는다(불변 규칙 ②).
- 발송용 벌크 조회(`findSettings`)는 검증하지 않는다 - 호출자(Planner)가 이미
  `getActiveGuardianIds`로 좁힌 뒤 부르고, 스케줄러 경로라 요청 주체가 없다.

## 6. 집계 로직은 거의 그대로

V40에서 이미 **보호자별로 상한을 따로 계산**하도록 재구성해 둔 덕분에, 이번엔 조회 키에 `wardId`가
하나 붙은 것이 전부다.

```
[V40]  findSettings(guardianIds)          → 보호자별 cutoff로 집계
[V41]  findSettings(wardId, guardianIds)  → 동일
```

상위집합 조회(현재 시각까지의 약), 매 분 스캔 방지 게이트(`MIN`/`MAX(missed_alert_time)`),
하루 1건 UNIQUE, 선점 후 발송, 자정 컷은 **무변경**이다. `medication_missed_alert_log`는 이미
`(guardian_id, ward_id, dose_date)` 축이라 손댈 것이 없다.

## 7. 변경 파일

| 파일 | 내용 |
|---|---|
| `V41__change_missed_alert_setting_to_per_ward.sql` | 신규 |
| `GuardianMedicationSetting` | `wardId` 필드 + 팩토리 시그니처 |
| `GuardianMedicationSettingRepository` | 조회 메서드를 (보호자, 피보호자) 축으로 교체. MIN/MAX 게이트는 그대로 |
| `GuardianMedicationSettingService` | `wardId` 축 + **ACTIVE 연결 검증** + 카드용 벌크 조회 추가 |
| `MedicationMissedAlertPlanner` | `findSettings(wardId, pending)` |
| `GuardianMedicationController` | 경로에 `/ward/{wardId}` 추가, 기존 경로 제거 |
| `WardMedicationSummary` | 설정 4종 추가 |
| `GuardianMedicationService` | 카드 조립 시 설정 벌크 조회 1회 추가(N+1 없음) |
| `GuardianMedicationAlertSetting{Request,Response}` | 문구 정리, 응답에 `wardId` |

## 8. 파괴적 변경 (FE)

`/api/guardian/medication-alert-setting`(wardId 없는 경로)이 **사라진다**.

착수 전 `../SilverBridgeFe`를 검색해 **사용처 0건**을 확인했다(`medication-alert-setting`·`missedAlert`
모두 미검출, 복약 관련 파일은 `src/app/(ward)/ward/medication` 하나뿐). 화면이 붙기 전이라
호환 경로를 남기지 않고 교체한다.

## 9. 검증

`./gradlew build` **415건 / 실패 0** (기존 403 + 신규 12).

마이그레이션은 gosky dev DB에서 **트랜잭션 실행 후 롤백**으로 사전 검증했다 - 컬럼·UNIQUE·인덱스·FK가
의도대로 생성되고 스키마가 원상 복구됨을 확인.

| 신규 테스트 | 고정하는 동작 |
|---|---|
| `조회_인가위반` · `변경_인가위반` | 연결 없는 피보호자 설정 접근 시 403, 행도 만들지 않음 |
| `미설정_기본값` · `시각미설정_기본시각` | 저장 행이 없거나 시각이 비면 기본값으로 채워 응답 |
| `null은_미변경` | 시각만 바꿔도 수신 여부가 초기화되지 않음 |
| `시각_분단위_절삭` | 초·나노 버림 |
| `발송용_조회는_피보호자축` | 발송 판정이 그 피보호자 축으로 조회 |
| `카드목록용_조회` | 보호자 한 명의 피보호자별 설정을 한 번에 |
| `설정조회는_피보호자축` (Planner) | Planner가 `wardId`를 넘겨 조회 |
| `getWardMedications_알림설정_동봉` | 카드에 4종이 실리고, 미설정 피보호자는 기본 21:00 |

### 수동 확인 가이드

1. 김영희 건 `PUT .../ward/{김영희}/medication-alert-setting` `{"missedAlertTime":"22:30:00"}`
2. 이순자 건은 `{"missedAlertTime":"20:00:00"}`
3. `GET /api/guardian/medication` → 두 카드의 `missedAlertTime`이 각각 22:30 / 20:00
4. 22:30에 김영희 건 요약만, 20:00에 이순자 건 요약만 도착
5. 연결되지 않은 wardId로 PUT → **403** + 서버 로그에 `[IDOR-ATTEMPT]`

## 10. 프론트 영향

- 설정 UI를 **피보호자 카드 안에** 두는 프로토타입 구조가 그대로 유효하다.
- 카드 렌더링에 필요한 값이 `GET /api/guardian/medication` 한 번에 다 오므로 추가 호출이 필요 없다.
- ⚠️ 시각 선택 UI 옆에 **"선택한 시각 이후 복용 예정인 약은 그날 요약에 포함되지 않습니다"** 안내를
  반드시 넣을 것. 한 걸음 더 나아가 그 피보호자의 가장 늦은 복용 시각보다 이른 시각을 고르면
  "취침 전 22:00 약은 이 요약에 포함되지 않습니다" 경고를 인라인으로 띄우면 좋다(프론트에서 계산 가능).
