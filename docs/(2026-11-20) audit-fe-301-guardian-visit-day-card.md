# FE #301 보호자 예약 현황 "병원 가는 날" 카드 통일 기능 점검 (템플릿 B)

- 점검일: 2026-11-20
- 대상: FE PR #301 (머지 `1186ed0`, gosky FE dev 반영) - 점검 기준 커밋 FE develop `d572698`(#301 포함)
- 판정: **조건부 PASS** - 🔴 0 · 🟠 0 · 🟡 1 · 🟢 3

## 변경 요약

보호자 `/guardian/hospital` 예약 현황을 피보호자 병원 화면·챗봇 예약 완료 카드와 같은 디자인으로 맞췄다.

- 다음 진료 1건: 공용 `VisitDayCard`(큰 색 D-day 띠, 긴 날짜·시각, 병원·주소)를 그대로 쓴다. 선택 prop `action`을 새로 두고 보호자 화면만 [예약 취소]를 넣는다.
- 그 밖의 예약: 피보호자 "그다음" 카드와 같은 크기의 작은 카드 + [예약 취소].
- 보호자 전용 `ddayLabel`을 `VisitDayCard`의 것으로 합치고, 안 쓰는 보호자 CSS를 지웠다.

## 점검 파일

`src/components/reservation/VisitDayCard.tsx`·`.module.css` · `src/app/(guardian)/guardian/hospital/_components/HospitalListStep.tsx`·`GuardianHospitalContent.module.css`·`hospitalBooking.ts`·`GuardianHospitalContent.browser.test.tsx` · 사용처 `src/app/(ward)/ward/hospital/_components/WardHospitalContent.tsx`·`src/components/chatbot/ChatToolCard.tsx` · `src/utils/reservation/reservationView.ts`(`splitReservations`·`daysBetween`)

## 항목별 결과

| 항목 | 판정 | 근거 |
|---|---|---|
| `VisitDayCard` 사용처 = 보호자·피보호자·챗봇 3곳 | PASS | grep |
| `action` 미전달(피보호자·챗봇)은 DOM·모양 그대로 | PASS | PR 때 전후 스크린샷 md5 동일(태블릿 카드·챗봇 compact) |
| 지운 보호자 CSS 클래스가 다른 단계에서 쓰이지 않음(`.listCard`는 검색 단계용으로 유지) | PASS | 클래스별 tsx·css 짝 대조 |
| `ddayLabel` 통합 - 옛 보호자판 "0 이하 = 오늘"이 사라져도 예정 예약은 날짜 ≥ 오늘만 들어와 결과 같음 | PASS | `splitReservations` 조건 |
| 오늘(늦은 시각)·내일·N일 뒤 표기 | PASS | 임시 촬영 테스트(오늘 23:59 → "오늘") |
| 다음 진료 region 이름·[예약 취소] 접근성 이름·확인 창·Esc 포커스 복귀 | PASS | 기존·갱신 테스트(L-5 등) |
| 그 밖의 예약 취소 | PASS | 추가 테스트 |
| 글씨 확대 변수 `--ward-font-scale`은 피보호자 레이아웃(`WardLayout`)만 - 보호자 카드는 기본 크기 | PASS | grep |
| 가로 넘침 393·360(테스트)·820·1024·1440(gosky 실측) | PASS | 넘친 요소 0 |
| 긴 병원명·긴 진료과 줄바꿈 | **FAIL (M-1)** | 아래 |

## 이슈

| 등급 | ID | 내용 | 제안 |
|---|---|---|---|
| 🟡 | M-1 | 병원 이름 뒤 진료과가 다음 줄로 넘어갈 때 가운뎃점만 윗줄 끝에 남는다("강남종합병원 ·" / "소아청소년과"). `VisitDayCard`의 `· {department}`가 공백에서 끊기기 때문 - **#301 이전부터 있던 공용 카드 동작**이라 피보호자 화면에도 생긴다. 재현: 보호자 393px·gosky 820px, 피보호자 393px(기본·최대 글씨)·1024px(최대 글씨) | 가운뎃점 뒤를 줄바꿈 없는 공백(`· `)으로 붙이고, 진료과 span 앞 공백에서만 줄을 바꾸게 한다(`overflow-wrap: anywhere`는 유지). 같은 표기인 피보호자·보호자 "그다음" 카드(`rowDept`·`laterDept`)도 함께 |
| 🟢 | L-1 | "그다음" 카드에서 시각이 "오전" / "9시"로 갈라진다(393px, 피보호자 화면과 같은 CSS) | M-1과 함께 `formatTime` 결과 표시 쪽 공백을 줄바꿈 없는 공백으로 |
| 🟢 | L-2 | 예약 취소 **성공** 뒤 포커스가 [새 진료 예약하기]로 가는 경로(누른 버튼이 사라진 경우)는 테스트가 없다(Esc로 닫는 경우만 있음) | 브라우저 테스트 1건 |
| 🟢 | L-3 | `action`을 넘기지 않으면 자리가 없다는 것과 M-1 줄바꿈은 테스트로 고정되지 않았다(스크린샷으로만 확인) | M-1 수정 때 가운뎃점·진료과가 같은 줄인지 재는 테스트를 함께 |

## 테스트

- 점검 기준 커밋: tsc 0 / eslint 0 / vitest 712 / 관련 브라우저(보호자·피보호자 병원·챗봇·reservation) 221 / `next build` 통과.
- 임시 촬영 테스트(커밋 안 함): 보호자 393px 긴 이름·오늘 경계, 피보호자 카드 393px(배율 1·1.647)·1024px(1.647) - 넘침 0, 가운뎃점 단독 줄 4건 모두 재현.
- gosky 실측(시험 보호자, 로그인 200): 393·820·1024·1440px - 넘침 없음, 820px에서 M-1 재현.

## 수정용 커밋 메시지 초안 (M-1·L-1 반영 시)

`fix: 병원 가는 날 카드에서 진료과·시각이 줄 끝에서 갈라지지 않게 (#301 점검 M-1·L-1)`
