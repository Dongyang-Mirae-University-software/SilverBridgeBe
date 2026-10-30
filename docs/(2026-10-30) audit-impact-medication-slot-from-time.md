# 영향 범위 점검 - 복약 시간대를 복용 시각으로 정하기 (2026-10-30)

- 변경: BE #407(`1c131a5`) 수정·단건 등록에서 시간대 = `fromDoseTime(최종 시각)` / FE #181(`ccedef6`) 수정 창 시각↔시간대 연동
- 불변식: **이번 변경 이후 저장되는 약은 `time_slot == fromDoseTime(dose_time)`이다.** 이전에 저장된 어긋난 약은 다음 수정 때까지 예외(사용자 결정 - 마이그레이션 없음)
- 점검만 했고 코드는 바꾸지 않았다.

## 사용처 판정 (BE)

| 도메인 | 위치 | 새 불변식 | 근거 |
|---|---|---|---|
| medication | `GuardianMedicationService.create` | PASS | 시각이 오면 `fromDoseTime`, 없으면 시간대 기본 시각(기본 시각은 `fromDoseTime`에서 자기 시간대로 돌아옴 - `DoseAmountsAndTimeSlotTest`) |
| medication | `GuardianMedicationService.update` | PASS | 시간대·시각 중 하나라도 오면 최종 시각 기준. 이름·복용량·메모만 오면 기존 값 유지(옛 행 예외 유지) |
| medication | `createBatch` | PASS | 원래 `fromDoseTime` |
| medication | `MedicationReminderPlanner`·`MedicationReminderService.GroupKey` | PASS (옛 행 주의) | 묶음 키에 시간대가 들어간다. 옛 어긋난 약과 같은 시각의 맞는 약이 있으면 같은 분에 알림이 **두 건** 나간다(L-1). 문구는 2026-10-27부터 "약 드실 시간이에요."라 시간대 이름이 없다 |
| medication | `MedicationItem.of`(카드·피보호자 화면 응답) | PASS | 저장값 그대로 |
| dashboard | `GuardianDashboardService`(확인이 필요한 일) | PASS (옛 행 주의) | 저장 시간대를 그대로 싣는다 - 옛 행은 "점심 01:40"으로 보일 수 있다 |
| 미복용 요약 | `MedicationMissedAlert*` | 해당 없음 | 시간대를 쓰지 않는다(시각 기준 집계) |
| DB | `V35` `time_slot VARCHAR(20) NOT NULL` | 해당 없음 | CHECK 없음, 값 집합은 enum과 같음 |

## 사용처 판정 (FE)

| 위치 | 판정 | 근거 |
|---|---|---|
| 보호자 `MedicationFormModal` | PASS | 시각↔시간대 연동(#181), 어긋난 옛 약은 열 때 시각 기준으로 보정 |
| 보호자 `WardMedicationCard` | PASS | 칸 순서 시간대 고정(#174), 옛 행은 저장 시간대 칸에 보임 |
| 피보호자 `WardMedicationContent` | PASS (옛 행 주의) | (시간대, 시각) 묶음·시각 순 정렬 - 옛 행은 "점심" 이름으로 01:40에 보인다 |
| 단건 등록 API 사용 | 해당 없음 | FE는 단건 등록(`POST /ward/{wardId}/medication`)을 쓰지 않는다(약 추가 = 일괄 등록) |

## 이슈

| 등급 | ID | 내용 | 제안 |
|---|---|---|---|
| 🟡 Medium | M-1 | **Swagger 본문 설명이 새 규칙을 말하지 않는다.** `GuardianMedicationController`의 등록·수정 `@Operation` 설명은 "timeSlot (필수)"·"[시간대만 바꾼 경우]"만 있고 "시간대는 시각으로 정해진다(어긋나면 시각 기준)"가 없다. 요청 DTO 필드 설명만 고쳤다 | 두 `@Operation` 설명에 한 단락 추가(문서만, 동작 무변경) |
| 🟢 Low | L-1 | 옛 어긋난 약이 남아 있으면 **같은 시각 알림이 두 건**으로 나뉠 수 있다(묶음 키에 시간대 포함). 옛 행 개수는 운영 DB 읽기가 막혀 세지 못했다 | 개수를 보고 결정: 적으면 그대로(다음 수정 때 바로잡힘), 많으면 보정 마이그레이션 검토 |
| 🟢 Low | L-2 | **정책 문서 drift**: `policy-medication.md` "시간대 묶음" 항목의 알림 문구 예시("아침 약 6개 드실 시간입니다(08:00). …외 N개")는 2026-10-27 단순화("약 드실 시간이에요.") 이전 것이다 | 예시를 현재 문구로 바꾸기(문서만) |
| 🟢 Low | L-3 | FE 수정 창의 `slotFromTime` 경계(03:59/04:00, 10:59/11:00, 15:59/16:00, 20:59/21:00)를 고정한 테스트가 없다(BE는 `DoseAmountsAndTimeSlotTest`가 고정) | 경계값 브라우저 테스트 1건 추가 |
| 🟢 Low | L-4 | 복용량 표기가 입력 칸("반알", "1알 반" - `formatDoseLabel`)과 목록("반 알", "1정 반" - `formatDoseAmount`, BE 알림과 같음)에서 다르다. 정책 위반("0.5정")은 아니다 | 공용 코드라 피보호자 화면 세션과 함께 통일 여부 결정 |

🔴 Critical·🟠 High: 없음

## 정정

- #407 PR 본문과 `fix-medication-slot-from-time.md`·정책 파일·progress에 "어긋난 시간대가 알림 문구를 틀어지게 한다"고 썼으나 사실과 다르다 - 알림 문구는 2026-10-27부터 시간대 이름을 넣지 않는다. 실제 영향은 묶음 키(L-1)다. 문서 세 곳은 이 점검에서 고쳤다(PR 본문은 머지된 기록이라 그대로 둔다).

## 테스트로 고정할 수 있는 것

- BE 저장 경로 3곳(단건·일괄·수정)의 불변식은 단위 테스트로 고정됐다(#407 5건 + 기존 일괄 등록).
- FE 경계값(L-3), Swagger 문구(M-1)는 수정할 때 테스트·`*ApiDocTest` 형태로 고정을 제안한다.

## 어긋난 약 개수 확인 명령 (사용자 실행용, dev DB, 개수만)

```
ssh gosky 'docker exec dmu-dev-db sh -c "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -At -c \"SELECT count(*) FROM medication WHERE deleted_at IS NULL AND time_slot <> CASE WHEN extract(hour FROM dose_time) BETWEEN 4 AND 10 THEN '"'"'MORNING'"'"' WHEN extract(hour FROM dose_time) BETWEEN 11 AND 15 THEN '"'"'LUNCH'"'"' WHEN extract(hour FROM dose_time) BETWEEN 16 AND 20 THEN '"'"'DINNER'"'"' ELSE '"'"'BEDTIME'"'"' END\""'
```

## 종합 판정

**PASS(조건부)** - 새로 저장되는 약은 모든 경로에서 불변식을 지킨다. M-1(Swagger 설명)은 다음 작업에서 고치고, L-1은 옛 행 개수를 본 뒤 결정한다.
