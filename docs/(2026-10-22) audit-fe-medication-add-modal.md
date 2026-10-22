# (2026-10-22) FE 약 추가 모달 기능 점검 (템플릿 B)

- 대상: SilverBridgeFe PR #83 (`e510769`, 머지 `d3d1dea`, gosky FE 반영 확인)
  - 보호자 "약 추가" 모달 - [카메라로 찍기]·[직접 쓰기] 두 탭이 같은 양식(약 목록 × 드시는 시간 목록)을 쓰고, 일괄 등록 API를 1회 호출
  - 약 수정 모달의 복용량 칸을 반알(0.5)과 최대 99로 확장
- 방법: 작성자와 분리된 하위 에이전트(Sonnet)가 코드만 받아 독립 점검했다. 메인 세션이 Medium 이상 항목을 코드로 재확인했다.
- 별도 검증(구현 단계, 2026-10-22): vitest 123건, `next build` 통과. Chromium 375px·1280px에서 목 API 46개 시나리오 통과. gosky 실서버에서 FE 프록시를 거친 OCR 호출로 합성 메모 이미지 → 200 `readable=false`(2.4초), 잘못된 wardId → 403 `MEDICATION_NOT_AUTHORIZED`를 확인했다.
- 코드 수정 없음(점검만). 이슈 11건은 같은 날 FE PR #84(`7cbb32f`, 머지 `b73513a`, gosky FE 반영)에서 모두 조치했다 - 맨 아래 "조치 결과" 참고.

## 점검 파일

- FE `src/app/(guardian)/guardian/medication/_components/`: `MedicationAddModal.tsx`, `MedicationDraftForm.tsx`, `MedicationAddModal.module.css`, `MedicationFormModal.tsx`, `WardMedicationCard.tsx`
- FE `src/utils/medication/`: `medicationDraft.ts`, `ocrImage.ts`와 각 테스트
- FE `src/service/{api,interface,query}/.../medication.ts`
- FE `src/lib/api/apiClient.ts`, `errorHandler.ts`, `reportError.ts`, `src/app/api/[...path]/route.ts`
- BE 계약: `GuardianMedicationOcrController`, `MedicationBatchCreateRequest`, `MedicationOcrResponse`, `MedicationTimeSlot`, `MedicationOcrService`, `ErrorCode`

## 요구사항 판정

| # | 요구사항 | 판정 | 근거 |
|---|---|---|---|
| 1 | 인식 결과는 초안, 자동 등록 없음 | PASS | 인식 결과는 `setDraft`로만 반영되고, 등록은 제출 버튼의 일괄 등록 mutation 하나뿐이다 |
| 2 | 사진·결과 저장·로그·분석 없음, object URL 해제 | PASS | storage·console·오류 보고(`reportError`) 경로가 없다. 폴백 object URL은 `finally`에서 revoke하고, ImageBitmap은 `close()`, canvas는 0 크기로 줄인다 |
| 3 | `capture=environment` 파일 입력, 방향 반영, 긴 변 1568px 이하, JPEG 0.88 | PASS | `ocrImage.ts` `prepareOcrImage`·`fitWithin`. 실시간 미리보기(getUserMedia)는 쓰지 않는다 |
| 4 | perDose·slots·timesPerDay·confidence 매핑 | PASS | `ocrResultToDraft`. timesPerDay 4는 4슬롯으로 넓혔다(요구는 3까지) |
| 5 | 실패 안내 | PASS (M-2 참고) | readable=false·0개, 400, 413, 429, 503·504·시간 초과를 구분한다. 403은 서버 문구를 쓴다 |
| 6 | 일괄 등록 검증, 실패 시 양식 유지, 부분 등록 없음 | PASS (L-4 참고) | 약 1~20개·시각 1~6개, 이름 100자, 빈 이름 금지, 이름·시각 중복 금지, 0.5~99, 메모 100자. BE는 한 트랜잭션이다 |
| 7 | 버튼 문구·비활성 | PASS | `getSubmitLabel`·`canSubmitDraft`. 마지막 행의 ×는 비활성 |
| 8 | 수정 모달(PATCH) 회귀 없음 | PASS | 정수 1 → 0.5, 1.5 → 2·1, 상한 99. BE `MedicationUpdateRequest`도 `@DoseAmount`를 쓴다 |

