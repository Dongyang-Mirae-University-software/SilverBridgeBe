# (2026-11-02) 기능 점검(B) - SOS WebSocket 제목·본문 (#433)

> 대상: PR #433(머지 `08bae07`) - `sos-triggered` payload에 푸시와 같은 `title`·`body` 추가. 템플릿 B(기능 + 건드린 공용 코드 사용처).
> 영향 범위 점검(C)이 아닌 이유: Spring 이벤트·`NotificationDispatcher`·enum·`ConnectionService` 같은 공용 규칙을 바꾸지 않았고, 이벤트 하나의 WS payload에 키를 **추가만** 했다.

## 결론

**PASS** · 🔴 0 · 🟠 0 · 🟡 0 · 🟢 2(FE 예비 문구, 조치 불요)

## 점검 항목

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| 1 | BE 발송처 전수 | PASS | `sos-triggered` 발송은 `SosNotificationListener` 한 곳. 제목·본문은 쿨다운 통과 뒤 루프 밖에서 한 번 만들고 WS·푸시가 같은 변수를 쓴다 |
| 2 | null 안전 | PASS | `wardName`은 `SosService`가 빈 값이면 대체 이름으로 채운다. `title`·`body`는 문자열 결합이라 null 아님(`Map.of` NPE 없음) |
| 3 | 정지 수신자·쿨다운·전달 집계 | PASS(변경 없음) | `WebSocketEventPublisher.isBlocked`는 payload와 무관. 쿨다운 중이면 WS도 없음(기존 테스트). WS는 여전히 전달 집계에 들어가지 않는다 |
| 4 | 정보 노출·로그 | PASS | 새로 실린 값은 이미 payload에 있던 `wardName`·`repeatCount`로 만든 문장뿐. 발송 실패 로그는 예외 클래스명만(기존). 구독 인가(`StompSubscriptionAuthorizationInterceptor`) 그대로 |
| 5 | FE 소비처 전수 (`develop` `784b559`) | PASS | `sos-triggered`는 보호자만 구독(`GUARDIAN_CONNECTION_TOPICS`), 피보호자 `WardLayout`은 구독하지 않음. `connectionSocket.ts`가 `title`·`body`를 읽고 → `GuardianLayout`이 `data`·`notification`에 싣고 → `PushNotificationListener.getPushNotificationContent`가 `data` 값을 먼저 쓴다. 서비스 워커(`push-sw-logic.js`)는 FCM 경로라 무관 |
| 6 | WS·FCM 중복 제거 | PASS(개선) | FE는 `sosEventId`로 WS·FCM 포그라운드 중 먼저 온 것만 띄운다. WS가 디스패치보다 먼저 나가므로 전에는 FE 예비 문구가 이겼다(원래 문제의 원인). 이제 어느 쪽이 먼저 와도 같은 문구 |
| 7 | 두 줄 표시 | PASS | 토스트 본문 `pre-line`(FE 브라우저 테스트 고정) + gosky 실측 스크린샷에서 두 줄 확인 |
| 8 | Swagger | PASS | gosky `/v3/api-docs`의 `POST /api/ward/sos` 설명에 WS 키·푸시 제목·반복 본문이 의도대로 나옴 |
| 9 | 테스트가 회귀를 잡는지(변이) | PASS 4/4 | 아래 표 |
| 10 | 실서버 | PASS | gosky dev SOS 1182·1188(기능 문서 §검증). vkcs CD 성공, 두 서버 `08bae07` |

### 변이 시험 (`SosNotificationListenerTest`)

| 변이 | 결과 |
|---|---|
| M1 반복 때 WS 본문을 1회 문구로(원래 버그 재현) | 검출 |
| M2 WS 제목을 옛 "긴급 SOS"로 | 검출 |
| M3 WS `title`·`body` 키 제거 | 검출 |
| M4 WS `title`·`body` 값 뒤바꿈 | 검출 |

## 잔여 (🟢, 조치 불요)

- **L-1** FE 예비 제목이 아직 "긴급 SOS"다(`realtime.ts`·`PushNotificationListener.tsx` `getConnectionPushNotification`). BE가 `title`을 늘 싣는 지금은 쓰이지 않는 경로라 화면에 영향 없음. FE 담당이 다음에 그 파일을 고칠 때 "[긴급] ○○님 SOS"로 맞추면 된다(사용자 결정으로 FE 전달 문서는 쓰지 않음).
- **L-2** Swagger 설명의 "최근 10분"은 문장으로 적혀 있어 `REPEAT_WINDOW`를 바꾸면 함께 고쳐야 한다(기존 1회 문구 설명과 같은 방식, 수용).
