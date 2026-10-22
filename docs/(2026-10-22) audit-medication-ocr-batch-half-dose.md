# 기능 점검 - 약 봉투 OCR(#361) + 약 일괄 등록·반 알(#362, V62) (2026-10-22)

> 템플릿 B(기능 점검). 대상: dev `9857061`(코드 기준 `f4883d4` - 이후 문서만). 점검만, 코드 수정 없음.
> 근거 문서: `(2026-10-22) feature-medication-ocr.md`, `(2026-10-22) feature-medication-batch-half-dose.md`.

## 종합 판정: ✅ PASS (🔴 0 · 🟠 0 · 🟡 0 · 🟢 6)

인가·IDOR·로그·저장 금지·원자성·반 알 규칙 모두 규칙대로다. 남은 것은 전부 Low이고 대부분 기존 경로와 같은 성질이거나 영향이 작다. 수정 제안은 L-3 하나(작음)만 권한다.

## 방법

- **독립 코드 점검**: 구현자(같은 세션)의 결론에 끌리지 않도록 결론을 주지 않고 코드만 보게 한 하위 에이전트(Sonnet)로 PHASE A~D 전체를 읽혔다. 보고는 메인이 다시 확인해 오탐·심각도를 정정했다(아래 "보정").
- **변이 테스트**(PHASE D): 방어 코드를 하나씩 망가뜨려 기존 테스트가 잡는지 확인 - **8종 모두 잡힘**.
- **실서버(gosky, 읽기 전용)**: 테스트 보호자 계정. 배포 직후 기능 확인(별도 기록)에 더해 이번 점검에서 연결 없는 피보호자 IDOR을 실호출.

## PHASE 0. 대상

| 엔드포인트 | 역할 | 인가 | 비고 |
|---|---|---|---|
| `POST /api/guardian/ward/{wardId}/medication/ocr` | GUARDIAN(클래스 `@PreAuthorize`) | `isActiveConnection` → 403 + `[IDOR-ATTEMPT] action=ocr` | 킬 스위치 → 속도 제한 → 인가 → 크기·형식 → 동시 자리 → AI |
| `POST /api/guardian/ward/{wardId}/medication/batch` | GUARDIAN | `requireActiveConnection`(첫 줄) | 한 트랜잭션, 단건과 같은 `newMedication` |
| `POST .../ward/{wardId}/medication`, `PATCH /api/guardian/medication/{id}` | GUARDIAN | 기존과 같음 | `doseAmount` 0.5 단위만 변경 |

파일: `domain/medication/{client/AiMedicationOcrClient, config/MedicationOcrProperties, controller/GuardianMedicationOcrController·GuardianMedicationController, dto/MedicationOcrResponse·MedicationBatchCreateRequest·DoseAmount·MedicationCreateRequest·MedicationUpdateRequest·MedicationItem, entity/DoseAmounts·Medication·MedicationTimeSlot, service/MedicationOcrService·MedicationOcrProjection·MedicationOcrSlots·GuardianMedicationService·MedicationReminderService}`, `global/exception/{ErrorCode, GlobalExceptionHandler}`, `application.yaml`, `V62__allow_half_dose_amount.sql`.

## PHASE A. 보안·인가 - IDOR PASS

- 두 경로 모두 `isActiveConnection`만(`getMyWards` 없음). PENDING·해제 연결도 같은 403.
- **실서버**: 연결 없는 `wardId`로 OCR·일괄 등록 → 둘 다 403 `MEDICATION_NOT_AUTHORIZED`, 서버 로그 `[IDOR-ATTEMPT]` 2건(`action=ocr`, `약 일괄 등록`), 내 약 수 0 → 0(생성 없음).
- OCR 인가는 사진 검사보다 앞(연결 없는 보호자에게 형식 판정을 주지 않음) - 변이 M5로 고정 확인.
- 로그·응답: 사진·약 이름·AI 응답·예외 원문·업로드 파일 이름 없음(파일 이름은 AI에도 고정값). AI 키 헤더만, 리다이렉트 미추종, AI 오류 본문 미독. `MedicationOcrLogGuardTest`·`LogRawExceptionGuardTest`.
- 응답 허용 목록(`reason`·성분·새 필드 차단) - 변이 M7로 고정 확인. 저장 호출 없음(사진·결과).

## PHASE B. 기능 정합성

- 반 알: 0.5~99·0.5 배수(`compareTo` + `multiply(2).stripTrailingZeros().scale()`), 극단 지수 값은 범위 비교에서 먼저 걸러진다. 응답 정규화 `1`·`0.5`·`10`(지수 표기 없음). `doseAmount` 전 사용처(리마인더 Planner·Target·Service, `Medication.update`, 서비스 3곳, DTO)에 `equals` 비교 없음. 대시보드·탈퇴·역할 변경·미복용 요약은 `doseAmount`를 읽지 않는다.
- V62: CHECK가 DTO 규칙과 같다. V35에 기존 CHECK 없음. 비가역 설명 주석 있음. 두 서버 기존 데이터 위반 0(배포 전 확인).
- 일괄 등록: null 요소 `@NotNull`+`@Valid` 400, 상한 20×6=120행, 인가 → 중복 → 생성, 실 DB 통합 테스트로 중간 항목 실패 시 0건 확인. 시각 → 시간대 경계와 기본 시각 왕복 일치.
- 스케줄러·미복용 요약은 엔티티 `doseTime`·`createdAt`만 보므로 단건과 동일하게 다룬다.
- OCR 동시 자리: try-with-resources로 모든 예외 경로에서 반납(1회 보장), 전체 자리 실패 시 1인 카운트 되돌림 - 변이 M6로 고정 확인. 클라이언트: 연결 3초·전체 40초 마감(`disconnect`)·덩어리마다 마감 확인·응답 크기 상한.

