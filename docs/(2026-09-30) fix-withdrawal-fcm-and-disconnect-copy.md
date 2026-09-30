# 탈퇴 FCM 토큰 삭제 전파(M-1) + 탈퇴·역할 변경 해제 알림 문구(M-2) (2026-09-30)

> 근거: `docs/(2026-09-30) audit-impact-remaining-audit-items.md` M-1·M-2. 마이그레이션 없음.

## 요약

| ID | 문제 | 수정 |
|---|---|---|
| M-1 | `UserWithdrawalFcmListener`(동기 AFTER_COMMIT) → `FcmService.deleteAllTokens`(REQUIRED)가 이미 커밋된 탈퇴 트랜잭션에 합류해 **삭제가 커밋되지 않음**(H-1과 같은 결함). 로그는 "삭제"로 찍힘 | `deleteAllTokens` → `REQUIRES_NEW`, 옛 주석("soft delete라 CASCADE 미발동") 정정 |
| M-2 | 탈퇴 정리가 `DisconnectedBy.GUARDIAN`/`WARD`를 재사용 → 남은 쪽이 "상대가 연결을 해제했습니다"를 받음(#259로 처음 발송 시작) | `DisconnectedBy.WITHDRAWN` 추가 → "보호자가 탈퇴해 연결이 종료되었습니다." / "피보호자가 탈퇴해 연결이 종료되었습니다." |
| (M-2 확장, 사용자 결정) | 관리자 **역할 변경** 정리도 `GUARDIAN`/`WARD`를 재사용 → "보호자가 연결을 해제했습니다"(이 경로는 예전부터 발송 중) | 기존 `DisconnectedBy.ADMIN` 사용 → "관리자가 연결을 해제했습니다." |

## 문구 결정

- 탈퇴는 받는 사람 기준으로 나눈다. 알림은 **남은 쪽에만** 가므로 떠난 쪽 = `notifyTargetId`의 반대편이다. 이벤트에 따로 싣지 않고 리스너에서 `notifyTargetId.equals(wardId)`로 판단한다.
- 역할 변경은 관리자 조작이라 강제 해제와 같은 값(`ADMIN`)을 쓴다. 새 값을 만들지 않았다.
- 바뀌지 않은 것: 당사자 해제(`GUARDIAN`/`WARD`)·관리자 강제 해제 문구, PENDING 무알림, WS 이벤트명(`connection-cancelled`)·`data.type`(`CONNECTION_CANCELLED`)·알림 종류(`CONNECTION_DISCONNECTED`).

## FE 영향

없음. SilverBridgeFe는 `disconnectedBy`를 읽지 않고, `CONNECTION_CANCELLED` 푸시는 서버 `body`를 그대로 표시한다(`payload.body ?? '연결이 해제되었습니다.'`).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `notification/service/FcmService.java` | `deleteAllTokens` REQUIRES_NEW, 주석 정정 |
| `connection/event/ConnectionDisconnectedEvent.java` | `DisconnectedBy.WITHDRAWN` 추가, `ADMIN` 설명에 역할 변경 포함 |
| `connection/service/ConnectionService.java` | 탈퇴 정리 → `WITHDRAWN`, 역할 변경 정리 → `ADMIN` |
| `connection/listener/ConnectionNotificationListener.java` | `WITHDRAWN` 문구 분기 |
| `test/.../ConnectionServiceTest.java` | 탈퇴 2건 기대값 `WITHDRAWN`, 역할 변경 `ADMIN` 단언 추가 |
| `test/.../ConnectionNotificationListenerTest.java` | 탈퇴 문구 2건 신규 |
| `integrationTest/.../WithdrawalListenerCommitIntegrationTest.java` | FCM 토큰 삭제 커밋 케이스, 해제 이벤트 `WITHDRAWN` 단언 |
| `build.gradle` | integrationTest 스위트에 `firebase-admin:9.10.0`(main과 같은 버전) - `FcmService`를 실제 빈으로 올릴 때 `FirebaseMessaging` 목 타입이 필요한데 main의 `implementation`은 이 스위트 컴파일 경로에 전이되지 않는다. 배포 jar 무관 |

## 테스트 결과

- `./gradlew test` **685 / 0 실패**(기존 683 + 2) · `./gradlew build -x test` 통과
- vkcs 통합 테스트:
  - `FcmService`만 수정 전으로 되돌림 → **27건 중 1건 실패**(`탈퇴_FCM토큰정리는_커밋된다` - 토큰 잔존) = M-1 재현
  - 수정본 → **27 / 0 실패**
  - M-2는 새 enum 값을 참조해 수정 전 코드로는 컴파일되지 않으므로 대조 실행 대신 단위 테스트로 고정(리스너 문구 2, 발행 값 3)
