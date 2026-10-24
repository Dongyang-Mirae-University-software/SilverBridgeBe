# (2026-10-24) FE 화면 아래 빈 띠(숨긴 input이 문서를 늘림) 수정 기능 점검

- 대상: FE PR #114 `fix/layout-hidden-input-overflow` (커밋 `8bda270`, 머지 `386ac52`, gosky 반영 2026-10-24)
- 템플릿: B(기능 점검), 점검만(코드 수정 없음)
- 기준: FE develop `386ac52`, gosky FE `386ac52`

## 결론

**조건부 PASS** - 🔴 0 · 🟠 1 · 🟡 1 · 🟢 2

빈 띠 원인(숨긴 input이 문서 기준으로 놓임)은 고쳐졌다. 보호자 13개 화면에서 바깥 문서가 늘지 않고, 안전망 `.stage`에 기대는 요소도 없다. 남은 것은 다음과 같다.

- 🟠 1건(H-1): 이 PR이 새로 만든 회귀다. 피보호자 보호자 목록의 inline 새로고침 버튼이 16px 밀린다.
- 🟡 1건: 원래부터 있던 문제다. 복약 토글에 스크린리더 이름과 포커스 표시가 없다.
- 🟢 2건: 재발 방지 테스트가 없고, 로그인·가입 화면 스크롤 영역에 안전망이 없다.

## PHASE -1. 환경

| 항목 | 결과 |
|---|---|
| 날짜 | 2026-10-24 |
| develop에 `386ac52` 포함 | 예 |
| `npm test` / `npm run test:browser` | 274 통과 / 104 통과 |
| `tsc --noEmit` / `npm run build` | 통과 / 통과 (PR 브랜치에서) |
| 린트 | 기존 `BirthDateSelects.tsx` 오류 1·경고 9 그대로 (무관) |

## PHASE 0. 대상

`position: relative`를 새로 받은 선택자 7개:

| 파일 | 선택자 | 사용 화면 |
|---|---|---|
| `WardMedicationCard.module.css` | `.switchField` | 보호자 복약 |
| `WardBasicSettingsSection.module.css` | `.contrastRow` | 피보호자 설정 |
| `SignupRoleSelector.module.css` | `.roleCard` | 회원가입 |
| `RefreshButton.module.css` | `.button.inline` (`static` → `relative`) | 피보호자 보호자 목록(`WardGuardianPendingSection`·`WardGuardianActiveSection`) |
| `GuardianConnectionRequestHistory.module.css` | 640px 이하 `.table` | 보호자 피보호자 등록 |
| `GuardianLayout.module.css` · `WardLayout.module.css` | `.stage` | 보호자·피보호자 전 화면 (안전망) |

## PHASE A. 보안·인가

해당 없음 - CSS만 바뀌었고 API 호출·데이터·로그를 건드리지 않는다.

## PHASE B. 기능 정합성

### B-1. 새로 positioned가 된 요소에 숨어 있던 offset·z-index

`static`에서는 `top·right·bottom·left·z-index`가 무시되지만, `relative`가 되면 적용된다. 7개 선택자에 상속되거나 같이 걸리는 규칙을 확인했다.

- `.switchField`·`.contrastRow`·`.roleCard`·`.table`·`.stage`: offset·z-index 없음 → 위치 변화 없음
- **`.button.inline`: `.button`의 `right: 16px`·`bottom: calc(16px + env(safe-area-inset-bottom))`·`z-index: 50`을 그대로 받는다 → H-1**

### B-2. 안전망 `.stage` 영향 (gosky 실측, Playwright, 1366×768·375×667)

보호자 13개 화면: 대시보드·복약·이상감지·환경설정·피보호자 목록·등록·SOS·병원·문의·공지·게임·정서·챗봇.

| 확인 | 결과 |
|---|---|
| 바깥 문서 높이 = 화면 높이 | 26건 중 25건 같음. 남은 1건(복약 375)은 `.medicationStage` 구조라 원래 스크롤되고, 문서 끝 = 내용 끝(1268/1268)이라 빈 띠 없음 |
| `.stage`를 기준으로 삼는 absolute 요소 | 0개 - 안전망에 기대는 요소 없음. 문서 기준으로 놓인 absolute는 `SCRIPT`·`NEXT-ROUTE-ANNOUNCER`(Next 내부)뿐 |
| 챗봇 375의 `.chatStage` | `position: fixed` 유지(뒤 선언이 이김), 높이 667 = 화면 |
| fixed 요소(모달 overlay·사이드바·스크림·떠 있는 새로고침) | `.stage`에 transform·filter가 없어 기준이 바뀌지 않음 |