## PHASE C. 구조·계약

- `@Transactional` 경계(일괄 등록 단일 트랜잭션, OCR 서비스는 트랜잭션 없음 - DB 접근 없음) 적절. 웹 응답에 Jackson 2 타입 없음(일반 DTO 변환).
- 응답 포맷·상태 코드·Swagger(0.5 단위·전부 성공/전부 실패·413 분기) 반영. 정수 요청·기존 FE 표시 하위 호환.
- 생성 로직 중복 없음(`newMedication` 공유).

## PHASE D. 테스트 - 변이 8종 전부 잡힘

| 변이 | 결과 |
|---|---|
| M1 일괄 등록 인가 제거 | 잡힘 |
| M2 같은 요청 중복 검사 제거 | 잡힘 |
| M3 응답 정규화 제거(`1.0` 노출) | 잡힘 |
| M4 0.5 배수 검사 제거 | 잡힘 |
| M5 OCR 인가를 사진 검사 뒤로 | 잡힘 |
| M6 OCR 동시 자리 반환 누락 | 잡힘 |
| M7 OCR 응답에 성분 섞기 | 잡힘 |
| M8 시간대 경계 이동(11시 → 12시) | 잡힘 |

## 이슈 (전부 🟢)

| ID | 내용 | 판단 |
|---|---|---|
| L-1 | multipart 전역 상한 5→10MB로 프로필 사진 경로(`PATCH /api/user/me/image`)도 서비스 5MB 검사 전에 10MB까지 받는다(임시 파일·대역폭). | 수용. 5MB 거절은 서비스 검사로 유지(`UserServiceTest`), 전에도 5MB까지 같은 성질이었고 전역 사용자 속도 제한(분 600)이 있다. *(독립 점검은 🟡로 봤으나 기존 노출의 크기만 2배가 된 것이라 🟢로 정정)* |
| L-2 | 10MB 초과는 서블릿 파싱 단계에서 413이 나와 `@PreAuthorize`·서비스 속도 제한보다 앞선다(WARD·ADMIN도 413을 볼 수 있음). | 수용. 드러나는 정보 없음, 크기 판정만 앞선다. |
| L-3 | 일괄 등록 시각 중복 검사가 나노초까지 비교 - `08:00:00`과 `08:00:00.0000001`은 통과하고 DB `TIME`(마이크로초)에는 사실상 같은 시각 2건이 생긴다. 단건 등록도 시각을 그대로 받는다. | **수정 권장(작음)**: 중복 비교·저장 전에 시각을 초(또는 분) 단위로 자른다. FE는 `HH:mm:ss`만 보내므로 하위 호환 영향 없음. |
| L-4 | OCR 마감 `disconnect`가 공용 스케줄러 풀(3)을 쓴다 - 풀이 막히면 마감이 늦어지고 읽기 타임아웃이 안전망(최악 약 2배). | 수용. 챗 중계와 같은 구조, 동시 상한 5. |
| L-5 | OCR 요청 1건이 사진을 2번 메모리에 올린다(원본 + multipart 본문, 8MB면 약 16MB). | 수용. 동시 5건이라 최대 약 80MB. |
| L-6 | `[IDOR-ATTEMPT]` 로그에 경로 변수 `wardId` 원문 - 줄바꿈 등으로 로그 위조 가능. | 수용(기존 복약 IDOR 로그와 같은 패턴, 이번 변경으로 생긴 것 아님). 전역으로 고칠 때 함께. |
| 참고 | 일괄 등록 이름 중복은 NFC·서식 문자·공백·대소문자까지만 같게 본다(전각·NFKC 호환 문자는 다른 이름). | 이슈 아님(단건 등록은 같은 이름도 허용, 오입력 방지 보조 기능). |

### 보정 (독립 점검 보고 중 오탐)

테스트 공백으로 보고된 9건 중 4건은 이미 있었다: 중복 이름의 대소문자·zero-width(`GuardianMedicationBatchTest.duplicatesRejected`의 `Tylenol`/` tylenol​`), 목록 null 요소(`MedicationHalfDoseBatchValidationTest` `drugs[4].<list element>`), 반 알 알림 문구(`MedicationReminderServiceTest.반알_문구`), 응답 크기 상한(`AiMedicationOcrClientTest.malformedReply`). 나머지(동시 자리 단독 테스트·통합 테스트의 IDOR·속도 제한 fail-open 경로)는 단위 테스트·변이·실서버 호출로 확인돼 추가 가치가 낮다.

## 수정용 초안 (L-3, 승인 시)

- 브랜치 `fix/medication-dose-time-truncate`: 일괄 등록 `Time.doseTime`·단건 등록·수정의 `doseTime`을 초 단위로 자른 뒤 중복 비교·저장. 테스트: `08:00:00`과 `08:00:00.0000001` 중복 400.
- 커밋: `fix: 복용 시각을 초 단위로 맞춰 일괄 등록 중복 검사 우회 방지`
