# 복약 카드 - 오늘 등록한 약은 등록 전에 지난 복용 시각을 "지난 약"으로 세지 않는다 (2026-10-22)

## 배경

오늘 14:00에 아침 08:00 약을 등록하면 보호자 복약 카드가 바로 "드실 시간이 지난 약 1개가 아직 체크 전이에요"로 잡았다.
그 약은 오늘 먹을 기회가 없었다. BE 미복용 요약(`MedicationMissedAlertPlanner`, MED-G14)은 이미 이 약을 빼고 있어
**화면·대시보드·알림이 서로 다른 약을 셌다.** 카드 응답에 등록 시각이 없어 FE가 같은 판정을 할 수 없었다(FE PR #86 보류 항목 a).

## 범위

| 저장소 | 변경 |
|---|---|
| BE | `MedicationItem`에 `createdAt`(약 등록 시각, KST `+09:00`) 추가 - 가산 필드, 마이그레이션 없음(`medication.created_at`은 V35부터 있음) |
| BE | 판정 기준을 `MedicationClock.registeredAfterDose(registeredAt, doseTime, day)` 한 곳으로 모음. 미복용 요약은 이 함수를 위임 호출 |
| BE | 보호자 대시보드 "확인이 필요한 일"(`GuardianDashboardService.uncheckedOf`)도 같은 기준으로 뺀다(사용자 결정) |
| FE | `MedicationItem.createdAt?` 타입, `getDoseStatus`·`isRegisteredAfterDose`·`toKstDateTime`을 `utils/format/medication.ts`로 분리, 카드 상태 `beforeRegistered` = 회색 "오늘은 등록 전이었어요"(사용자 결정) - 지난 약 개수·점 색에서 제외 |

## 기준 (BE·FE 동일)

- **그날(KST) 등록**됐고 **등록 시각 > 복용 시각**이면 그날은 미체크로 세지 않는다.
- 정각 등록(같은 시각)은 센다. 전날 이전 등록분은 시각과 무관하게 센다. 등록 시각을 모르면(옛 BE 응답·감사 필드 없음) 예전처럼 센다.
- 날짜·시각은 저장 오프셋과 무관하게 KST로 비교한다(BE `toKst`, FE `Intl` `Asia/Seoul`). FE 비교는 밀리초까지 해 BE의 `LocalTime.isAfter`와 맞춘다.
- 체크한 약은 등록 시각과 무관하게 "드셨어요".
- 등록 시각 기준이라 약 수정(복용 시각 변경)은 이 판정에 영향이 없다(미복용 요약과 같음).

## 바뀌지 않는 것

복용 체크·복약 알림(2차)·미복용 요약 발송 조건, 인가(ACTIVE 연결만), 기존 응답 필드, 피보호자 화면(지난 약 표시가 없음), 카드의 "N번 중 M번 드셨어요" 총계.

## 배포 순서

BE 먼저(필드 가산이라 옛 FE 무영향) → FE(필드가 없으면 예전 동작).

## 변경 파일

- BE: `domain/medication/service/MedicationClock.java`(`atKst`·`registeredAfterDose`), `MedicationMissedAlertPlanner.java`(위임), `dto/MedicationItem.java`(`createdAt`), `domain/dashboard/service/GuardianDashboardService.java`(`uncheckedOf`)
- BE 테스트: `GuardianDashboardServiceTest`(늦게 등록·UTC 저장값·어제·정각·모름), `GuardianMedicationDoseJsonTest`(응답 `"createdAt":"…+09:00"`)
- FE: `src/service/interface/medication.ts`, `src/utils/format/medication.ts`(+test), `WardMedicationCard.tsx`

## 검증

- BE `./gradlew build` 통과 - 테스트 1816건(실패 0, 건너뜀 1). 기존 `MedicationMissedAlertRegistrationRuleTest` 그대로 통과(위임 후 동작 동일).
- FE `npm test` 138/138, `tsc`·`next build` 통과, 린트 오류는 기존 `BirthDateSelects.tsx` 1건뿐(범위 밖).
- FE 375px·1280px 화면: 늦게 등록한 약 회색 배지·회색 점, 지난 약 개수에서 제외, 가로 넘침 없음.
