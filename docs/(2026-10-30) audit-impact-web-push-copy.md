# 영향 범위 점검 (템플릿 C) - 웹푸시 문구·data 전용 발송 (2026-10-30)

대상: PR #405·#406(`FcmService` 메시지 구성·`SmsNotificationChannel` 괄호 규칙·보호자 문구 7곳), data 전용 켜기(17:09). 결과: **PASS - 🟢 1건(수용 권장), 정보 1건**. 코드 변경 없음.

## 확인한 것

| 항목 | 결과 |
|---|---|
| `FcmService.sendToUser` 호출처 | `FcmNotificationChannel` 1곳뿐. Firebase를 직접 부르는 곳도 `FcmService`뿐 → **모든 종류·모든 수신자**에 data.title·body가 실린다 |
| 알림톡 변수 | FCM 계층에서 복사본에만 넣어 호출자 data(알림톡 변수 맵)는 그대로. 알림톡 테스트 무수정 통과 |
| 피보호자 문구 | 무변경(`WardSmsAssertions`·본인 문구 테스트 무수정 통과), 본인 WS 제목 `화재 감지` 유지 |
| FE 서비스 워커(gosky dev 배포본) | `data.title || notification.title || 대체`, `notification` 블록이 없으면 **항상 `showNotification`** → iOS Safari "보이지 않는 푸시"로 구독이 끊길 경로 없음 |
| FE 앞화면 알림·WS 토스트 | `PushNotificationListener`가 data 우선으로 읽고 본문 `white-space: pre-line`(FE 테스트로 고정). WS `anomaly-detected`도 같은 목록으로 표시 |
| 웹·비웹 분할 | 운영(gosky) 토큰 13개 **전부 WEB** → 분할은 실제로 일어나지 않고 1회 발송. 비웹 토큰이 생겨도 판정은 합산(`FcmServiceMessageTest`) |
| 켠 뒤 실제 발송 | 17:17 발송 1건 접수(성공 2 = 해당 사용자 토큰 2개) |

## 발견

- **L-1 🟢 (수용 권장)**: 사용자가 문자를 켠 **낙상** 알림이 89B(SMS)에서 101B(LMS)로 늘었다(제목 줄 `[낙상 감지] {이름}님 댁 {장소}`이 추가되고 본문에 장소가 한 번 더 들어감, 이름 3자·거실 기준). 흉기는 원래 LMS, 화재 55→67B(SMS 유지), 복약 요약은 94→84B로 **LMS → SMS**로 줄었다. 대상이 문자를 켠 보호자뿐이고 대체 문자(푸시 미전달)는 무변경이라 수용을 권장한다.
- **I-1 (정보)**: 관리자 알림 이력 화면은 FE에 아직 없다(관리자 FE = 대시보드·회원). 만들 때 본문에 `pre-line`을 쓸 것 - 이력 본문에 `\n`이 들어 있다.

## FCM 토큰 누적 - BE 변경 불필요

- FE `f9232e5`(2026-10-30)가 토큰이 바뀌면 `localStorage`의 옛 토큰을 기존 `DELETE` API로 해제하고, 로그아웃 때도 해제한다 - 제안했던 `replacesToken`과 같은 효과라 BE에 필드를 만들 이유가 없다.
- 남은 토큰(사용자당 최대 4개)은 모두 그 이전 등록분이다. 브라우저 데이터가 지워진 토큰은 다음 발송 때 `UNREGISTERED`로 자동 삭제되고, 그 밖은 60일 유휴 정리(04:00)·사용자당 5개 상한이 걷어낸다.
- 출처(dev·prod·localhost FE)가 다른 토큰은 실제로 다른 구독이라 각각 알림을 받는 것이 정상이다(같은 PC에서 두 출처를 다 쓰면 두 번 받음 - 테스트 환경 한정).
