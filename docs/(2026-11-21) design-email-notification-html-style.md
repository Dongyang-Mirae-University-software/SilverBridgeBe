# 이메일 알림 HTML 디자인 - B안(카드형 다듬기) (2026-11-21)

## 배경

#509의 HTML 메일(진한 초록 띠 + 흰 카드)은 메일 앱에서 앱 화면을 붙인 것처럼 어색했다. 두 안을 대표 3종(연결 수락·문의 답변·복약 미복용 요약)에 적용해 360px·600px로 미리보기한 뒤, 사용자가 **B안(카드형 다듬기)**을 골랐다. A안(편지형: 흰 바탕, 색·카드 없음)은 채택하지 않았다.

## B안 구성

| 요소 | 값 |
|---|---|
| 바탕 | 크림 `#fbf6f0` (FE 앱 `background_color`) |
| 서비스 이름 줄 | `CareAI` 17px 굵게, 테라코타 `#b0674d` (FE 앱 `theme_color`). 띠 없음 |
| 카드 | 흰색, 테두리 `1px #eadfd6`, 모서리 14px, 최대 폭 560px·휴대폰은 화면 폭 |
| 알림 종류 칩 | 연한 바탕 `#f6e8e1`·글자 `#8a4a35` 14px. 연결 5종 "가족 연결", `INQUIRY_ANSWERED` "문의 답변", `MEDICATION_MISSED` "복약 확인", 그 밖 "알림" |
| 제목 | 22px 굵게 `#2b211c` (서비스 이름과 같으면 생략 - 피보호자 알림) |
| 본문 | 18px, 줄간격 1.75 `#3a302b`, 줄바꿈 유지 |
| 꼬리말 | 카드 밖 14px `#8c7f77`, 문구 두 줄 그대로 |

## 지킨 것 / 바뀌지 않은 것

- 인라인 스타일·표 레이아웃만. 외부 이미지·폰트·링크·추적 픽셀 없음. 제목·본문·칩 모두 HTML 이스케이프.
- 꼬리말 문구·제목 접두어 `[CareAI]`·보낸 사람 이름·텍스트 대체 본문 그대로(칩은 HTML에만).
- 허용 종류·긴급 알림 제외·`EmailSendExecutor`·`ChannelResult`·디스패처·로그 규칙 무변경. 칩 문구는 채널이 이미 받는 `NotificationType`으로 고른다(`EmailNotificationChannel.label`) - 새 종류에 이메일을 허용하면 문구를 더해야 하며 테스트가 막는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `EmailNotificationHtml.java` | B안 구조, `render(serviceName, label, title, body, footer...)` |
| `EmailNotificationChannel.java` | 종류별 칩 문구 `label(type)`을 렌더러에 넘김 |
| `EmailNotificationHtmlTest.java` | 구성 순서·앱 톤·글자 크기·줄바꿈·이스케이프(칩 포함)·외부 자원 없음·반응형·생략 |
| `EmailNotificationChannelTest.java` | 허용 종류 전부 전용 칩 문구, 보낸 메일 HTML에 칩·텍스트에는 없음 |

## 테스트·확인

- `./gradlew build` 통과(테스트 2877건, 실패 0).
- 360px 렌더(연결 수락·문의 답변·복약 미복용·피보호자 연결 요청) 깨짐 없음. 스크린샷은 작업 보고에만 첨부.
- **수용**: 메일 앱 다크 모드는 앱마다 색을 다르게 뒤집어 크림 바탕·칩 색이 달라 보일 수 있다(A안을 고르지 않은 대가로 받아들임). 실제 메일 앱 표시는 아래 "실발송 확인"에서 Gmail로 확인했다.

## 실발송 확인 (2026-11-21, gosky dev)

시험 보호자(`pVFG3u`) 이메일을 사용자 Gmail로 잠시 바꾸고(gosky에서 사용자가 스크립트 실행, 원래 주소 백업 후 원복) 이메일 알림을 켠 뒤 문의 634 → 관리자 답변으로 `INQUIRY_ANSWERED` 메일을 보냈다.

| 항목 | 결과 |
|---|---|
| 발송 | 관리자 알림 이력 EMAIL `DELIVERED`(Gmail SMTP `250 2.0.0 OK`) |
| 도착 | **Gmail 스팸함**("이전에 스팸으로 확인된 메일과 유사") - 사용자가 스팸 해제 |
| 보낸 사람·제목 | `CareAI <gosky.dev@gmail.com>`, `[CareAI] 문의 답변 완료` |
| 형식 | 최상위 `multipart/alternative`(text/plain → text/html, UTF-8), 첨부 표시 없음(L-3 조치 실메일 확인) |
| HTML | B안 카드·"문의 답변" 칩·제목·본문·꼬리말 두 줄이 렌더 그대로 표시 |
| 발신 인증 | SPF·DKIM·DMARC 모두 pass |

- **스팸 분류는 코드가 아니라 발송 계정 평판 문제다**: `gosky.dev@gmail.com`은 공용 개발 Gmail이고 인증은 모두 통과했다. 처음 받는 사용자에게는 스팸함으로 갈 수 있다. 근본 해결은 우리 도메인 발신 주소나 메일 발송 서비스(SMTP 릴레이) - `.env.dev` 변경이 필요해 결정 대기.
- 네이버 주소(문의 635)로도 보냈으나(EMAIL `DELIVERED`) 받은편지함에 없었다 - 주소 철자 확인 필요, 미확인으로 둔다.
- 남은 데이터: 시험 문의 634·635와 답변(API로 지울 수 없음). 알림 설정은 원래대로(FCM만) 되돌렸다.

## 후속 - 메일 머리글 정체성 (2026-11-21)

실발송 원본 머리글에서 `Received: from a8a43e5e5201`(EHLO)·`Message-ID: <…@a8a43e5e5201>`처럼 컨테이너 ID가 나갔다. `application.yaml`에 `mail.from: ${MAIL_USERNAME}`(Message-ID → `…@gmail.com`, From과 같은 도메인)·`mail.smtp.localhost: ${MAIL_SMTP_LOCALHOST:api.devdmu.gosky.kr}`(gosky 발송 IP `58.75.21.151`을 가리키는 이름)를 더했다. 메일 라이브러리 시험으로 Message-ID는 `mail.smtp.localhost`가 아니라 `mail.from`·`mail.host`가 정한다는 것을 확인하고 `mail.from`을 골랐다(From 도메인과 일치). 알림·비밀번호 재설정 메일 모두 적용, `.env` 수정 없음. vkcs는 발송 IP와 이름이 맞지 않지만 인증 후 제출이라 거절 사유가 아니다(개발 서버, 수용). `MailHeaderIdentityConfigTest`(실제 yaml로 `JavaMailSenderImpl` 생성)가 고정. 효과는 보조 신호 제거뿐이며 스팸함 분류의 주원인(공용 계정 평판)은 그대로다.