인가는 BE가 판정한다(GUARDIAN + `isActiveConnection`). FE는 wardId를 카드 데이터에서만 넘긴다. IDOR 실호출(403)도 위에서 확인했다.

## 이슈

🔴 Critical 0 · 🟠 High 0 · 🟡 Medium 5 · 🟢 Low 6

### 🟡 Medium

- **M-1 읽는 중 탭을 바꾸면 직접 쓴 내용이 인식 결과로 덮어써진다** (확인)
  - `MedicationAddModal.tsx`의 탭 버튼은 등록 중일 때만 비활성이다. 읽는 중에 [직접 쓰기]로 옮겨 입력하는 사이 응답이 오면 `setDraft(recognized)`가 입력을 지운다.
  - 조치안: 읽는 중에는 탭을 비활성화하거나, 직접 쓰기 탭에 있을 때 도착한 결과는 반영하지 않는다. 결과 화면의 [다시 찍기]가 고친 내용을 덮는 것은 의도된 동작이다(새 사진 = 새 초안).
- **M-2 읽는 중 모달을 닫고 바로 다시 찍으면 429가 "잠시 후 다시 시도해 주세요"로만 보인다** (확인)
  - FE 프록시(`src/app/api/[...path]/route.ts`)는 `request.signal`을 `fetch`에 넘기지 않는다. BE는 AI 응답까지 1인 동시 1건 슬롯을 쥔다(`MedicationOcrService`의 `slots.acquire`, 최대 40초).
  - 그래서 닫은 뒤 다시 찍으면 `MEDICATION_OCR_LIMIT_EXCEEDED`(429)가 난다. 프록시에 취소를 넘겨도 BE 슬롯은 AI 응답 전까지 풀리지 않으므로 이 쪽 수정만으로는 해소되지 않는다.
  - 조치안: 429 중 `MEDICATION_OCR_LIMIT_EXCEEDED`는 서버 문구("이전 사진을 읽는 중입니다…")를 보여준다.
- **M-3 등록한 반 알 약이 목록에 "0.5정"으로 보인다** (확인, PR 본문에 범위 밖으로 적어 둔 항목)
  - 위치: FE `guardian/.../WardMedicationCard.tsx`, `ward/.../WardMedicationCard.tsx`, `ward/.../WardMedicationContent.tsx`, `utils/format/medication.ts` `getMedicationSummaryText`
  - BE 사용자 문구는 "반 알"이고 입력 양식은 "반알"이라 서로 어긋난다.
  - 조치안: 4곳을 공용 포맷 함수 하나로 바꾼다(예: 0.5 → "반 알", 1.5 → "1알 반", 정수 → "N정"). 피보호자 화면이 함께 바뀐다.
- **M-4 약마다 다른 복용 안내가 첫 번째 약의 안내로 통일된다** (확인, 설계 시 알려진 한계)
  - 일괄 등록 `memo`는 요청 전체에 하나뿐이라 A약 "식후 30분", B약 "취침 전"이면 모두 "식후 30분"이 된다. 초안에 보이므로 보호자가 고칠 수는 있다.
  - 조치안: 약마다 안내가 다르면 메모를 비우고 "약마다 복용 안내가 달라 비워 두었어요"라고 알린다.
- **M-5 인식된 약이 20개를 넘으면 조용히 자른다** (확인, 발생 가능성 낮음 - BE가 최대 20개를 준다)
  - 조치안: 잘렸을 때 결과 문구에 "약 20개까지만 가져왔어요"를 덧붙인다.

### 🟢 Low

