# 약 봉투 OCR - 보호자 중계 API (2026-10-22)

> 근거: `(2026-10-22) result-medication-ocr-measure.md`(조건부 Go, sonnet 5.5), 챗 중계 `(2026-10-07) feature-chat-relay.md` 패턴.
> 브랜치 `feature/medication-ocr`, 마이그레이션 없음, `.env` 변경 없음(공용 `AI_HTTP_BASE_URL`·`AI_API_KEY`).

## 1. 무엇을 했나

보호자가 약 봉투 사진을 올리면 백엔드가 AI 서버로 넘겨 **등록 초안**을 돌려준다. 사진·결과는 **저장하지 않는다**.
등록은 보호자가 이름·시간대를 확인·수정한 뒤 기존 `POST /api/guardian/ward/{wardId}/medication`으로 한다(자동 등록 없음).

## 2. API 계약 (FE ↔ BE)

`POST /api/guardian/ward/{wardId}/medication/ocr` - GUARDIAN 전용, `multipart/form-data` 파트 `image`

응답(공용 래퍼 `{success, message, data}`, `Cache-Control: no-store`):

```json
{ "success": true, "data": {
  "readable": true,
  "drugs": [ { "name": "슈다페드정", "perDose": 0.5, "timesPerDay": 2, "days": 5,
               "slots": ["MORNING", "DINNER"], "timing": "식후 30분", "confidence": "high" } ] } }
```

| 필드 | 규칙 |
|---|---|
| `readable` | false면 약 봉투로 못 읽음, `drugs`는 빈 배열 |
| `drugs` | 최대 20개. 이름이 보이는 글자로 남지 않는 약은 뺀다 |
| `name` | 한 줄 정리(제어·서식 문자 제거), 최대 100자. 1글자 오독 가능 → 확인 필수 |
| `perDose` | 0 초과 99 이하 숫자, 아니면 null. **반 알 = 0.5** |
| `timesPerDay` / `days` | 정수 1~10 / 1~365, 아니면 null |
| `slots` | `MORNING·LUNCH·DINNER·BEDTIME`만, 모르는 값 버림, 중복 제거, 하루 순서. 표시 없으면 빈 배열 |
| `timing` | 최대 50자, 없으면 null |
| `confidence` | `high`·`low`, 그 밖은 null. 화면 강조용일 뿐 |

