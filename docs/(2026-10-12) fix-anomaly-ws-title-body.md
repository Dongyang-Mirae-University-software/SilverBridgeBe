# 이상감지 실시간 이벤트에 제목·본문 추가 (2026-10-12)

## 증상·원인
- 피보호자 화면에 "거실에서 화재이 감지되었습니다"가 표시됐다(올바른 표현: 화재가).
- WS `anomaly-detected` payload에 `title`·`body`가 없어 FE가 자체 대체 문구(`${label}이 감지되었습니다`, 조사 "이" 고정)를 썼다.
  푸시(FCM) 본문은 BE가 `DetectedTypeLabel.withSubjectParticle`로 만들어 조사가 맞았다.

## 변경
- `AnomalyNotificationListener`: 수신자별 WS 전용 맵 복사본에 `title`·`body`를 넣어 전송. 제목·본문은 반복문 안에서 한 번 만들어
  WS와 FCM(`NotificationContent`)이 같은 값을 쓴다. 공유 `data`(FCM data·알림톡 변수)는 변경하지 않는다.
- 제목을 종류별로 변경: `이상 상황 감지` → `화재 감지`·`낙상 감지`·`흉기 감지`(FCM 제목도 동일하게 바뀐다).
- 마이그레이션 없음. FE 코드 수정 불필요(`payload.body ?? 대체문구`, `payload.title ?? '이상 상황 감지'`).

## 바뀌지 않는 것
FCM 본문·data, 알림톡 variables, SMS 대체문, 쿨다운·미전달 해제·수신자 선정·발송 순서, `[WS-BLOCKED]`, urgent executor, 기존 WS 필드 10개.

## 계약
`docs/(2026-08-31) api-contract-anomaly-dashboard.md` §3 하단에 필드 추가 기재.

## 테스트
`AnomalyNotificationListenerTest`: WS title·body 수신자별 값과 FCM 일치·기존 필드 유지·FCM data에 title/body 없음, 종류별(FIRE/FALL/WEAPON) 조사·제목.
`./gradlew test` BUILD SUCCESSFUL, `./gradlew build -x test` BUILD SUCCESSFUL.

## 점검 판단
`NotificationContent`·`WebSocketEventPublisher` 계약은 건드리지 않고 리스너 한 곳만 바꿨다 -> 영향 범위 점검 C 불요, 기능 점검 B는 범위가 작아 생략 가능.
