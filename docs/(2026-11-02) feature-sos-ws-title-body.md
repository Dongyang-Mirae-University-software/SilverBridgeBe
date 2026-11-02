# (2026-11-02) SOS WebSocket(sos-triggered)에 제목·본문 싣기

## 배경

- FE #205(2026-11-02)에서 화면 안 SOS 알림창의 예비 문구를 "○○님이 긴급 도움을 요청했습니다."로 맞췄다. WS `sos-triggered` payload에 `body`가 없어 FE가 늘 예비 문구를 썼기 때문이다.
- 남은 차이: 같은 피보호자가 10분 안에 SOS를 반복하면(`repeatCount` ≥ 2) 푸시 본문은 "○○님이 계속 도움을 요청하고 있습니다.\n최근 10분 내 N번째 요청입니다."인데 화면 안 알림창은 첫 문구를 그대로 보여 줬다(gosky dev 실측 sosEventId 1181). 제목도 푸시는 "[긴급] ○○님 SOS", 화면 안 알림창은 FE 예비 제목 "긴급 SOS"였다.

## 전제 확인 (코드)

| 항목 | 결과 |
|---|---|
| 리스너가 제목·본문을 한 번 만듦 | `SosNotificationListener`가 쿨다운 통과 뒤 루프 밖에서 `buildTitle`·`buildBody` |
| `wardName` null 가능성 | 없음 - `SosService`가 빈 이름이면 대체 이름으로 채워 이벤트에 싣는다(`Map.of` NPE 없음) |
| `WebSocketEventPublisher` | payload를 그대로 보낸다. 정지 수신자 차단은 payload와 무관 |
| FE 사용처 | `connectionSocket.ts`가 `title`·`body`를 읽고 `realtime.ts`가 `payload.title ?? '긴급 SOS'`, `payload.body ?? 예비 문구` 순서로 쓴다. 두 줄 본문은 `PushNotificationListener`가 `pre-line`으로 그린다 → **FE 수정 불필요** |

## 변경

- `SosNotificationListener`: WS payload에 `title`·`body` 추가. 푸시(`NotificationContent`)와 **같은 변수**를 넣어 문구가 다시 갈라지지 않게 했다. 기존 키 `wardId`·`wardName`·`sosEventId`·`repeatCount`는 그대로.
- `WardSosController` Swagger 설명문: WS payload 키와 푸시 제목(옛 "긴급 SOS"로 남아 있던 것을 "[긴급] ○○님 SOS"로)·반복 본문 안내 갱신.
- 화면 안 알림창 제목이 "긴급 SOS" → "[긴급] ○○님 SOS"로 바뀐다(의도, 2026-11-02 사용자 결정).

## 바뀌지 않는 것

- 1회 SOS 본문(글자 그대로), 문자 폴백 문구, 쿨다운(푸시·WS·문자 단일 10초)·문자 시간당 상한·전달 집계(WS는 여전히 미전달로 셈), 알림 이력, `NotificationType` 정책, FE 코드.

## 검증

- `SosNotificationListenerTest`: 1회 WS payload 키 6개·제목·본문이 푸시와 같음(신규), 반복(3회) WS 제목·본문이 푸시와 같음(보강), 쿨다운 중이면 WS도 없음(기존).
- `./gradlew test`·`./gradlew build -x test` 통과.
- gosky dev 실측(2026-11-02 15:44, PR #433 머지 `08bae07` 배포 뒤): 로컬 Playwright로 시험 보호자 탭(devdmu.gosky.kr)을 열어 두고 시험 피보호자가 SOS 2건(13초 간격).
  - sosEventId 1182: 화면 안 알림창 "[긴급] 남궁님 SOS" / "남궁님이 긴급 도움을 요청했습니다." - 보호자 2명 대상·2명 전달
  - sosEventId 1188: "[긴급] 남궁님 SOS" / "남궁님이 계속 도움을 요청하고 있습니다.\n최근 10분 내 2번째 요청입니다."(두 줄로 표시) - 2명 대상·2명 전달
  - 시험 브라우저 FCM 토큰은 DELETE로 해제(200). 같은 시각의 1183~1187·1189는 다른 피보호자의 SOS로 이번 시험과 무관.