피보호자 화면은 피보호자 테스트 계정이 없어 실측하지 못했다. 대신 CSS로 판정했다. 피보호자 쪽 absolute 요소는 `WardGuardianCallSection .cardLink`·`WardSettingsContent` 토글(부모 relative), `CameraRegisterModal`(fixed overlay 안), `.contrastRow input`(이번에 고침), inline 새로고침(H-1)이 전부다.

### B-3. 다른 안쪽 스크롤 영역

`overflow: auto|scroll`인 영역 13곳 중 position이 없는 곳은 다음과 같다.

- 모달 본문 4곳(`ProfileModal`·`MedicationAddModal`·`CameraRegisterModal`·`CameraSceneModal`): 바깥 overlay가 fixed라 기준이 있다.
- 챗봇 목록 2곳·사이드바 nav: 안에 absolute가 없다.
- `AuthShell .formPane/.shell`: L-2.

### H-1 🟠 inline 새로고침 버튼이 왼쪽·위로 16px 밀림 (이 PR의 회귀)

- 실측: 실제 `RefreshButton.module.css`로 같은 마크업을 그렸다. static이면 (48, 48), relative면 (32, 32)로 `right`·`bottom` 16px만큼 밀린다. z-index 50 쌓임도 새로 생긴다.
- 영향: 피보호자 보호자 목록 화면 2개 섹션 머리의 새로고침 버튼이 원래 자리에서 어긋나 옆 글자와 겹칠 수 있다. 떠 있는 기본 모드(fixed)는 영향이 없다.
- 놓친 이유: 구현 때 offset이 없는 규칙만 봤고, 상속되는 `.button` 규칙을 함께 보지 않았다. 피보호자 화면은 실측하지 못했다.
- 조치 제안: `.button.inline`에 `inset: auto; z-index: auto;`를 더한다. 숨긴 라벨을 가두는 `relative`는 그대로 둔다.

## PHASE C. 접근성

### M-1 🟡 복약 토글 3개에 스크린리더 이름·포커스 표시 없음 (원래부터)

- `<label class=switchField>` 안에 체크박스와 `aria-hidden` 트랙만 있다. 제목("복약 시간 알림" 등)은 바깥 `settingsMeta`에 있어 이름으로 연결되지 않는다(실측 이름 빈 값).
- 포커스 상태에서도 트랙의 outline·box-shadow가 `none`이라 키보드 사용자가 어디에 있는지 보이지 않는다.
- 이 PR과 무관하다(PR은 접근성 속성을 바꾸지 않았다).
- 조치 제안:
  - 체크박스에 `aria-label`(또는 제목에 `id`를 주고 `aria-labelledby`)
  - `.switchField input:focus-visible + .switchTrack`에 outline

다른 숨긴 input은 문제가 없다. 역할 카드 radio·고대비 체크박스는 label 안의 글자로 이름이 붙고, 새로고침은 `.label` 글자로 이름이 붙는다. 회원가입 역할 radio는 Tab·Space·화살표로 동작함을 확인했다.

## PHASE D. 테스트

### L-1 🟢 재발 방지 테스트 없음

CSS 위치 문제는 단위 테스트(node)로는 잡히지 않는다. 브라우저 테스트(`*.browser.test.tsx`, `WardMedicationCard.browser.test.tsx`가 이미 있음)에 다음을 제안한다.

- `RefreshButton` inline을 상자 안에 그려 버튼 위치가 흐름 위치와 같은지 (H-1 고정)
- `WardMedicationCard`를 `position` 없는 높이 300px 스크롤 상자 안에 그려 `document.documentElement.scrollHeight <= innerHeight`인지 (이번 원인 고정)

### L-2 🟢 로그인·가입 스크롤 영역(`AuthShell`)에 안전망 없음

`.formPane`·`.shell`이 안쪽 스크롤 영역인데 position이 없다. 지금은 안의 absolute가 모두 positioned 부모를 가져 증상이 없다. `.stage`와 같은 안전망을 줄지는 선택이다(영향 확인 필요 - `.hero` 원 장식은 `.hero`가 기준이라 무관).

## 조치 제안 묶음

| ID | 내용 | 크기 |
|---|---|---|
| H-1 | `.button.inline { inset: auto; z-index: auto; }` + 브라우저 테스트 | CSS 1줄 + 테스트 1 |
| M-1 | 복약 토글 `aria-label` 3개 + `:focus-visible` outline | TSX 3곳 + CSS 1규칙 |
| L-1 | 복약 카드 문서 높이 브라우저 테스트 | 테스트 1 |
| L-2 | `AuthShell` 안전망 | 선택 |

수정용 커밋 메시지 초안: `fix: inline 새로고침 버튼이 떠 있는 버튼 위치값을 받아 16px 밀리던 문제` / `fix: 복약 알림 토글에 스크린리더 이름과 키보드 포커스 표시 추가`
