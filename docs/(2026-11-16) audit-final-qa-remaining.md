# 기능 점검 - 최종 QA 남은 BE 항목 (#493) + gosky 누적 위험 알림 켠 뒤 상태 (2026-11-16)

대상: BE #493 `d8fc29fa`(커밋 `0c47771b`), gosky·vkcs 반영 확인. 근거 `docs/(2026-11-16) fix-final-qa-remaining.md`.
코드 변경: `ConnectionNotificationListener`(피보호자 해제 문구), `application.yaml`(multipart 요청 전체 10MB → 12MB). 나머지는 문서.

## A. 보안·개인정보 - PASS

- 피보호자 해제 문구에 관리자 실명·내부 ID 없음("보호자가 / 관리자가 가족 연결을 해제했어요.") - 공지 작성자 고정 문구와 같은 기준. 피보호자 문자 평문 검사(`WardSmsAssertions`) 통과.
- multipart 경로 5개(말벗 음성 대답·챗봇 STT 2·약 봉투 OCR·프로필 사진) 모두 경로별 검사가 있고, 여러 파일을 받는 곳은 말벗뿐(음성 5MB + 사진 장당 1MB·5장). 파일 하나 상한 10MB는 그대로 - 요청 전체 12MB로 새로 열린 경로 없음(`policy-medication` "전역 상한을 올릴 때" 규칙 확인). 다른 multipart 수신 경로(`MultipartHttpServletRequest`·`getParts`) 없음. 챗봇 본문 필터(512KB)는 JSON 챗 2경로에만 걸려 STT와 겹치지 않음.

## B. 기능 정합성

| 해제 경로 | 주체 | 피보호자 수신 문구 | 판정 |
|---|---|---|---|
| 보호자 해제 `ConnectionService:229` | GUARDIAN | 보호자가 가족 연결을 해제했어요. | PASS |
| 피보호자 해제 `:338` | WARD | (보호자에게만) | PASS |
| 보호자 탈퇴 `:364` | WITHDRAWN | 단문 유지(M-2) | PASS |
| 역할 변경 정리 `:402` | ADMIN | 관리자가 가족 연결을 해제했어요. (관리자 조작 - 사실) | PASS |
| 관리자 강제 해제 `:467` | ADMIN | 관리자가 가족 연결을 해제했어요. | PASS |

WS `connection-cancelled`와 FCM 본문은 같은 변수. FE는 이 문구를 비교하지 않고 받은 `body`를 그대로 보인다(없을 때만 "연결이 해제되었습니다."). 문서 정정 3건은 코드 동작과 일치.

**12MB가 FE 경로에는 닿지 않는다(🟡 M-1)**: FE는 `apiClient` 기본 주소 `/api`(상대 경로)로 말벗 음성 대답·챗봇 STT를 보내고, 이것은 FE 도메인 → Next.js `src/app/api/[...path]/route.ts`(크기 상한 없음) → BE로 간다. gosky nginx `api.devdmu.gosky.kr`은 13m이지만 **`devdmu.gosky.kr`은 `client_max_body_size 10m`** 이라, #493이 고치려던 최대치(음성 5MB + 사진 1MB × 5 + 머리글)는 FE에서 보내면 BE에 닿기 전에 nginx가 HTML 413으로 막는다(JSON 오류 형식 아님). 실사용 크기(30초 WAV 약 3MB + 작은 JPEG)는 10MB에 닿지 않아 지금 영향은 작다. 커밋 설명 "앞단 nginx 는 13m"은 API 도메인만 맞다.

**gosky 누적 위험 알림(11/16 켬)**: `.env.dev` 스위치 켜짐 확인. 최근 하루 `COMPANION_RISK_PATTERN`·`CONNECTION_DISCONNECTED` 알림 0건, 선점 행(`companion_risk_pattern_notice`) 0, 최근 7일 어댑터 칸 값 1건(0.0, 11/15 점검용 테스트 대화) - 0.3 이상 0, v9 칸 0.97 이상 2건(11/15 01:18 전 v9 값). 관련 오류 로그 0.

## C. 구조·계약 - PASS

AFTER_COMMIT·디스패처 경로 변화 없음, 알림 종류·채널·수신자 그대로. 응답 모양 무변경.

## D. 테스트

해제 문구는 주체 4경우(GUARDIAN·WARD·WITHDRAWN·ADMIN)와 문자 평문까지 `ConnectionNotificationListenerTest`가 고정. 12MB는 `MultipartLimitConfigTest`가 설정값만 본다(요청 경계 동작은 미고정 - 🟢 L-2).

## 이슈

🔴 0 · 🟠 0 · 🟡 1 · 🟢 2

| # | 내용 | 근거 | 제안 |
|---|---|---|---|
| M-1 | FE 도메인 nginx 10m 때문에 #493의 12MB가 브라우저 사용자 경로에 적용되지 않는다(최대치 업로드는 nginx HTML 413) | gosky `/etc/nginx/sites-enabled/devdmu.gosky.kr:8`, FE `src/lib/api/apiClient.ts:29` | gosky `devdmu.gosky.kr`도 `client_max_body_size 13m` + `nginx -t && reload` (공용 서버 설정 - 사용자 확인 후) |
| L-1 | 정책 문서의 옛 10MB 표기 - `policy-medication.md` "multipart 전역 상한 10MB", `policy-companion.md` "서블릿 상한(10MB)" | 두 파일 | 요청 전체 12MB·파일 하나 10MB로 정정 |
| L-2 | 12MB 경계를 설정 문자열로만 고정 | `MultipartLimitConfigTest` | 선택 - 요청 경계 테스트(12MB 이하 통과·초과 413 JSON) |

## 조치 (2026-11-16, 사용자 "전부 반영해줘")

| # | 결과 |
|---|---|
| M-1 | **인프라 담당 이관**(사용자 결정). 한때 gosky `devdmu.gosky.kr` nginx를 13m로 바꿨으나 nginx 작업은 제외 대상이라 같은 날 원본으로 되돌렸다(원본과 같음 확인, `nginx -t` 후 reload, 11MB → 다시 nginx 413, 로그인 200, 백업 파일 삭제). 지금 FE 도메인은 10m 그대로 |
| L-1 | `policy-medication.md` 전역 상한을 "파일 하나 10MB·요청 전체 12MB" + 앞단 nginx 두 도메인 13m 규칙으로, `policy-companion.md` 업로드 합계 상한 12MB로 정정 |
| L-2 | `MultipartLimitConfigTest.max_payload_fits_request_limit` - 실제 application.yaml 바이트로 말벗 최대치 + 머리글 여유 1MB ≤ 요청 상한, 파일 상한 ≤ 요청 상한, nginx 13m > 요청 상한 |

## 종합

**PASS (Medium 1건)**. 해제 문구는 모든 경로에서 사실과 맞다. 12MB 상한은 API 도메인 직접 호출에만 적용되고 FE 경로는 nginx 10m에 묶인다.
