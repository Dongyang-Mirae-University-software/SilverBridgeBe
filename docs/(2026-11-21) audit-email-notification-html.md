# 기능 점검(B) - 이메일 알림 HTML·서비스 이름 CareAI (2026-11-21)

- 대상: BE #509(`184cdf84`, 이메일 HTML·보낸 사람 이름) + #510(`74372478`, 서비스 이름 CareAI)
- 기준: dev `74372478`, `./gradlew build -x test` 통과
- 판정: **PASS** · 🔴 0 · 🟠 0 · 🟡 1 · 🟢 4 · 실제 발송 확인(Gmail 스팸함 도착 - 발송 계정 평판, `design-email-notification-html-style.md` "실발송 확인")

## 점검 파일

`EmailNotificationChannel`·`EmailNotificationHtml`·`WardNotificationText`·`AnomalyNotificationListener`(문자 대체)·`CompanionRiskNotificationListener`·`CompanionRiskPatternListener`·`SmsService`·`PasswordResetService`·`application.yaml`(`notification.email.from-name`), 관련 테스트. 엔드포인트 변경 없음(인가 표 해당 없음).

## PHASE A. 보안

| 항목 | 결과 | 근거 |
|---|---|---|
| HTML 이스케이프 | PASS | 제목·본문·서비스 이름·꼬리말 모두 `HtmlUtils.htmlEscape`, 줄바꿈만 `<br>`. 테스트 `send_HTML이스케이프`·`이스케이프` |
| 메일 헤더 끼워 넣기(제목 CRLF) | PASS | angus-mail 2.0.5로 직접 시험: `"[CareAI] abc\r\nBcc: evil@…"`(ASCII)·한글 섞인 제목 모두 별도 헤더 줄이 생기지 않고 BCC 수신자 없음(ASCII는 접기, 한글은 인코딩). 제목에 들어가는 사용자 값은 피보호자 이름뿐이고 이름은 한 줄 정리(`TextSanitizer`) |
| From 표시 이름 | PASS | 설정 값만, `setFrom(addr, name)` UTF-8 인코딩. 주소 설정이 비면 미지정 |
| 로그 | PASS | 채널 로그는 userId·예외 클래스명만. `LogRawExceptionGuardTest`에 채널·실행기 포함 |
| 외부 자원 | PASS | 이미지·링크·스타일시트·스크립트 없음(`외부자원없음` 테스트) |

## PHASE B. 기능 정합성

| 항목 | 결과 | 근거 |
|---|---|---|
| 허용 7종 렌더 | PASS | 렌더는 종류와 무관한 공통 틀. 대표 4종(연결 수락·문의 답변·복약 미복용·피보호자 연결 요청) 360px 확인 |
| 피보호자 메일 제목 | PASS | 제목 `CareAI`면 접두어 생략(`[CareAI] CareAI` 방지), HTML 제목 줄 생략 |
| 피보호자 문자 90바이트 | PASS | 접두어 13 → 9바이트로 여유 증가. `WardSmsAssertions`·경계 테스트 이동(연결 요청 이름 칸 31바이트) |
| 이상감지 대체 문자 | PASS | EUC-KR 90바이트 이하 테스트로 교체 |
| 남은 옛 이름(BE) | PASS | 사용자 노출은 Swagger 제목(개발자용, 의도적 유지)뿐. 마이그레이션에 없음 |
| FE 영향 | 🟡 M-1 | 아래 |
| 실제 발송 | 미확인 | 시험 계정 주소가 `@test.com`(남의 도메인)이라 발송하지 않음 |

## PHASE C. 구조

- 실패 처리 PASS: 메일 작성 중 `MessagingException`·`UnsupportedEncodingException`·`MailException` → `PROVIDER_ERROR`, 발송은 `EmailSendExecutor` 안 그대로, 시간 초과·풀 포화도 `PROVIDER_ERROR`.
- 설정 기본값 PASS: `from-name` 기본 `CareAI`(yaml·코드 동일), `.env` 수정 없음.

## PHASE D. 테스트

이스케이프·외부 자원·보낸 사람·두 본문·작성 실패·가상 이메일·시간 초과는 고정됨. 빈틈은 🟢 L-1·L-2.

