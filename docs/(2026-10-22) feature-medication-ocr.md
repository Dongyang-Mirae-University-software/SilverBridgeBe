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
⚠️ 파트 이름 `image`는 AI 라우터가 아직 없어 가정이다(AI `feature/medication-ocr` 작업 중) - AI가 다르게 정하면 `AiMedicationOcrClient.PART_NAME`만 바꾼다.

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
- 실서버 확인(합성 메모 이미지 → `readable=false`)은 머지·gosky 배포 + AI 반영 뒤.

## 6. FE 전달

- 업로드 전 EXIF 방향 반영·긴 변 약 1500px(측정: 누운 사진 9→6~7/10, 1024px 5~6/10). 8MB 이하.
- 타임아웃 50초 이상. FE 프록시(Next 라우트)·nginx 본문 상한이 8MB 이상인지 확인.
- 0.5는 복용량 1 + 메모 "1회 반 알". `slots`마다 약을 따로 등록. `confidence=low` 항목 강조, high라도 확인 단계는 건너뛰지 않는다.
