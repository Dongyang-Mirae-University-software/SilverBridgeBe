# 영향 범위 점검 보완 - 최종 QA BE 결함 11건 (#490) 의 ①밖 공용 규칙 (2026-11-16)

대상: BE #490 머지 `560646d7`(2026-11-15), gosky·vkcs 반영 확인. 근거 `docs/(2026-11-15) fix-final-qa-be-defects.md`.

> ① 숫자 enum 거부는 같은 날 다른 세션이 먼저 점검했다(`docs/(2026-11-16) audit-impact-enum-number-reject.md`, PASS). 이 문서는 그 결과와 같은 사용처(13 DTO·16칸)를 독립적으로 다시 확인했고, 숫자 문자열 등 변형 탐침과 ②·③·④를 더한다. 이슈 번호는 이 문서 안에서만 쓴다.

## 변경점과 불변식

| 공용 규칙 | 불변식 | 판정 |
|---|---|---|
| ① 전역 `spring.jackson.datatype.enum.fail-on-numbers-for-enums: true` (Jackson 3) | 웹 요청 JSON의 enum 칸은 이름 문자열만 받는다 - 숫자·숫자 문자열은 400 | PASS |
| ② 로그아웃 → 그 토큰으로 연 WS만 닫기 (`UserLoggedOutEvent`) | 로그아웃한 토큰의 열린 세션은 커밋 뒤 닫히고, 다른 토큰 세션은 남는다 | PASS |
| ③ 연결 응답 시각 +09:00 (`ConnectionTimes`) | 같은 순간을 표기만 바꾼다, 시각을 날짜로 자르는 화면이 바른 날짜를 본다 | PASS (🟢 L-2) |
| ④ 일반 공지 응답 작성자 = 고정 문구 "관리자" | 일반 사용자 응답에만, 관리자 화면 실명은 그대로 | PASS (🟢 L-3) |

국소 수정(받은 알림 NPE·정지 사유 변경·없는 병원 감사 로그·제어문자·보이는 글자·미리보기 형식 검사)은 그 도메인 안에서 끝나 공용 영향 없음.

## ① 숫자 enum 거부 - 사용처 전수

- **적용 범위**: Boot가 만든 Jackson 3 매퍼 = 웹 요청 본문만. 앱 코드는 `tools.jackson`을 직접 쓰지 않고(Jackson 2), RestClient 2곳(`FileServerClient`·`KakaoOAuthClient`)은 정적 `RestClient.builder()`라 Boot 설정을 받지 않으며 응답에 enum도 없다. STOMP 수신(`@MessageMapping`)은 없다. `@RequestParam`·`@PathVariable` enum은 Spring 변환기(Jackson 아님)라 해당 없음.
- **요청 본문 enum 칸 16개 / 13개 엔드포인트**(스크립트 수집): `RegisterRequest`·`KakaoRegisterRequest`(gender·role), `UserUpdateRequest`(gender), `AdminUserUpdateRequest`(role·status), `AdminHospitalStatusRequest`(operationStatus·congestionLevel), `AnomalyFeedbackRequest`(verdict), `InquiryCreateRequest`(category), `MedicationCreateRequest`·`MedicationUpdateRequest`(timeSlot), `NotificationSettingUpdateRequest`(channelType), `SosTriggerRequest`(triggerType), `SosSettingUpdateRequest`(sosAction). 11개 enum 모두 `@JsonCreator`·`@JsonValue` 없음 → 같은 규칙.
- **FE**: TypeScript `enum` 선언 0개, 해당 타입은 모두 문자열 유니온이고 값이 BE 상수의 부분집합(`WardSosAction` = `CALL_119_AND_NOTIFY`·`NOTIFY_GUARDIAN_FIRST` ⊂ BE 3값 등). 숫자를 보내는 경로 없음 → 전에 되던 요청이 400이 되는 곳 없음.
- **탐침(임시 테스트, 커밋 안 함, 실제 application.yaml)**: `"0"`·`"1"`·`0.0`·`-1`·`99`·`""`·`"call_119"`·`true` 모두 거절, `" CALL_119"`(앞 공백)만 받음.

## 이슈

🔴 0 · 🟠 0 · 🟡 0 · 🟢 3

| # | 내용 | 근거 | 제안 |
|---|---|---|---|
| L-1 | `WebJsonEnumNumberRejectTest`가 숫자(`0`·`1`)만 고정하고 숫자 문자열(`"0"`)은 고정하지 않는다(지금은 거절됨 - 탐침) | `src/test/.../sos/dto/WebJsonEnumNumberRejectTest.java` | `"0"` 사례 한 줄 추가 |
| L-2 | 관리자 연결 목록 `AdminConnectionResponse.connectedAt`은 UTC(`Z`) 그대로 - 같은 연결 시각이 응답마다 표기가 다르다. 지금 관리자 FE는 이 값을 쓰지 않아 영향 없음 | `admin/dto/AdminConnectionResponse.java:33` | 표기를 맞추려면 `ConnectionTimes`를 공용으로(관리자 FE는 FE 담당자 몫 - 화면 변경 제안 아님) |
| L-3 | 일반 공지 응답은 작성자를 고정 문구로 내보내는데 `AnnouncementService`가 여전히 작성자 이름을 조회한다(쓰지 않는 쿼리) | `announcement/service/AnnouncementService.java:45` 부근 | 조회 제거 |

## 종합

**PASS** (Low 3건). 숫자 enum 거부로 막히는 정상 요청 경로는 없다.
