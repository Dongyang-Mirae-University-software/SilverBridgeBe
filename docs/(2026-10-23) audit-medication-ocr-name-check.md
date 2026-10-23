# 기능 점검 - 약 봉투 OCR 약 이름 대조 칸 전달 (PR #367)

> 템플릿 B(기능 점검). 대상: `ea8cd0b`(PR #367) - OCR 응답 `drugs[]`에 `inList`·`suggestions` 추가. 마이그레이션 없음.
> 판정: **PASS** - 🔴🟠🟡 0 · 🟢 3(코드 조치 불요, FE 전달 2건).

## PHASE -1. 환경

- 2026-10-23, dev `ea8cd0b`. `./gradlew test build` 통과(테스트 스위트 258개 실패 0). vkcs CD 성공(통합 테스트 포함), gosky 수동 배포 `ea8cd0b` healthy·AI WS 재연결.
- AI 서버 gosky `6f382b7`(#38 허가 목록 대조, #39 자모 비교·같은 용량 우선).

## PHASE 0. 대상

| 파일 | 변경 |
|---|---|
| `medication/dto/MedicationOcrResponse.java` | `Drug`에 `Boolean inList`·`List<String> suggestions` |
| `medication/service/MedicationOcrProjection.java` | `inList()`(JSON boolean만)·`suggestions()`(이름 규칙 정리·중복 제거·최대 3) |
| 테스트 2개 | `MedicationOcrProjectionTest` +3, `GuardianMedicationOcrControllerHttpTest` 확장 |

엔드포인트는 그대로다: `POST /api/guardian/ward/{wardId}/medication/ocr` - GUARDIAN 전용(URL 규칙 + `@PreAuthorize`), ACTIVE 연결만.

## PHASE A. 보안·인가

- **IDOR·역할 - PASS**: 인가 경로(`isActiveConnection`, 인가가 사진 검사보다 앞)는 바뀌지 않았다. 새 칸은 같은 응답에 붙을 뿐이다.
- **허용 목록 - PASS**: 새 칸도 코드가 읽는 키만 옮긴다. 실서버 응답 키는 `confidence·days·inList·name·perDose·slots·suggestions·timesPerDay·timing` 9개이고 `reason`·성분은 없다.
- **입력 검증 - PASS**: 제안은 AI(외부) 값이라 약 이름과 같은 `TextSanitizer` 정리·보이는 글자 검사·100자 자르기를 거친다. `inList`는 JSON boolean이 아니면 `null`이다(문자열 `"true"`·숫자 1을 참으로 읽지 않는다).
- **PII·로그 - PASS**: 로그 줄은 바뀌지 않았다. gosky 실호출 뒤 BE·AI 로그에 약 이름이 나온 횟수는 0이다(BE `drugCount=2`, AI `not_in_list=1`). `MedicationOcrLogGuardTest` 통과. 제안은 식약처 허가 품목명이라 개인정보가 아니다.

## PHASE B. 기능 정합성

- **AI 계약 대조**: AI `DrugNameList.check()` - 목록이 없으면 `(None, [])`, 목록에 있으면 `(True, [])`, 없으면 `(False, 최대 3개)`. 표시값은 괄호를 뺀 품목명이다. BE 처리와 어긋나는 곳이 없다.
- **옛 AI 호환**: 칸이 없거나 null이면 `inList=null`·`suggestions=[]`(테스트로 고정).
- **실서버(gosky, 2026-10-23 10:46)**: 테스트 보호자 + ACTIVE 피보호자로 합성 봉투 사진(서버에서 만들고 호출 뒤 삭제)을 올림 → 200, 3.7초.
  - `타이레놀정500mg` → `inList:true`, `suggestions:[]`
  - `슈다페트정`(한 획 오독) → `inList:false`, `suggestions:["슈다페드정","슈다펜정","디페트정"]`
- **길이**: 허가 목록 42,744줄의 표시값 중 100자를 넘는 것은 3개다. BE가 100자로 자르며, 약 등록 API의 이름 상한(100)과 같아 제안을 그대로 등록에 넣어도 400이 나지 않는다.

## PHASE C. 구조·계약

- 가산 변경(칸 추가)이라 기존 FE는 영향이 없다. Swagger `@Schema`에 "참고용·자동으로 바꾸지 않음"을 적었다.
- 트랜잭션·이벤트·DB와 무관하다(저장 없음 그대로).

## PHASE D. 테스트

- 칸 있음 / 없음·null·형식 다름 / 제안 정리(빈 값·문자열 아님·제어문자·중복·100자·3개 상한), 실제 HTTP 직렬화(`inList` false·JSON null, `suggestions` 값·빈 배열).
- **변이 시험 5종 전부 잡힘**(임시 복사본, 변이 없는 원본은 통과): 상한 3→4 / 중복 제거 없음 / `inList` 문자열 허용 / 제안 정리 생략 / `inList` 전달 누락.

## 이슈

- 🟢 **L-1 100자 넘는 품목명 3개는 잘린 이름으로 나간다** - 등록 상한도 100이라 등록은 되지만 허가 품목명과 글자가 달라진다. 3/42,744건이고 대부분 수출명 표기라 봉투에 나올 일이 드물다. **수용**.
- 🟢 **L-2 BE는 "제안은 `inList=false`일 때만"을 강제하지 않는다** - AI가 보낸 대로 넘긴다(현재 AI는 그 규칙을 지킨다). FE는 `inList === false`일 때만 제안을 보여 주면 된다. **FE 전달**.
- 🟢 **L-3 `confidence`와 `inList`는 별개다** - 실호출에서 오독 이름 `슈다페트정`도 `confidence:"high"`였다. 확신은 "봉투 글자를 그렇게 읽었다"는 뜻이지 "그런 약이 있다"는 뜻이 아니다. FE는 `inList === false`를 확신과 따로 강조해야 한다(`null`은 대조 못 함 - 경고하지 않는다). **FE 전달**.

## FE 전달 요약

- `inList: true | false | null`, `suggestions: string[]`(최대 3, 각 100자 이내). `false`일 때만 "목록에 없는 이름이에요" + 제안 칩, 칩을 누르면 이름 칸만 바꾼다(자동 교체 금지). `null`이면 아무 표시도 하지 않는다.
- FE `develop`(`b46493b`)은 아직 이 칸을 쓰지 않는다(2026-10-23 확인).

## 조치 결과 (2026-10-23, `fix/medication-ocr-name-check-findings`)

사용자 지시("이슈 전부 반영")로 🟢 3건을 BE에 반영했다. FE 표시는 사용자가 FE 세션에서 진행한다.

- **L-1 → 조치**: 100자를 넘는 제안은 자르지 않고 **뺀다**(잘린 이름은 허가 목록에 없는 품목명이다). 정확히 100자는 남긴다. 약 이름(`name`) 자체는 기존대로 100자로 자른다(보호자가 고치는 초안).
- **L-2 → 조치**: 제안은 `inList=false`일 때만 내린다. `true`·`null`·칸 없음·형식 다름이면 AI가 제안을 보내도 `[]`다.
- **L-3 → 조치(문서·계약)**: `confidence`는 AI 값을 그대로 둔다(글자를 읽은 확신이라는 신호를 지우지 않는다). Swagger·DTO 주석에 "약 목록에 있다는 뜻이 아니다 - 목록 대조는 `inList`", `inList`에 "false면 확신과 별개로 강조, null이면 경고하지 않음"을 적었다.
- 테스트: `MedicationOcrProjectionTest` 10 → 12건(100자 경계 유지·`inList` 조건). 변이 3종 전부 잡힘(`inList` 조건 제거 / 100자 초과를 자름 / 경계 `<=`→`<`). `./gradlew test build` 통과(스위트 258개 실패 0).
