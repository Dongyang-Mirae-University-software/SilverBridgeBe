# (2026-10-24) FE 보호자 문의하기 디자인 반영(FE #121) 기능 점검

- 대상: FE PR #121 `design/inquiry` (커밋 `7944bed`, 머지 `869603e`, gosky 반영 2026-10-24)
- 템플릿: B(기능 점검), 점검만(코드 수정 없음)
- 기준: FE develop `869603e`, gosky FE `869603e`, BE dev(문의 API 변경 없음)

## 결론

**PASS** - 🔴 0 · 🟠 0 · 🟡 0 · 🟢 5

인가는 BE가 지키고(남의 문의 403, 본문 없음 - gosky 실측) 화면은 그 결과를 문구로만 보여 준다. 작성 제한(분류 필수·제목 100자·내용 2000자·빈 값 비활성·연속 클릭 방지), 등록 뒤 목록 새로고침, 상태별 오류 문구, 375/1280 바깥 문서 스크롤 없음이 모두 동작한다. 남은 것은 다음과 같다.

- 🟢 L-1: 400일 때 BE의 구체적 문구("제목을 입력해주세요.") 대신 일반 문구가 나간다.
- 🟢 L-2: 변이 6종 중 3종을 테스트가 잡지 못한다(같은 순간 연속 클릭 ref 가드·성공 후 1페이지 이동·공백 내용).
- 🟢 L-3~L-5: 표시·접근성 문제(L-3·L-4는 원래부터, L-5는 이번 PR).

## PHASE -1. 환경

| 항목 | 결과 |
|---|---|
| 날짜 | 2026-10-24 |
| develop에 `869603e` 포함 / gosky FE | 예 / `869603e` (`/guardian/inquiries` 200) |
| `npm test` / `npm run test:browser` | 277 통과 / 120 통과 (2회 연속) |
| `tsc --noEmit` / eslint(대상 파일) / `npm run build` | 통과 / 통과 / 통과 |

## PHASE 0. 대상

| 파일 | 역할 |
|---|---|
| `inquiries/page.tsx` | `GuardianInquiryContent`만 렌더(`PageLayout`은 안쪽으로 이동 - 헤더 버튼이 작성 상태를 다루기 때문) |
| `GuardianInquiryContent.tsx` | 헤더 "문의 남기기", 작성 카드 열기/닫기, 안내 띠(5초), 목록·페이지·상세 |
| `GuardianInquiryForm.tsx` | 분류 칩(radiogroup)·제목·내용·글자 수·오류 문구·보내기 |
| `GuardianInquiryCard.tsx` | 상태 알약·"M월 D일"·분류·제목·축약 본문 2줄·답변 4줄 |
| `GuardianInquiryDetailModal.tsx` | 전체 본문·답변·답변 시각, 403·404 문구 |
| `inquiryErrorMessage.ts` | 상태 코드 → 문구(400·401·403·404·429) |
| `vitest.browser.config.ts` | `dayjs`·`dayjs/locale/ko` 사전 묶기(테스트 중 Vite 재로딩 방지) |

API·쿼리·타입(`service/**/guardian/inquiry.ts`)은 바뀌지 않았다. 다른 화면에서 이 컴포넌트·라벨을 가져다 쓰는 곳은 없다(`git grep`, 메뉴 링크 `constants/dashboard.ts`만).

| 호출 | 역할 | BE 인가 |
|---|---|---|
| `POST /api/guardian/inquiry` | 작성 | GUARDIAN, 분당 5회 |
| `GET /api/guardian/inquiry` | 목록(본문 100자 축약, 답변 전문) | GUARDIAN, 본인 것만 |
| `GET /api/guardian/inquiry/{id}` | 상세 | GUARDIAN, 남의 것 403 |

## PHASE A. 보안·인가 - PASS

| 항목 | 결과 |
|---|---|
| IDOR | gosky 실측: 테스트 보호자로 남의 문의(id 181) 조회 → **403 `INQUIRY_NOT_AUTHORIZED`, `data=null`**(제목·본문 미노출). 없는 id(1)는 404 `INQUIRY_NOT_FOUND`. 화면은 403을 "본인이 남긴 문의만 볼 수 있어요."로만 보여 준다(컴포넌트 테스트로 고정) |
| 목록 범위 | 테스트 계정 목록에 본인 문의 2건만(182·183) |
| XSS | `dangerouslySetInnerHTML` 없음, 본문·답변은 React 텍스트 + `white-space: pre-wrap` |
| 로그·저장소 | `console.*`·`localStorage`·`sessionStorage` 0건(대상 폴더 `git grep`) |
| 입력 검증 | 화면은 `maxLength` 100/2000·trim 빈 값 차단. BE와 길이 단위가 같다(JS `length`·Java `@Size` 모두 UTF-16) |