**반 알 처리(2026-10-22 결정)**: `doseAmount`는 정수 그대로 둔다. 응답의 `perDose` 원값을 보고 FE가 0.5면 복용량 1 + 메모 "1회 반 알"을 채운다.
> 후속(PR #362, `feature/medication-batch-half-dose`): `doseAmount`가 0.5 단위가 되면 `perDose` 0.5를 그대로 `doseAmount`에 넣는다(메모 우회 불필요). 일괄 등록 API도 함께 - `(2026-10-22) feature-medication-batch-half-dose.md`.

내리지 않는 것: AI `reason`(자유 문장 - 개인정보가 섞일 수 있음), 성분(측정에서 확신 high로 틀림), 그 밖 AI가 더하는 모든 필드(허용 목록 `MedicationOcrProjection`).

오류(`{success:false, code}`):

| HTTP | code | 언제 |
|---|---|---|
| 401 | - | 비로그인 |
| 403 | (권한) / `MEDICATION_NOT_AUTHORIZED` | WARD·ADMIN / ACTIVE 연결 아님(PENDING·해제 포함) + `[IDOR-ATTEMPT]` |
| 400 | `MEDICATION_OCR_INVALID_IMAGE` | 파트 없음·빈 파일·JPG/PNG/WebP 아님(매직 바이트) / AI 400·415·422 |
| 413 | `MEDICATION_OCR_IMAGE_TOO_LARGE` | 8MB 초과(서비스) / 10MB 초과(서블릿 단계, OCR 경로만 413) / AI 413 |
| 429 | `TOO_MANY_REQUESTS` / `MEDICATION_OCR_LIMIT_EXCEEDED` | 보호자당 분 10·시간 60(Retry-After) / 동시 전체 5·1인 1 |
| 503 | `MEDICATION_OCR_UNAVAILABLE` | 킬 스위치 off·AI 401·5xx·통신 오류·키 미설정·응답 형식 이상 |
| 504 | `MEDICATION_OCR_TIMEOUT` | 호출 전체 40초 초과 |

## 3. AI 계약 (BE ↔ AI)

`POST {AI_HTTP_BASE_URL}/api/v1/medication/ocr`, 헤더 `X-API-Key`, multipart 파트 `image`(파일 이름은 형식별 고정 `image.jpg|png|webp` - 사용자 파일 이름은 넘기지 않음).
200 본문은 **래퍼 없이** `{readable, reason, drugs[{name, perDose, timesPerDay, days, slots, timing, confidence}]}`, 오류는 AI 공용 래퍼 - BE는 오류 본문을 읽지 않고 상태 코드로만 판단한다.
AI 구현 확인(AI `feature/medication-ocr` `1b8e749`): 파트 `image`, 본문 상한 8MB+64KB(초과 413 `OCR_IMAGE_TOO_LARGE`), 형식·파트 오류 400 `OCR_INVALID_IMAGE`, 꺼짐·미설정 503, 모델 실패 502 `OCR_UPSTREAM_FAILED`(BE는 503으로), 모델 호출 제한 30초(BE 40초 안). 사진은 메모리에서만 다루고 EXIF를 떼어 재인코딩한다.

**AI 머지·배포(2026-10-22)**: AI PR #29(`0212176`)로 main 머지·gosky 반영, 이어서 기능 점검에서 찾은 정규화 메모리 문제를 AI PR #32(`5eebb7c`)로 고쳐 gosky 반영했다(현재 AI main `5eebb7c`).
- 모델 `claude-sonnet-5-5`(effort low, haiku 설정 시 sonnet으로 대체), JSON 스키마 강제, 서버 측 대체 모델, 30초·재시도 없음. 킬 스위치 AI `MEDICATION_OCR_ENABLED`(기본 true, AI `.env` 추가 없음).
- 사진 처리: EXIF 방향 반영 → 긴 변 1568px 이하(확대 없음) → EXIF 없는 JPEG. `UploadFile`(1MB 넘으면 디스크 임시 파일)을 쓰지 않고 본문을 메모리로 읽는다.
- **화소 상한(PR #32)**: JPEG 6,000만 / **PNG·WEBP 1,600만**(넘으면 AI 400 → BE `MEDICATION_OCR_INVALID_IMAGE`). PNG는 줄여 읽을 수 없어 105KB짜리 2,500만 화소 PNG가 디코드 232MB였다. JPEG는 디코드 단계에서 줄여 읽어 4,800만 화소도 41MB(수정 전 439MB). 정규화는 AI 안에서 동시 2건. BE 동시 상한(서버당 5)이 두 서버(prod·dev) 합쳐 AI에 최대 10건이라 둔 방어다.
- 후처리: 이름 앞뒤 공백 제거·100자, 빈 이름 제거, `perDose`·`timesPerDay`·`days` 0 이하 → null, `slots` 중복 제거(하루 순서), `readable=false`면 `drugs=[]`.
- AI 로그는 `[MED-OCR] result= bytes= px= ms= drugs=`만(사진·약 이름·모델 응답·오류 문구 없음).
- gosky AI 직접 호출 확인: 합성 메모 `readable=false` 2.9초, 6000×4000 JPEG 메모 → 1568×1045로 줄여 3.8초, 2,500만 화소 PNG → 400. 재시작 뒤 BE 두 서버 AI WS 재연결 확인.

## 4. 처리 순서와 정책

킬 스위치(`medication.ocr.enabled`) → 속도 제한(fail-open) → 인가(`isActiveConnection`, `getMyWards` 금지) → 크기(읽기 전 `getSize`) → 형식(매직 바이트) → 동시 자리 → AI 호출(전체 40초, 리다이렉트 미추종) → 허용 목록 → 응답.
인가를 사진 검사보다 앞에 둬 연결 없는 보호자에게 형식 판정 결과를 주지 않는다.

- **저장 없음**: 사진·결과를 DB·디스크에 남기지 않는다. 감사 로그·알림 없음.
- **로그**: 보호자 ID·wardId·소요 시간·`readable`·약 개수·결과 코드·AI 상태 코드·예외 클래스명만. 사진·약 이름·AI 응답·파일 이름 금지(`MedicationOcrLogGuardTest`, `LogRawExceptionGuardTest` 대상).
- **40초 예외**: 일반 외부 호출 8~10초 규칙의 예외(모델 호출, 측정 4~10초 + 업로드·AI 재시도 여유). 요청 스레드를 붙드므로 동시 상한을 함께 둔다(인스턴스 메모리, 단일 서버 전제).
- **multipart 전역 상한 5MB → 10MB**: 경로별 상한은 서비스가 검사한다(프로필 사진 5MB 그대로 - `UserService`). 서블릿 단계 초과는 OCR 경로만 413, 그 밖은 기존 400 `FILE_TOO_LARGE`.

## 5. 검증

- `./gradlew build` 통과 - 전체 1,749건, 실패 0. 새 테스트 30건:
  `AiMedicationOcrClientTest`(7, 로컬 HTTP 서버: 키 헤더·multipart·래퍼 없는 200·401/5xx→503·413·400/422→400·시간 초과 504·리다이렉트 미추종·형식 이상·키 미설정·매직 바이트),
  `MedicationOcrProjectionTest`(7, 허용 목록·범위·이름·시간대·상한),
  `MedicationOcrServiceTest`(9, 순서·킬 스위치·속도 제한·IDOR 로그·413/400·동시 1인/전체·AI 오류 시 자리 반환·로그에 약 이름/파일 이름 없음),
  `GuardianMedicationOcrControllerSecurityTest`(4, 실제 `SecurityConfig`: 401·WARD/ADMIN 403·200 no-store·오류 코드별 상태),
  `GuardianMedicationOcrControllerHttpTest`(2, Jackson 3 직렬화·전역 413 분기),
  `MedicationOcrLogGuardTest`(1).
- **실서버 확인(gosky, 2026-10-22, PR #361·#362 머지 후 수동 배포 `f4883d4`)**: 테스트 보호자 계정 + ACTIVE 피보호자로 합성 메모 사진(영어 장보기 메모, AI 컨테이너에서 생성) → 200 `{"readable":false,"drugs":[]}` 2.3초(BE → AI → 모델 경로 실동작), 사진 아닌 파일 → 400. 시험 파일은 서버에서 삭제.

## 6. FE 전달

- 업로드 전 EXIF 방향 반영·긴 변 약 1500px(측정: 누운 사진 9→6~7/10, 1024px 5~6/10). 8MB 이하.
- 타임아웃 50초 이상.
- FE 프록시(`src/app/api/[...path]/route.ts`)는 본문을 그대로 넘기고 `Content-Type`(boundary)을 보존해 변경 불요(2026-10-22 확인).
- **gosky nginx 본문 상한 - 해결(2026-10-22)**: 경로 브라우저 → `devdmu.gosky.kr` → FE(6510) → `api.devdmu.gosky.kr` → BE의 두 nginx가 기본 1MB라 1MB 넘는 사진이 BE 전에 413(HTML)이었다. 사용자 승인으로 두 서버 블록에 `client_max_body_size 10m`을 넣고 reload(백업 `/root/nginx-backup-20261022-ocr/`), 3MB 업로드가 두 도메인 모두 BE까지 도달(비로그인 401)·12MB는 nginx 413 확인. BE→AI `testai.gosky.kr`은 100M.
- 0.5는 복용량 1 + 메모 "1회 반 알". `slots`마다 약을 따로 등록. `confidence=low` 항목 강조, high라도 확인 단계는 건너뛰지 않는다.

## 7. 후속 - 약 이름 대조 칸 전달 (2026-10-23)

- AI OCR(#38·#39)이 `drugs[]`에 `inList`(bool|null)·`suggestions`(문자열 배열, 최대 3)를 더했다. BE는 허용 목록으로 거르므로 그대로는 FE에 가지 않아 `MedicationOcrResponse.Drug`에 두 칸을 추가했다(마이그레이션 없음, 가산 변경이라 기존 FE 무영향).
- `inList`: JSON boolean일 때만 값, 없거나 null·형식이 다르면 `null`(모름). `suggestions`: **`inList=false`일 때만** 채우고(그 밖은 `[]`), 배열이 아니면 `[]`, 원소는 약 이름과 같은 정리(`TextSanitizer`·빈 값/문자열 아님 제외), **100자를 넘으면 자르지 않고 뺀다**(점검 L-1·L-2 조치), 순서 유지 중복 제거, 최대 3개. AI가 칸을 안 주면 `null`·`[]`라 옛 AI 응답과 호환된다.
- 제안은 **참고용**이다 - 이름을 자동으로 바꾸거나 자동 등록하지 않는다(불변 규칙 ① 그대로). 이름·제안 원문은 로그에 남기지 않는다(`MedicationOcrLogGuardTest` 유지).
- 테스트: `MedicationOcrProjectionTest` +3(칸 있음·없음/null/형식 다름·제안 정리와 3개 상한), `GuardianMedicationOcrControllerHttpTest`(실제 직렬화에서 `inList` false/null·`suggestions` 배열/빈 배열).