## 이슈

- 🟡 **M-1 FE에 옛 서비스 이름이 남아 사용자가 두 이름을 본다**: FE `src/app/manifest.ts`의 `name`·`short_name` `'실버브릿지'`(설치 앱 이름), `WardIdSharePanel.tsx`의 공유 문구 `[실버브릿지] 저를 가족으로 등록해 주세요.`·공유 제목 `'실버브릿지 가족 등록'`. 알림은 `CareAI`인데 앱·공유 문구는 실버브릿지다. 푸시 제목으로 분기하는 FE 로직은 없어(테스트 데이터로만 씀) 동작 영향은 없다. FE 변경이라 BE에서 고치지 않고 FE 쪽으로 넘긴다(앱 로고·인증 화면은 이미 CareAI).
- 🟢 **L-1** 비밀번호 재설정 문자·메일 제목 `[CareAI]`를 고정하는 테스트가 없다(`PasswordResetServiceTest`에 문구 단언 없음). 인증번호 문자는 `SmsVerificationServiceTest`가 고정.
- 🟢 **L-2** 제목 헤더 끼워 넣기 안전성은 메일 라이브러리 동작에 기대며 테스트로 고정돼 있지 않다(이번에 직접 시험으로 확인). `EmailNotificationChannelTest`에 CRLF 제목 → 원문 헤더에 `Bcc:` 줄 없음 단언 추가 제안.
- 🟢 **L-3** `MimeMessageHelper(message, true, …)`는 mixed > related > alternative로 감싼다(문서·주석은 "multipart/alternative"). 일부 메일 앱(Apple Mail·Outlook)은 최상위 multipart/mixed에 첨부 표시를 띄울 수 있다. 실제 발송 확인 때 같이 보고, 필요하면 최상위를 alternative로 직접 구성.
- 🟢 **L-4** 피보호자 메일은 제목이 `CareAI`뿐이라 메일함 목록에서 종류를 알 수 없다(2026-11-20 사용자 결정으로 수용, 본문 미리보기로 보임).
- 참고: 말벗 위험 문자(보호자용)는 이름 포함 약 99~107바이트로 원래부터 장문 문자였다(이번 변경으로 4바이트 줄어듦). 피보호자 90바이트 규칙 대상 아님.

## 수정용 커밋 메시지 초안

- `test: 비밀번호 재설정 문구와 이메일 제목 헤더 끼워 넣기 방지를 테스트로 고정` (L-1·L-2)
- `fix: 이메일 알림 최상위 MIME을 multipart/alternative로` (L-3, 실발송 확인 후 필요 시)
- FE: `fix: 앱 이름과 가족 등록 공유 문구의 서비스 이름을 CareAI로` (M-1)

## 조치 (2026-11-21, 사용자 지시 "전부 반영")

| 이슈 | 조치 |
|---|---|
| M-1 | FE #318(`e7de2e59`): manifest `name`·`short_name`, 가족 등록 공유 문구·제목을 CareAI로. gosky FE dev 반영(`manifest.webmanifest` `"name":"CareAI"` 확인), FE 단위 테스트 722건 통과 |
| L-1 | `PasswordResetServiceTest`가 문자 문구 `[CareAI] 비밀번호 재설정 인증번호: %s\n유효 시간: 5분`과 메일 제목 `[CareAI] 비밀번호 재설정 인증번호`를 고정 |
| L-2 | `EmailNotificationChannelTest.send_제목줄바꿈_헤더주입없음` - ASCII·한글 제목에 CRLF + `Bcc:`를 넣어도 원문 헤더 줄·BCC 수신자가 생기지 않음을 고정 |
| L-3 | `EmailNotificationChannel`이 머리글만 `MimeMessageHelper`(multipart 아님)로 쓰고 본문은 최상위 `multipart/alternative`(text/plain → text/html, UTF-8)로 직접 구성. mixed·related 없음을 `send_alternative`가 고정 |
| L-4 | 변경 없음(사용자 결정으로 수용) |

BE #511(`023a4144`) - `./gradlew build` 통과(테스트 2873건, 실패 0), vkcs CD·gosky dev 반영. 실제 발송은 여전히 미확인(시험 주소 `@test.com`).
