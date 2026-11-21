# 기능 점검(B) - 이메일 HTML 디자인 B안(#512)·메일 머리글 정체성(#513) (2026-11-22)

- 대상: BE #512(`09e7205b`, 앱 톤 카드형 HTML·알림 종류 칩) + #513(`e89c5c71`, Message-ID·EHLO에 컨테이너 ID 대신 발송 도메인)
- 배경: #512는 최종 전체 점검(`(2026-11-21) audit-full-2026-11-final.md`) 기준 코드에 들어 있었으나 이메일 HTML을 규칙 수준(허용 7종·가상 이메일·`[CareAI]`)으로만 봤고, #513은 점검 중 머지돼 "범위 밖"이었다.
- 판정: **PASS** · 🔴 0 · 🟠 0 · 🟡 0 · 🟢 2(수용) · 실발송 확인 완료

## PHASE A. 보안

| 항목 | 결과 | 근거 |
|---|---|---|
| 칩·제목·본문 이스케이프 | PASS | `EmailNotificationHtml`이 칩도 `escape(label.strip())`. 칩 문구는 고정 상수지만 이스케이프를 거친다(`이스케이프` 테스트가 칩 포함) |
| 외부 자원 | PASS | 이미지·링크·스타일시트·스크립트·`url(` 없음(`외부자원없음` 테스트) |
| 메일 머리글 | PASS | 사용자 값은 머리글에 들어가지 않는다. `mail.from`·`mail.smtp.localhost`는 설정값만. 제목 CRLF 끼워 넣기 방지는 기존 테스트 그대로 |
| 로그 | PASS | 로그 변경 없음(채널 로그는 userId·예외 클래스명만) |

## PHASE B. 기능 정합성

| 항목 | 결과 | 근거 |
|---|---|---|
| 칩 문구 | PASS | 이메일 허용 종류(`Email.ALLOWED` 7종) 전부 전용 문구 - `label_허용종류_전용문구`가 `allowedChannels()`에서 EMAIL 허용 종류를 모아 확인하므로 새 종류에 이메일을 허용하고 문구를 빼면 실패한다 |
| 텍스트 대체 본문 | PASS | 칩은 HTML에만, 텍스트 본문·꼬리말·제목 접두어·보낸 사람 그대로(`send_칩`) |
| 피보호자 메일 | PASS | 제목이 서비스 이름과 같으면 제목 줄 생략, 칩 다음 본문 |
| Message-ID 도메인 | PASS | 실메일 `Message-ID: <141312148.1.1795280284396@gmail.com>` - From(`gosky.dev@gmail.com`)과 같은 도메인 |
| EHLO 이름 | PASS | 실메일 `Received: from api.devdmu.gosky.kr ([58.75.21.151]) by smtp.gmail.com` - 이전 `a8a43e5e5201` |
| 비밀번호 재설정 메일 영향 | PASS(개선) | From을 지정하지 않는 `SimpleMailMessage`의 봉투 발신자가 컨테이너 기반 주소 대신 `mail.from`(발송 계정)으로 정해진다. 제목·본문 무변경 |

## PHASE C. 구조

- 칩 문구는 채널이 이미 받는 `NotificationType`으로 고른다 - 디스패처·`ChannelResult`·허용 종류 무변경.
- 설정은 `application.yaml` 기본값으로 동작하고 `.env` 무변경. `MailHeaderIdentityConfigTest`가 실제 yaml로 `JavaMailSenderImpl`을 만들어 Message-ID 도메인·EHLO 이름·기존 SMTP 설정(인증·STARTTLS·10초 제한)을 고정.

## PHASE D. 테스트

렌더러 9건(구성 순서·앱 톤·글자 크기·줄바꿈·이스케이프·외부 자원·반응형·생략), 채널(칩 문구 전수·보낸 HTML의 칩), 메일 머리글 3건. 빈틈 없음.

## 실발송 확인 (2026-11-22, gosky dev `e89c5c71`)

시험 보호자 이메일을 사용자 Gmail로 잠시 바꾸고(사용자 `!` 실행, 원래 주소 백업 후 원복) 문의 636 → 관리자 답변으로 1통. 관리자 이력 EMAIL `DELIVERED`, **받은편지함 도착**(지난 11/21 사용자가 스팸 해제한 뒤), SPF·DKIM·DMARC pass, 최상위 `multipart/alternative`. 알림 설정은 스크립트가 원래대로(FCM만) 되돌렸다.

## 이슈

- 🟢 **L-1(수용)** vkcs도 EHLO를 `api.devdmu.gosky.kr`로 밝혀 발송 IP와 맞지 않는다. 인증 후 제출이라 거절 사유가 아니고 개발 서버라 수용(바꾸려면 vkcs `.env`에 `MAIL_SMTP_LOCALHOST`).
- 🟢 **L-2(수용, 기존)** 처음 받는 사용자에게는 공용 발송 계정 평판 때문에 스팸함으로 갈 수 있다. 이번 머리글 정리는 보조 신호 제거뿐 - 근본 해결(우리 도메인 발신 주소·SMTP 릴레이)은 사용자 결정 대기.
- 남은 데이터: 시험 문의 636과 답변(API로 지울 수 없음).
