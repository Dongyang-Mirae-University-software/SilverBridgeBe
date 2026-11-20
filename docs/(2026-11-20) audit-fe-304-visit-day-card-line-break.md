# FE #304 병원 가는 날 카드 줄 갈라짐·취소 뒤 포커스 수정 기능 점검 (템플릿 B)

- 점검일: 2026-11-20
- 대상: FE PR #304 (머지 `1b9ebe4`, gosky FE dev 반영) - #301 점검(`(2026-11-20) audit-fe-301-guardian-visit-day-card.md`) M-1·L-1~L-3 조치분
- 판정: **PASS** - 🔴 0 · 🟠 0 · 🟡 0 · 🟢 3 → L-1·L-3 **조치**(FE #305 `29719a5`), L-2 **조치**(FE #306 `68c3d2b`)

## 변경 요약

- 진료과 앞 가운뎃점 뒤를 줄 바꿈 없는 공백(`NBSP`)으로 - 공용 `VisitDayCard`, 피보호자·보호자 "그다음" 카드.
- 화면용 시각 `formatTimeDisplay`("오전 9시"를 한 줄에, 분 앞은 줄 바꿈 가능) - 위 세 곳.
- 보호자 예약 취소에 성공해 그 카드가 사라지면 포커스를 [새 진료 예약하기]로(`HospitalListStep` effect).

## 점검 파일

`src/utils/reservation/reservationView.ts` · `src/components/reservation/VisitDayCard.tsx`·`VisitDayCard.browser.test.tsx` · `src/app/(guardian)/guardian/hospital/_components/HospitalListStep.tsx`·`HospitalConfirmStep.tsx`·`HospitalDateTimeStep.tsx`·`GuardianHospitalContent.tsx`·`GuardianHospitalContent.browser.test.tsx` · `src/app/(ward)/ward/hospital/_components/WardHospitalContent.tsx`·`.browser.test.tsx`

## 항목별 결과

| 항목 | 판정 | 근거 |
|---|---|---|
| NBSP·`formatTimeDisplay`는 의도한 3곳에만 | PASS | grep |
| 접근성 이름(취소 버튼 aria-label·시간 버튼)·확인 창·확인 단계·완료 화면은 기존 `formatTime` 유지 - 화면 읽기 문구 그대로 | PASS | grep |
| 확인 단계(30px)·완료 화면(28px) 시각은 이미 `white-space: nowrap`, 취소 확인 창은 16px - 갈라짐 해당 없음 | PASS | CSS |
| 포커스 effect: Esc·바깥 클릭·취소 실패는 누른 버튼이 남아 그대로, 피보호자 바꾸기는 포커스가 피보호자 버튼이라 빼앗지 않음 | PASS | 코드 |
| 예약 목록 조회에 자동 갱신 주기(`refetchInterval`) 없음 | 참고 | grep |
| 기준선 tsc·lint 0 / 단위 712 / 브라우저 803 / build | PASS | 실행 |

## 이슈

| 등급 | ID | 내용 | 결과 |
|---|---|---|---|
| 🟢 | L-1 | 취소 **실패** 뒤 포커스가 누른 [예약 취소]로 돌아오는지 테스트가 확인하지 않음 | **조치** FE #305 - 단언 추가 |
| 🟢 | L-2 | 취소 실패 뒤 사용자가 빈 곳을 눌러 포커스를 비운 상태에서 나중에 목록 갱신으로 그 카드가 사라지면 포커스가 [새 진료 예약하기]로 옮겨짐(드묾) | **조치** FE #306 `68c3d2b` - 누른 버튼을 취소 성공 때만 기억하고 실패·그대로 두기·바깥 클릭·Esc 뒤에는 잊는다. 새 테스트(실패 → 포커스 비움 → 11초 뒤 창 포커스로 목록 다시 받기 → 카드 사라짐 → 포커스 그대로)는 수정 전 실패·수정 후 통과, 브라우저 전체 804건·build 통과 |
| 🟢 | L-3 | 360px 테스트가 화면 폭을 테스트 끝에서 되돌려, 중간 실패 시 뒤 테스트가 좁은 화면에서 돎(기존 테스트도 같은 방식) | **조치** FE #305 - 보호자·피보호자 병원 화면 테스트의 되돌리기를 `afterEach`로 |

## 함께 처리: 관리자 이상감지 검색 테스트 간헐 실패 (FE #305)

- `AdminAiStatsContent.browser.test.tsx` '한 글자씩 쳐도 … 요청은 한 번만'이 전체 실행에서 가끔 실패했다. 화면은 입력이 300ms 멈춰야 요청을 보내는데(`useDebouncedValue(keyword, 300)`), 테스트는 세 번 입력 사이가 늘 그보다 짧다고 가정했다.
- 재현: 실제 시계로 입력 사이를 350ms씩 늦추면 `expected … length of 1 but got 3`(같은 실패).
- 수정(테스트만, 관리자 화면 코드 그대로): `setTimeout`·`clearTimeout`만 가짜 타이머로 돌려 "299ms엔 검색 요청 0건, 300ms에 '김영희' 1건"을 본다. 같은 지연 조건에서 새 테스트 통과, 지연 없이 10회 반복 10/10, 브라우저 전체 2회 각 803건 통과.