## PHASE B. 기능 정합성

| 확인 | 결과 |
|---|---|
| 보내기 비활성 | 분류 미선택·제목/내용 공백·등록 중이면 비활성(테스트) |
| 연속 클릭 | 버튼 비활성 + 같은 순간 클릭용 ref 가드. `dblClick`에서 호출 1회(테스트) |
| 등록 성공 | 카드 닫힘 → 안내 띠(`role=status`) → 1페이지 목록 새로고침(mutation `invalidateQueries` + `setPage(0)`). gosky 실측 동일 |
| 오류 문구 | 400·401·429는 작성 카드 안 `role=alert`, 403은 상세. 실패해도 입력 유지(테스트) |
| 목록/상세 | 목록은 BE 축약 본문(테스트 계정 최대 63자), 전체는 상세에서만. 답변 대기/완료 구분(테스트·gosky) |
| 레이아웃 | gosky 375×812·1280×800 모두 `scrollHeight == innerHeight`, `scrollWidth == innerWidth`. absolute 요소 없음(모달은 `fixed` 오버레이) |
| 보이지 않는 문자 제목 | gosky 실측: 제목 `​​` → BE 400 `INVALID_INPUT` "제목을 입력해주세요."(생성 안 됨). 화면은 trim으로 지워지지 않아 버튼이 켜지고 일반 문구가 나온다 → **L-1** |

## PHASE C. 구조·계약

- 오류 판정은 `ApiErrorShape.status`로만 한다(서버 문구 파싱 없음). 응답 `code`는 쓰지 않는다.
- `GuardianInquiryForm`의 `onCancel`이 필수 prop이 됐지만 다른 사용처가 없다(타입 체크 통과).
- 라벨 상수(`INQUIRY_CATEGORY_LABEL`·`INQUIRY_STATUS_LABEL`)는 Card에서 내보내 Form·Modal이 가져간다. 순환 import 없음.
- 상태 문구는 디자인을 따라 "답변 대기" → **"답변 기다리는 중"**으로 바뀌었다(이 화면 안에서만 쓰임).

## PHASE D. 테스트 - 변이 시험

`GuardianInquiryContent.browser.test.tsx` 9건(375px Chrome)에 대해 방어 코드를 하나씩 지워 봤다.

| 변이 | 결과 |
|---|---|
| M1 같은 순간 연속 클릭 ref 가드 제거 | **안 잡힘** - `dblClick` 두 번째 클릭이 이미 비활성 버튼에 떨어진다 |
| M2 버튼 비활성 + ref 가드 제거 | 잡힘(2건 실패) |
| M3 등록 성공 뒤 `setPage(0)` 제거 | **안 잡힘** - 테스트가 1페이지에서만 등록한다 |
| M4 429 문구 제거 | 잡힘 |
| M5 분류 미선택 허용 | 잡힘 |
| M6 내용 trim 제거(공백 내용 허용) | **안 잡힘** - 공백 내용 단계에서 분류가 비어 있어 원래 비활성이다 |

## 이슈

### 🟢 L-1 400 문구가 어떤 칸이 문제인지 말하지 않는다

보이지 않는 문자만 넣은 제목이나 제어문자가 섞인 제목은 화면 검사를 통과하고, BE가 400과 함께 "제목을 입력해주세요."처럼 칸별 문구를 준다. 화면은 이를 "입력한 내용을 다시 확인해 주세요."로 바꿔 어느 칸인지 알 수 없다. 흔한 입력은 아니다.
- 제안: 400이고 `code === 'INVALID_INPUT'`이면 BE 문구를 그대로 보여 준다(BE 검증 문구는 사용자용 한국어). 문구 파싱이 아니라 코드 분기다. 오류 문구는 입력을 고치면 지운다.

### 🟢 L-2 테스트가 놓치는 변이 3종

- M1: 버튼 요소에 `click()`을 같은 순간 두 번 보내 호출 1회를 확인한다.
- M3: 2페이지로 넘긴 뒤 등록하고 `getGuardianInquiries`가 `{ page: 0 }`로 다시 불리는지 본다.
- M6: 분류·제목을 채운 상태에서 내용만 공백이면 비활성인지 본다.

