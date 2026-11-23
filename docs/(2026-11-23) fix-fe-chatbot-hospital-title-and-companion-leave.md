# (2026-11-23) FE #336 - 챗봇 병원 검색 카드 제목, 말벗 대화 이탈 결정 (FE 디자인 점검 D-2·D-4·D-7)

- 근거: `(2026-11-23) audit-fe-design-companion-chatbot-326-333.md` (D-2·D-4·D-7)
- FE PR: https://github.com/Dongyang-Mirae-University-software/SilverBridgeFe/pull/336 (머지 `6d55c8a`, 머지 직전 develop `30e4585` - 함께 실린 다른 PR 없음)
- gosky dev 반영: `6d55c8a` (소스를 마운트한 `next dev`라 pull로 반영). prod는 건드리지 않음
- BE·AI 코드 변경 없음

## 사용자 결정 (2026-11-23)

| 항목 | 결정 |
|---|---|
| D-2 대화 중 화면 이탈 | (나) 지금대로 - 기기는 마치기를 부르지 않고 10분 뒤 `NO_RESPONSE`로 닫힌다, 60분 창 안이면 이어 가기. `policy-companion.md` "수용한 한계"에 기록 |
| D-4 병원 목록 제목 | 제목만 "병원 N곳"으로, 병원별 진료과 칩은 그대로 |
| D-7 공용 머리말 터치 크기 | 이번엔 뺀다(모든 대시보드 머리말이 함께 바뀌어 따로 다룬다) |

## 전제 확인 (PHASE 0)

- D-2: FE에서 `finishWardCompanionSession`을 부르는 곳 0곳. BE 마감 스케줄러는 10분 무활동 대화를 닫고, AI가 `USER_STOP`으로 주더라도 `CompanionResult.asAbandoned`가 `NO_RESPONSE`로 바꾼다.
- D-4: AI 예약 오케스트레이터는 `draft.department or draft.location`이면 검색하고, 지역만으로도 `search_hospitals(region, "")`를 부른다. 카드(`search_hospital`) 데이터는 병원 배열뿐이고 검색에 쓴 진료과가 없다 - 공통 진료과가 "고른 과"라는 보장이 없다.
- D-7: "홈으로 이동"(37px)·"메뉴 닫기"(32px)는 `SidebarLayout`(피보호자·보호자·관리자 공용).

## 바뀐 것 (D-4)

- `ChatToolCard` 제목 "정형외과 병원 5곳" → "병원 5곳". 병원별 칩(공통 진료과, 없으면 "진료과 N개")은 그대로.
- `hospitalListView.ts` 주석의 "검색은 고른 진료과로 걸러 온다" 가정 정정.

## 검증

- 단위 723 / 브라우저 841 통과(WSL Node 22 + Chrome), `tsc --noEmit`·eslint 오류 없음
- 챗봇 카드 실제 화면은 유료 AI 호출이 필요해 실측하지 않음(브라우저 테스트로 확인)

## 남긴 것

- D-6 시험 환경(gosky alpine node의 작은 ICU) - WSL Node 22로 돌리면 된다(메모만)
- D-7 공용 머리말 터치 크기 - 별도 작업

## 정리

- FE worktree·로컬·원격 브랜치, `~/.local/node22`(사용자 지시대로 사용 후 삭제)를 지웠다. 남은 시험 컨테이너 없음. 로컬 FE `develop` `6d55c8a`로 fast-forward.
- 디스크 `df -h /`: gosky 81% → 81%, 로컬 WSL 5%.
