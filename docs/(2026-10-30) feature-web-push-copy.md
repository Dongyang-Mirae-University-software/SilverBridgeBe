# 웹푸시 문구 정리·두 줄 본문·웹 토큰 data 전용 발송 (2026-10-30)

PR #405(`2a15120`) + 후속 #406(`2a27492`, 통합 테스트 컨텍스트). 마이그레이션 없음.

## 원인 - 알림이 두 번 뜬다

FCM 메시지에 `notification` 블록이 있으면 Firebase SW 라이브러리(`@firebase/messaging` onPush)가 알림을 자동으로 띄우고, FE `onBackgroundMessage`가 `showNotification`으로 한 번 더 띄운다. 앱이 뒤에 있을 때 모든 알림이 PC·휴대폰에서 두 번 뜬다.
해결: 웹 토큰(`platform=WEB`, FE는 항상 WEB으로 등록)에는 data만 보내고 FE 서비스 워커 혼자 그린다 - 설정 `notification.fcm.webpush-data-only`.

## 문구 (보호자 수신분)

| 종류 | 제목 | 본문 |
|---|---|---|
| WARD_SOS | `[긴급] {이름}님 SOS` | `{이름}님이 긴급 도움을 요청했습니다.` |
| WARD_SOS 연타 | 같음 | `{이름}님이 계속 도움을 요청하고 있습니다.\n최근 10분 내 {N}번째 요청입니다.` |
| 화재 | `[화재 감지] {이름}님 댁 {장소}` | `{장소} 카메라에서 화재가 감지되었습니다.` |
| 흉기 | `[흉기 감지] …` | `…흉기가 감지되었습니다.\n바로 연락해 안전을 확인하고, 위험하면 112에 신고해 주세요.` |
| 낙상 | `[낙상 감지] …` | `…낙상이 감지되었습니다.\n바로 연락해 안전을 확인해 주세요.` |
| MEDICATION_MISSED | `오늘 복약 확인 · {이름}님` | `{시각}까지 예정된 복약 {N}건 중 {M}건이 체크되지 않았습니다.` |
| 재촉 건별·요약·동수 안내·카메라 끊김·복약 중지 | 그대로 | 문구 그대로, 두 문장 사이만 `\n` |
| 연결·강제 연결·문의 답변 | 그대로 | 그대로(한 문장) |

- 빈 값: 장소 없으면 본문 `등록된 카메라에서…`, 제목은 빈 부분만 뺀다(`[화재 감지] {이름}님 댁` / `[화재 감지] {장소}` / `[화재 감지]`).
- 이상감지 WS: 보호자는 푸시와 같은 새 제목·본문, **피보호자 본인 WS 제목은 `화재 감지` 그대로**.
- 바꾸지 않은 것: 피보호자 수신분 전부(90바이트 단문), 알림톡 템플릿·변수, 이상감지 `smsFallbackText`, 디스패처 정책·쿨다운·문자 대체 판정·이력 구조.

## 문자

- SOS 대체 문자는 `smsFallbackText`로 **바꾸기 전 문구 그대로**(`[긴급 SOS] …`) - 사용자 결정.
- 문자 채널: 제목이 `[`로 시작하면 괄호를 겹치지 않고 `제목\n본문`(사용자가 문자를 켠 이상감지·복약 요약).

## FCM 메시지

- data에 `title`·`body`를 **모든 종류·모든 수신자**에 더한다(FCM 계층에서만 - 알림톡 변수 맵에는 넣지 않는다). 이상감지 `detectedType`·`incidentId`는 원래 있었다.
- `notification.fcm.webpush-data-only`(env `NOTIFICATION_FCM_WEBPUSH_DATA_ONLY`, 기본 false): true면 웹 토큰은 notification 블록 없이, 그 밖의 토큰은 기존대로 따로 보낸다. **전달 판정은 두 묶음 합산**으로 기존과 같다(성공 1건 이상 = 전달, 전부 만료 = `ALL_TOKENS_EXPIRED`, 그 밖 = `PUSH_SERVER_ERROR`) - SOS 문자 대체·쿨다운 해제 근거라 바꾸지 말 것.
- `notification.fcm.high-urgency-types`(env `NOTIFICATION_FCM_HIGH_URGENCY_TYPES`, 쉼표 구분): 웹푸시 `Urgency: high`. 기본 = 사용자 선택 10종 `WARD_SOS`·`ANOMALY_DETECTED`·`ANOMALY_DETECTED_SELF`·`MEDICATION_REMINDER`·`ANOMALY_REVIEW_REQUIRED`·`CAMERA_LINK_LOST`·`CONNECTION_REQUEST`·`CONNECTION_ACCEPTED`·`CONNECTION_REFUSED`·`INQUIRY_ANSWERED`(normal: `MEDICATION_MISSED`·`MEDICATION_STOPPED`·`ANOMALY_REVIEW_CONFLICTED`·`CONNECTION_DISCONNECTED`·`CONNECTION_FORCED`). 배경: 안드로이드 절전 모드에서 복약 알림 12:00 발송 → 12:16 표시.

## 켜는 순서

1. BE 배포(완료 2026-10-30 16:13 vkcs·16:14 gosky) - data.title·body 추가, Urgency 적용. 표시는 지금과 같다.
2. FE 웹푸시 PR 머지·배포 - SW가 `data.title`·`data.body`로 그리는지 확인. ⚠️ 2026-10-30 현재 SW(`public/firebase-messaging-sw.js`)는 `payload.notification`만 읽어서 **먼저 켜면 "알림"·빈 본문으로 뜬다**.
3. 두 서버 `.env.dev` 백업 → `NOTIFICATION_FCM_WEBPUSH_DATA_ONLY=true` → `up -d api`(restart 아님) → 기동 확인.
   - **완료(2026-10-30 17:09, 사용자 확인 후)**: FE #184 배포(16:42, SW가 `data.title`·`body`로 그리고 `notification` 블록이 있으면 그리지 않음)를 배포된 파일로 확인한 뒤 두 서버에 적용. 백업 `.env.dev.bak-20261030-1709`(두 서버), 컨테이너 `printenv` = true, 기동·AI WS 연결·ERROR 0. gosky prod 무변경.
4. 확인: 테스트 계정(SOS·이상감지 제외 종류)으로 PC·휴대폰에서 한 번만 뜨는지.
5. 되돌리기: 그 줄 삭제 후 `up -d api`.

## FE 확인 필요

- WS `anomaly-detected`의 `body`에 `\n`이 들어간다 - 화면은 `white-space: pre-line` 등으로 줄바꿈 표시. 관리자 알림 이력 본문도 같다(표시 차이만).
- data 전용에서 SW는 **매번 `showNotification`을 호출**해야 한다(안 하면 브라우저가 "백그라운드에서 업데이트됨" 알림을 띄우거나 iOS Safari가 구독을 끊을 수 있다).

## 남은 일 (별도 세션)

- **FCM 토큰 누적**: 같은 토큰 재등록은 시각만 갱신하고, 새 토큰이 와도 옛 토큰은 지우지 않는다(상한 5·60일 유휴 정리·발송 시 UNREGISTERED 삭제뿐). 출처가 다른 FE(dev·gosky)는 FCM에서 서로 다른 기기라 각각 알림이 뜬다. 제안: 등록 요청에 선택 필드 `replacesToken`(본인 소유일 때만 삭제) + FE가 토큰 변경 시 옛 토큰 전달.
- 영향 범위 점검(템플릿 C) 초안은 최종 보고에 있다.
