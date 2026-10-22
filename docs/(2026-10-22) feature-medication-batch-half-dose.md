# 약 일괄 등록 + 반 알(0.5 단위) (2026-10-22)

> 근거: "약 추가" 시안 변경(약 여러 개 × 드시는 시간 여러 개, 복용량 "반알", "직접 쓰기" 탭도 같은 양식). 약 봉투 OCR(`(2026-10-22) feature-medication-ocr.md`, PR #361)의 후속이며 OCR 계약은 그대로다.
> 브랜치 `feature/medication-batch-half-dose`, **마이그레이션 V62**.

## 1. 반 알

- `doseAmount`: 정수 → **0.5 단위 숫자**(0.5~99). 0.5 배수가 아니면(0.3·1.2) 400, 0·100도 400. 기존 FE의 정수(1, 2)는 그대로 받는다.
  - 적용: 단건 등록 `POST .../ward/{wardId}/medication`, 수정 `PATCH /api/guardian/medication/{id}`, 일괄 등록. 검증은 `@DoseAmount` 하나(규칙 `DoseAmounts`).
- 응답: 정수는 `1`, 반 알은 `0.5`(DB `NUMERIC(3,1)`의 `1.0`을 그대로 내리지 않는다). JS에서는 `1`과 `1.0`이 같아 기존 FE 화면은 그대로다.
- DB: **V62** `dose_amount INT → NUMERIC(3,1)` + `chk_medication_dose_amount`(0.5~99, 0.5 배수). 기존 값 보존.
  - **되돌리기**: 적용은 무손실이다. 단 0.5 값이 한 건이라도 저장된 뒤에는 INT로 되돌리면 그 값을 잃으므로 사실상 비가역이 된다(되돌리려면 반 알 행 정리 선행).
- 서버 문구(복약 알림 `MEDICATION_REMINDER`): `DoseAmounts.label` - 0.5 = **"반 알"**, 1.5 = **"1정 반"**, 정수 = "N정". 미복용 요약·중지 안내 문구에는 복용량이 없어 변경 없음.
- FE 표시(`${doseAmount}정`)에서 0.5를 "반 알"로 바꾸는 것은 FE 몫이다(아래 FE 전달).

## 2. 일괄 등록

`POST /api/guardian/ward/{wardId}/medication/batch` - GUARDIAN 전용

```json
{ "drugs": [{ "name": "타이레놀정", "doseAmount": 0.5 }, { "name": "코대원정" }],
  "times": [{ "doseTime": "08:00:00" }, { "doseTime": "19:00:00" }],
  "memo": "식후 30분" }
```

- `drugs` 1~20개(이름 검증은 단건과 같음 - `@VisibleText`·`@NoControlChars`·100자, `doseAmount` 생략 시 1), `times` 1~6개, `memo` 선택(모든 약에 붙음).
- drugs × times 만큼 **한 트랜잭션**으로 생성 - **전부 성공 또는 전부 실패**(실 DB 통합 테스트로 중간 항목 실패 시 0건 확인).
- 응답 `data`: `MedicationItem[]`(단건 등록 응답과 같은 형태) - 복용 시각 순, 같은 시각 안에서는 요청한 약 순서.
- **시간대는 서버가 시각으로 정한다**(`MedicationTimeSlot.fromDoseTime`): 04:00~10:59 아침 / 11:00~15:59 점심 / 16:00~20:59 저녁 / 21:00~03:59 취침 전. 기본 시각(08·13·18·22시)은 자기 시간대로 돌아온다(테스트 고정). 새벽 약을 "아침"에 두면 아침 약처럼 읽혀 "취침 전"으로 뒀다.
- 400: 같은 요청 안의 같은 이름(정리 후·대소문자 무시) `MEDICATION_BATCH_DUPLICATE_NAME`, 같은 시각 `MEDICATION_BATCH_DUPLICATE_TIME`, 개수·이름·복용량 `INVALID_INPUT`. 이미 등록된 약과 이름이 같은 것은 단건 등록처럼 허용.
- 인가: `isActiveConnection`(위반 403 `MEDICATION_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`). 약 한 건 생성은 단건 등록과 같은 `newMedication`(이름 정리·빈 메모 null).
- 단건 등록 API는 그대로다(시간대는 계속 클라이언트가 고른다).
- 스케줄러·미복용 요약: 같은 엔티티의 `doseTime`·`createdAt`만 보므로 일괄 등록 약도 단건과 똑같이 다룬다("오늘 복용 시각 뒤에 등록한 약은 그날 요약 제외" 포함).

## 3. 검증

- `./gradlew build` 통과: 전체 1,778건, 실패 0. 새 테스트는 아래와 같다(기존 테스트는 정수 → `BigDecimal` 타입만 바꿨다).
  - `DoseAmountsAndTimeSlotTest`(40): 유효·무효 값, 응답 정규화, "반 알"·"1정 반" 표기, 시각 경계, 기본 시각이 원래 시간대로 돌아오는지
  - `MedicationHalfDoseBatchValidationTest`(12): 등록·수정 0.5 통과, 0.3·0·100 거절, 정수 JSON 호환(Jackson 3), 개수 상한, 항목 검증
  - `GuardianMedicationBatchTest`(4): 6×2=12건, 순서·시간대·정리, 403과 `[IDOR-ATTEMPT]`, 중복 400, 단건 등록·수정 반 알
  - `GuardianMedicationDoseJsonTest`(2): 응답 `1`·`0.5`·`10`(`1.0`·`1E+1` 아님), 0.3 → 400
  - `MedicationReminderServiceTest`: 반 알 문구 1건 추가
  - `MedicationControllerSecurityTest`: 일괄 등록 WARD·ADMIN 403
- 통합 테스트 `MedicationHalfDoseBatchIntegrationTest`(실 PostgreSQL): V62 CHECK(0.3·0·100·99.5 거절), 0.5 저장·조회, 12건 생성, 중간 항목이 DB에서 거절되면 0건. vkcs `tools/integration-test.sh feature/medication-batch-half-dose`.

## 4. FE 전달

- 복용량 스테퍼를 0.5 단위로 바꾸고, 0.5는 "반 알", 1.5는 "1정 반"으로 표시한다(현재 `${doseAmount}정` → "0.5정").
- "약 추가"·"직접 쓰기"는 일괄 등록 API로 보낸다. 시간대는 보내지 않는다(서버가 시각으로 정함). 하나라도 오류면 아무것도 등록되지 않으니 오류 `code`를 보여 주고 그대로 다시 보내게 한다.
- OCR 결과(`perDose`)의 0.5를 이제 `doseAmount`에 그대로 넣을 수 있다(메모 "1회 반 알" 우회 불필요).