- **L-1** 등록 중에도 ×·바깥 클릭으로 닫힌다. 등록은 계속되지만 실패 문구를 볼 수 없다. 기존 수정 모달과 같은 구조다.
- **L-2** 등록 실패 문구가 양식을 고쳐도 다음 제출 전까지 남는다.
- **L-3** 투명 PNG를 JPEG로 바꾸면 배경이 검게 된다(흰색 채우기 없음). 카메라 사진은 영향이 없고 스크린샷 업로드만 해당한다.
- **L-4** 이름 중복 판정이 BE와 조금 다르다. FE는 `trim().toLowerCase()`만 하고, BE는 `TextSanitizer` 정규화 후 비교한다. 연속 공백·보이지 않는 문자가 섞이면 BE가 400을 내고, 양식은 유지되며 서버 문구가 보인다.
- **L-5** 쓰이지 않는 코드가 남았다. `addWardMedication`·`useAddWardMedicationMutation`, `MedicationFormModal`의 추가(`initial` 없음) 분기가 그렇다.
- **L-6** 접근성: 탭에 `aria-controls`·`tabpanel`·화살표 이동이 없다. 모달 포커스 이동·트랩·Esc 닫기가 없다(기존 모달과 같음). 인식 확신 낮음 표시가 색으로만 구분된다.

## 확인한 정상 항목

- 프록시가 multipart 본문을 그대로 넘기고 `content-type`(boundary)을 유지한다. 실서버 호출로도 확인했다.
- axios는 FormData일 때 수동으로 지정한 `Content-Type`을 브라우저 boundary로 바꾼다(`user.ts`·`streamSession.ts`와 같은 선례).
- FE 시간 제한(45초)이 BE 처리 상한(40초)보다 길어서 BE 504가 먼저 도착한다.
- 언마운트 시 요청을 취소하고, 취소 뒤에는 상태를 갱신하지 않는다. 늦게 온 이전 응답도 무시한다.
- 시간 선택은 다른 행이 쓴 시각을 고를 수 없어 중복이 생기지 않는다.
- 타입이 BE DTO의 필드·널 허용 여부와 일치한다(`doseTime` `"HH:mm:ss"`).

## 테스트 공백

- 순수 함수
  - 21개 이상 자르기, 100자 초과 이름·메모 자르기
  - timesPerDay 0·4·5 이상
  - perDose 음수·NaN·0.25·99 초과
  - `getOcrErrorMessage`의 400·429 두 종류
- 컴포넌트 수준(현재 vitest 환경이 node라 없음)
  - 탭 전환 중 결과 반영
  - 실패 시 양식 유지
  - 언마운트 시 취소
  - 수정 모달 0.5·1.5 경계
  - 이 항목들은 구현 단계에서 브라우저 목 시나리오로만 확인했다.

## 종합 판정

조건부 PASS. 요구사항 8개를 모두 충족하고 보안·개인정보 측면에서 막을 사유가 없다. 후속으로 M-1(탭 전환 덮어쓰기)·M-3("0.5정" 표시)·M-2(429 문구)를 먼저 처리하고, M-4·M-5는 안내 문구로 보완하며, 순수 함수 테스트 공백을 함께 채우길 권한다.

## 수정용 커밋 메시지 초안 (FE)

```
fix: 약 추가 모달 점검 후속 - 읽는 중 탭 덮어쓰기·반 알 표시·OCR 429 문구

- 읽는 중에는 탭 전환을 막아 직접 쓴 내용이 인식 결과로 덮이지 않게 한다 (M-1)
- 목록·피보호자 화면의 "0.5정"을 공용 포맷("반 알")으로 통일한다 (M-3)
- 이전 사진 처리 중(MEDICATION_OCR_LIMIT_EXCEEDED) 429는 서버 문구로 안내한다 (M-2)
- 약마다 복용 안내가 다르면 메모를 비우고 알린다, 20개 초과 시 안내 (M-4·M-5)
- 경계값 테스트 보강
```

## 조치 결과 (2026-10-22, FE PR #84 `b73513a`)

