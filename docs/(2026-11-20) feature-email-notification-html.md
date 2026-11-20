# 이메일 알림 HTML 본문·보낸 사람 이름 (2026-11-20)

## 배경

2026-11-20 발표 자료를 확인하다 gosky에서 실제로 나가는 이메일 알림이 텍스트뿐이라는 것을 발견했다.
보낸 사람은 이름 없이 SMTP 계정 주소만 보이고, 피보호자 알림 제목은 `[실버브릿지] 실버브릿지`로 겹쳐 무슨 알림인지 알 수 없었다.

## 범위

| 항목 | 내용 |
|---|---|
| 메일 형식 | `SimpleMailMessage` → `MimeMessage` multipart/alternative(텍스트 + HTML). 텍스트 본문은 기존 내용 그대로 |
| HTML | 서비스 이름 띠 → 제목(22px) → 본문(18px, 줄바꿈 유지) → 꼬리말(15px, 기존 두 줄 문구). 인라인 스타일·표 레이아웃, `max-width:560px;width:100%`, 외부 이미지·폰트·링크·추적 픽셀 없음 |
| 이스케이프 | 제목·본문(보호자·피보호자 이름이 들어감)은 `HtmlUtils.htmlEscape` 후 줄바꿈만 `<br>` |
| 보낸 사람 | `"실버브릿지" <spring.mail.username>`. 표시 이름은 `notification.email.from-name`(기본 "실버브릿지", `.env` 수정 불필요). 주소 설정이 비면 From 미지정(기존 동작) |
| 제목 | 제목이 서비스 이름과 같으면(피보호자 알림 `WardNotificationText.TITLE`) 접두어 생략 → `실버브릿지`. HTML에서도 그 경우 제목 줄을 생략(띠가 같은 이름을 보여 줌). 그 밖은 `[실버브릿지] {제목}` 그대로 |
| 실패 | 메일 작성 중 `MessagingException`·`UnsupportedEncodingException`·`MailException`은 `PROVIDER_ERROR`로 기록, 로그는 클래스명만 |

## 결정 (사용자, 2026-11-20)

- Q1 비밀번호 재설정 메일: **바꾸지 않음**(A안). 제목 `[SilverBridge] 비밀번호 재설정 인증번호`·From 미지정 그대로.
- Q2 피보호자 메일 제목 중복: **접두어 생략**. 피보호자 메일은 제목만으로는 종류를 알 수 없다(본문 미리보기로 보임) - 수용.
- 앱으로 돌아가는 링크: FE 주소 설정 키가 없어(CORS 목록뿐) 만들지 않음.

## 바뀌지 않는 것

허용 종류(`Email.ALLOWED` 7종), 긴급 알림(SOS·이상감지) 이메일 제외, 카카오 가상 이메일 `NOT_APPLICABLE`, 주소 없음 `NO_EMAIL`,
`EmailSendExecutor` 호출 전체 시간 제한, `ChannelResult`·사유 코드, `NotificationDispatcher`, 꼬리말·접두어 문구, 로그 규칙, 마이그레이션 없음.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `domain/notification/channel/EmailNotificationChannel.java` | MimeMessage·From 이름·접두어 생략·작성 실패 처리 |
| `domain/notification/channel/EmailNotificationHtml.java` | 신규 - HTML 렌더러 |
| `application.yaml` | `notification.email.from-name` 1줄 |
| `EmailNotificationChannelTest.java` | MimeMessage 기준으로 재작성 + 보낸 사람·두 본문·alternative·접두어 생략·이스케이프·작성 실패 |
| `EmailNotificationHtmlTest.java` | 신규 - 구성 순서·줄바꿈·이스케이프·외부 자원 없음·반응형 폭·제목 줄 생략, `EMAIL_RENDER_DIR`가 있으면 대표 4종 렌더 파일 출력 |

## 테스트 결과

- 이메일 관련(채널 17·HTML 7(렌더 출력 1 건너뜀)·정책·디스패처 이메일·`LogRawExceptionGuardTest`) 통과.
- `./gradlew build` 통과: 테스트 2872건, 실패 0, 건너뜀 22.
- 렌더 확인: 연결 수락·문의 답변·복약 미복용 요약·피보호자 연결 요청을 360px 폭 headless Chromium으로 확인(깨짐 없음). 스크린샷은 작업 보고에만 첨부하고 저장소에는 넣지 않았다.
- 실제 발송(받은편지함 표시)은 이 문서 작성 시점에 미확인.