### 🟢 L-3 2페이지 이후 빈 목록 문구 (원래부터)

문의가 정확히 20건이면 "다음"이 보이고, 누르면 빈 페이지에 "아직 남긴 문의가 없어요"가 나온다. 2페이지 이후에는 "더 이상 문의가 없어요"가 맞다. 목록 응답에 전체 건수가 없어서 생기는 한계다.

### 🟢 L-4 상세 모달 키보드·스크린리더 (원래부터)

열 때 포커스가 모달로 옮겨지지 않고, Esc로 닫히지 않으며, 닫은 뒤 포커스가 카드로 돌아오지 않는다.

### 🟢 L-5 분류 칩 키보드 이동 (이번 PR)

`role=radiogroup`·`radio`로 바꿨지만 방향키 이동은 없고 칩마다 Tab이 멈춘다. 마우스·터치 사용에는 영향이 없다.

## 실측 중 남은 데이터

테스트 보호자 계정에 문의 2건이 남았다(관리자 화면 없이 삭제 불가).

- id 182 "[테스트] 문의하기 화면 확인용 - 답변 불필요" - PR #121 gosky 확인용
- id 183 "[테스트] 탭 검증 - 생성되면 안 됨" - 이 점검에서 내용에 탭을 넣으면 400이 날 것으로 예상하고 보냈으나, BE는 본문의 `\t \n \r`을 **의도적으로 허용**한다(`@NoControlChars(allowLineBreaks = true)`, `TextSanitizer.containsControlChars`). 결함이 아니라 점검자의 예상이 틀렸다.

## 수정용 커밋 메시지 초안 (FE)

```
fix: 문의하기 점검 후속 - 400 칸별 문구·테스트 보강

- 400 INVALID_INPUT이면 BE 검증 문구를 그대로 보여 주고, 입력을 고치면 오류를 지운다 (L-1)
- 같은 순간 연속 클릭·2페이지 등록 후 1페이지 이동·공백 내용 비활성 테스트 (L-2)
- 2페이지 이후 빈 목록 문구, 상세 모달 포커스·Esc (L-3·L-4)
```

## 조치 (2026-10-24)

| 항목 | 조치 |
|---|---|
| L-1 | FE #124 + #125 - 400 `INVALID_INPUT`이면 BE 문구, 입력을 고치면 오류를 지움 |
| L-2 | FE #124 - 같은 순간 연속 클릭·2페이지 등록 후 1페이지·공백 내용 테스트 |
| L-3 | FE #124 - 넘긴 빈 페이지 "더 이상 문의가 없어요" |
| L-4 | FE #124 - 상세 모달 포커스 이동·Esc·Tab 가두기·닫으면 카드로 복귀 |
| L-5 | FE #124 - 분류 칩 roving tabindex, 방향키·Home·End로 선택, 포커스 테두리 |

- **조치 중 발견**: #124의 L-1은 gosky 실측에서 일반 문구로 나왔다. `apiClient`는 `AxiosError`를 그대로 reject하고 `message`만 서버 문구로 바꾸므로 `error.code`는 axios 코드(`ERR_BAD_REQUEST`)다. 테스트는 `{ status, code, message }` 평범한 객체 목이라 통과했다. FE #125에서 `response.status`·`data.code`·`data.message`로 읽고(카메라·OCR 화면과 같은 방식) 테스트 목을 실제 `AxiosError`로 바꿨다. 상태 코드 문구는 `AxiosError.status`가 있어 원래도 맞게 동작했다.
- 변이 12종(점검 때 놓친 3종 + 새 방어 8종 + 옛 헬퍼 되돌리기) 전부 테스트가 잡는다.
- gosky 재실측(375): 400 → "제목을 입력해주세요.", 제목을 고치면 사라짐, 모달 열면 포커스·Tab 4회 뒤에도 모달 안·Esc로 닫히고 카드로 복귀, 칩 tabindex `0 -1 -1 -1 -1`·방향키/End 선택, 바깥 스크롤 없음. 보낸 요청은 400 1건뿐이라 새 문의는 생기지 않았다.
- 참고(범위 밖, 미수정): `apiClient.ts` 끝의 사용 예시 주석이 `error as ApiErrorShape`를 권한다. `status`·`message`는 맞지만 `code`는 서버 코드가 아니다.