| 이슈 | 조치 |
|---|---|
| M-1 | 읽는 동안 탭을 잠근다. 실패 화면의 [직접 쓰기로]는 그대로 동작한다 |
| M-2 | `MEDICATION_OCR_LIMIT_EXCEEDED`일 때 "앞서 보낸 사진을 아직 읽고 있어요. 잠시 후 다시 찍어 주세요"로 안내한다. 프록시 취소 전달은 BE 자리를 풀지 못해 넣지 않았다 |
| M-3 | `formatDoseAmount`(0.5 → "반 알", 1.5 → "1정 반", 정수 → "N정", BE `DoseAmounts.label`과 같음)를 보호자·피보호자 화면 3곳과 요약 문구 함수에 적용했다. 입력 양식은 시안대로 "반알·1알"이다 |
| M-4 | 약마다 안내가 다르면(일부만 있는 경우 포함) 메모를 비우고 결과 문구로 알린다 |
| M-5 | 20개를 넘으면 "한 번에 20개까지만 가져왔어요"라고 안내한다 |
| L-1 | 등록 중에는 ×·바깥 클릭·Esc로 닫히지 않는다. 바깥 클릭은 누르기·떼기가 모두 바깥일 때만 닫는다 |
| L-2 | 양식이 바뀌면 실패 문구를 지운다 |
| L-3 | 흰 바탕을 먼저 깔고 그린다 |
| L-4 | BE `TextSanitizer`와 같은 정규화(NFC·제어/서식 문자 제거·공백 묶기·trim·소문자)로 비교한다 |
| L-5 | 단건 추가 API·훅·타입과 수정 모달의 추가 분기를 지웠다 |
| L-6 | 탭 `aria-controls`·`tabpanel`·←/→/Home/End 이동, 열릴 때 포커스·포커스 가두기·Esc·닫을 때 포커스 복귀, 낮은 확신 칸의 글자 안내 + `aria-describedby`, `:focus-visible` 윤곽선. 수정 모달의 Esc·포커스는 범위 밖으로 두었다 |

검증: vitest 130건(신규 7건 - 위 "테스트 공백"의 순수 함수 항목), tsc·eslint·`next build` 통과. Chromium 375px·1280px 목 API 52개 시나리오(탭 잠금, 안내 문구, 닫기 규칙, 포커스·키보드, 429 두 종류, 수정 모달 0.5→1 제출) 통과. gosky FE `b73513a`에서 `/guardian/medication`·`/ward/medication` 200을 확인했다.

## 남은 것 처리 (2026-10-22, FE PR #85 `9267f10`)

- **컴포넌트 테스트를 Chrome에서 실행**: `npm run test:browser`(vitest 브라우저 모드, playwright `channel: 'chrome'` - 설치된 Google Chrome 사용, 브라우저 내려받기 없음, `*.browser.test.tsx`).
  - 19건: 약 추가 모달 10, 약 수정 모달 5, 사진 전처리 4(Chrome canvas).
  - 위 "테스트 공백"의 컴포넌트 항목(탭 전환 중 결과 반영, 실패 시 양식 유지, 언마운트 시 취소, 수정 모달 0.5·1.5 경계)을 모두 포함한다.
  - 3회 연속 통과했다. 변이 2종(읽는 중 탭 잠금 제거, Esc 처리 제거)을 각각 해당 테스트가 잡았다.
  - 기존 `npm test`(node)는 그대로다. devDependencies 3개(정확한 버전 고정)는 설치 스크립트가 없고, Docker runner 이미지(`--omit=dev`)에는 영향이 없다.
- **수정 모달 Esc·포커스**: 모달 공통 동작을 `useModalDialog` 훅으로 빼서 약 추가·수정 모달이 함께 쓴다. 수정 모달은 열리면 이름 칸에 포커스, Esc 닫기, 포커스 가두기, 저장 중 닫기 막힘이 생겼다.
- **남은 한계**: EXIF 방향 반영은 실제 휴대폰 사진이 필요해 자동 테스트에 넣지 않았다. FE 저장소에 CI가 없어 `test:browser`는 Chrome이 설치된 PC에서 직접 돌린다.
