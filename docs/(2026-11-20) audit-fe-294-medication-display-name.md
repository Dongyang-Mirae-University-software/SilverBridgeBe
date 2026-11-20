# 기능 점검(B) - FE #294 복약 화면 약 표시 이름(displayName) (2026-11-20)

- 대상: FE `bbd415f`(머지 `c944d7c`) - 어르신 오늘 먹을 약·보호자 복약 카드가 BE #503의 `displayName`(없으면 `name`)을 표시. FE 자체 축약(`wardMedicationDisplayName(s)`) 제거.
- 기준: FE `origin/develop` `c944d7c` = gosky FE dev 반영본, BE `dev`(#503·#504 반영, gosky API 응답에 `displayName` 확인). 템플릿 B, 코드 수정 없음.
- 방법: 코드 읽기 + BE 계약 대조 + 바꿔 보기 9건(임시 worktree, 커밋 안 함) + gosky 실측(시험 어르신·보호자, 조회만, 375·1024px)
- 근거: `docs/(2026-11-19) feature-medication-display-name.md`(FE 계약), `.claude/rules/policy-medication.md` "표시 이름"

## 종합 판정

**PASS - 🔴 0 · 🟠 0 · 🟡 0 · 🟢 3.** BE 계약(표시는 `displayName`, 수정 창 입력값은 원본 `name`, OCR 응답 원본)을 FE가 그대로 지키고, 새 테스트가 바꿔 보기 9건을 모두 잡는다.

## PHASE -1 환경

| 항목 | 결과 |
|---|---|
| FE develop · gosky FE dev | 둘 다 `c944d7c`(dev 서버 재기동 후 Ready) |
| PR 시점 검증 | eslint·tsc 0, 단위 80파일/712건, 브라우저 70파일/770건, `next build` 통과(첫 실행은 로컬 next 16.4.3 보안 경고로 멈춤, 재실행 통과) |
| gosky API | 보호자 23건·어르신 5건 모두 `displayName` 포함 |

## PHASE A 계약·데이터

| 확인 | 결과 |
|---|---|
| 표시 = `displayName`, 없으면 `name` | PASS - `medicationDisplayName()` 한 곳(공백뿐인 값도 `name`으로) |
| 수정 창 입력값 = 원본 | PASS - `WardMedicationCard`가 `target`(원본 `name`)을 그대로 `MedicationFormModal initial`로 넘김, 제출도 `value.name` |
| OCR 확인 화면 원본 | PASS - `MedicationDraftForm`·`medicationDraft.ts` 무변경 |
| WS `medication-taken` 캐시 갱신 | PASS - 두 캐시 모두 기존 항목을 펼쳐(`...medication`) `taken`·`takenAt`만 바꿈 → `displayName` 유지. `medicationDisplayName`은 화면에 쓰지 않아 미사용 |
| 대시보드 `mostUrgent` | 해당 없음 - FE 화면이 그리지 않음(타입에 `displayName?`만 추가) |
| 남은 이름 표시 지점 | 없음 - 약 이름을 그리는 곳은 두 화면 + `summarizeMedicationNames`(테스트에서만 사용)뿐, 옛 축약 함수 참조 0건 |
| 옛 BE 호환 | PASS - 필드가 없으면 원래 이름(축약 없음). prod FE를 나중에 재빌드해도 BE가 옛 버전이면 원래 이름으로 보일 뿐 |

## PHASE B 바꿔 보기 (9/9 검출)

| # | 바꾼 것 | 잡은 테스트 |
|---|---|---|
| M1 | 어르신 화면이 `name` 표시 | 어르신 짧은 이름 · 긴 이름 줄바꿈 |
| M2 | 보호자 카드가 `name` 표시 | 카드 짧은 이름·수정 창 원본 |
| M3 | 함수가 `displayName` 무시 | 유틸 "displayName 이 있으면" |
| M4 | 함수 대체값 없음(`?? ''`) | 유틸 옛 응답 2건 |
| M5 | 수정 창에 짧은 이름 전달 | 카드 수정 창 원본(+ 뒤 테스트 연쇄 실패 - L-2) |
| M6·M7 | 원본 `title` 제거(어르신·보호자) | 각 화면 title 확인 |
| M8 | 요약이 `name` 사용 | 요약 유틸 |
| M9 | 어르신 이름 줄바꿈 CSS 제거(`nowrap`) | 긴 이름 줄바꿈(칸 오른쪽 경계) |

## PHASE C gosky 실측 (조회만, 복용 체크 요청은 막고 진행)

| 화면 | 375px | 1024px |
|---|---|---|
| 어르신 `/ward/medication` | `아모디핀정`·`메트포르민정`(title 원본 `…5mg`·`…500mg`), 가로 넘침 없음 | 같음 |
| 보호자 `/guardian/medication` | `신일이부프로펜정`(title `…400mg`) 외 5건 원래 이름과 같음, 가로 넘침 없음 | 같음 |

예전 FE 규칙(8자 이하는 그대로)에서는 `아모디핀정5mg`이 줄지 않았다 - 이제 BE 규칙대로 줄어든다.

## 발견 (🟢만)

| # | 내용 | 판단 |
|---|---|---|
| L-1 | 용량만 다른 같은 약이 한 칸에 있으면 두 줄 모두 같은 짧은 이름(예: 다이아벡스정 ×2)이고, 보호자 카드의 수정·삭제 버튼 aria-label도 같아진다. 원본은 `title`(마우스 올림)에만 있어 터치 화면에서는 구분이 양(1정·반 알)뿐 | **수용** - BE 사용자 결정("용량이 겹쳐도 늘 짧은 이름"). 11/16 점검 L-1(FE "겹치면 원래 이름")은 이 결정으로 대체됐다 |
| L-2 | 카드 브라우저 테스트는 수정 창을 테스트 안에서 닫는다 - 단언이 실패하면 창이 남아 뒤 테스트가 연쇄로 시간 초과(M5에서 확인). 실패 원인 파악만 어렵게 할 뿐 오검출은 없음 | **조치**(FE #297 `9fd6f7a`) - `ModalOutlet`이 렌더할 때 `resetModal()`. M5 재실행 시 실패 2건 → 1건 |
| L-3 | 보호자 카드의 긴 이름 줄바꿈은 테스트가 없다(CSS `overflow-wrap: anywhere`는 있음, gosky 실측 넘침 없음) | **조치**(FE #297) - 보호자 카드 긴 이름 줄바꿈 테스트 추가, `.medicationName`을 `nowrap`으로 바꾸면 실패 확인 |
