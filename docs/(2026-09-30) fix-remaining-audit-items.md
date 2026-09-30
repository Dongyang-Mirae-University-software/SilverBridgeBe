# 점검 잔여 이슈 처리 - H-1·E-2·SOS 경계값·L-5 (2026-09-30)

> 대상: `docs/audit-index.md`의 ⚠️·❌ 잔여 항목. 마이그레이션 없음.
> 아키텍처 경계 F-1~F-3 refactor는 별도 PR(`refactor/package-boundaries`), 이상감지 통합 경로는 `(2026-09-30) issue-anomaly-camera-integration-gap.md`.

## 요약

| 항목 | 판정 | 조치 |
|---|---|---|
| **H-1** 탈퇴 리스너 트랜잭션 전파 | **실제 결함** (대장 추정 "무해"는 틀렸음) | 두 메서드 `REQUIRES_NEW` + 실 DB 통합 테스트 |
| **E-2** 이상감지 동시 응답 덮어쓰기 | 재현됨 | 상황 행 쓰기 잠금 + 통합 테스트 |
| **(신규)** 감지 승계가 판정을 되돌림 | 재현됨 (E-2 작업 중 발견) | `AnomalyIncident`에 `@DynamicUpdate` + 통합 테스트 |
| **#249 L-3** SOS 반복 횟수 테스트 공백 | 공백 3건 중 3건 해소 | 단위 2건 + 실 DB 1건 |
| **#245 L-5** 강제 연결 WS 이벤트명 | FE에서 사실 확인 | 백엔드 무변경, FE 요청 |
| **#257 M-1** 알림 이력 실서버 기록 | 확인 완료 | 코드 변경 없음 |

## H-1 - 탈퇴 시 상대방 해제 알림이 나가지 않고 있었다

### 원인

탈퇴 정리 리스너 `UserWithdrawalConnectionListener`·`MedicationWithdrawalListener`는 **동기 AFTER_COMMIT**이다(purge 전에 끝나야 해서 `@Async` 금지). 커밋 직후라 탈퇴 트랜잭션의 자원이 아직 스레드에 묶여 있고, 여기서 부른 `@Transactional`(REQUIRED) 메서드는 **이미 커밋된 트랜잭션에 합류**한다. 합류한 쪽의 커밋은 아무것도 하지 않는다.

- `ConnectionService.tearDownConnectionsOnWithdrawal` - 연결 `DISCONNECTED`·`CANCELLED` 전환이 커밋되지 않음. **같은 트랜잭션에서 발행한 `ConnectionDisconnectedEvent`도 AFTER_COMMIT을 맞지 못해** `ConnectionNotificationListener.handleDisconnected`(상대 해제 알림)가 **호출되지 않았다**.
- `MedicationWithdrawalService.removeMedicationsRegisteredBy` - 약 삭제가 커밋되지 않음. 건수 집계는 메모리에서 끝나 **중지 안내 알림은 나갔다**.

행 자체는 뒤이은 purge의 FK CASCADE가 지워 DB만 보면 멀쩡했다. 그래서 대장에는 "무해 추정"으로 남아 있었다. 운영(gosky)에는 V54 배포 이후 탈퇴가 0건이라 로그 흔적은 없다.

### 수정

두 메서드를 `@Transactional(propagation = REQUIRES_NEW)`로. 사용처는 각 탈퇴 리스너 하나뿐이다(역할 변경 정리 `tearDownConnectionsOnRoleChange`는 일반 트랜잭션 안에서 불리므로 그대로 REQUIRED).

**동작 변화**: 탈퇴 시 ACTIVE 연결 상대에게 해제 알림(`CONNECTION_DISCONNECTED`, FCM·WS)이 **이제 실제로 나간다** - 원래 의도(D-USER-3)대로다. 알림은 `@Async`라 purge와 경합할 수 있고, 피보호자 탈퇴 시 그 알림의 이력 행만 FK 때문에 남지 않을 수 있다(2026-09-22 L-3에서 수용한 한계와 같음).

### 검증 - `WithdrawalListenerCommitIntegrationTest` (vkcs, 실 PostgreSQL)

| 실행 | 결과 |
|---|---|
| 수정 전 코드 + 새 테스트 | **2건 실패** - 연결 `expected DISCONNECTED but was ACTIVE`, 약 `Expecting empty but was [Medication…]` |
| 수정 후 | 2건 통과 - 연결 `DISCONNECTED` 커밋, **해제 이벤트가 AFTER_COMMIT 리스너에 도달**(대상 = 피보호자, 당사자 ID 포함), 약 삭제 커밋 |

## E-2 - 이상감지 판정 동시 쓰기

### ① 보호자끼리 동시 응답

`submitFeedback`은 응답 목록을 읽고 → 내 표를 더해 → 판정을 덮어쓴다. 두 보호자가 같은 순간 반대로 답하면 각자 상대 표를 못 본 채 확정해 동수가 `REAL`/`FALSE_ALARM`으로 남았다.

- **수정**: `AnomalyIncidentRepository.findByIdForUpdate`(`@Lock(PESSIMISTIC_WRITE)`, `SELECT … FOR UPDATE`)로 상황 행을 읽는다. 같은 상황의 응답이 한 줄로 서고, 뒤 응답은 앞 응답의 커밋을 본 뒤 집계한다(PostgreSQL READ COMMITTED - 잠금 해제 뒤 실행하는 `findByIncidentId`는 새 스냅샷을 본다).
- 낙관적 잠금(`@Version`)은 진 쪽 응답이 실패해 보호자에게 재시도를 요구하므로 쓰지 않았다(2026-09-21 정책 파일 방향 그대로).
- 대가: 같은 상황에 동시에 응답할 때만 수 ms 대기. 다른 상황끼리는 무관.

### ② 감지 승계가 판정을 되돌림 (신규 발견)

`AnomalyIncident`에 `@DynamicUpdate`가 없어 JPA가 **전체 컬럼을 UPDATE**한다. AI 감지가 상황을 승계(`addDetection`)하는 트랜잭션이 행을 읽은 뒤 그 사이 보호자 응답이 커밋되면, 감지 쪽이 **읽어 둔 옛 `review_status`(PENDING)를 다시 쓴다**. 화재가 이어지는 동안 보호자는 알림을 받자마자 답하므로 ①보다 흔할 수 있다.

- **수정**: `@DynamicUpdate` - 감지는 `last_detected_at`·`event_count`·`max_confidence`만, 응답은 `review_status`만 쓴다. 감지 쪽에 잠금을 거는 안은 AI 수신 스레드를 보호자 응답 트랜잭션에 묶게 되어 택하지 않았다.
- 감지끼리의 `event_count++` 경합은 AI WS 수신이 단일 스레드라 해당 없다.

### 검증 - `AnomalyReviewConcurrencyIntegrationTest` (vkcs, 실 PostgreSQL)

| 실행 | 결과 |
|---|---|
| 수정 전 코드 + 새 테스트 | **2건 실패** - 동시 반대 응답 `[round 0] expected CONFLICTED but was REAL`, 감지 승계 후 `expected REAL but was PENDING` |
| 수정 후 | 2건 통과 - 동시 반대 응답 20회 모두 `CONFLICTED`, 감지 승계 후 `REAL` 유지·`event_count=2` |

감지 승계 테스트는 순서를 강제한다(감지 트랜잭션이 읽음 → 응답 커밋 → 감지 커밋)라 결정적이다. 동시 응답 테스트는 확률적이지만 수정 전 첫 회에 실패했다.

## #249 L-3 - SOS 반복 횟수 테스트 공백

| 공백 | 조치 |
|---|---|
| 1. 문구 전환 경계값 2 | `SosNotificationListenerTest` - 2건이면 "계속 도움을 요청하고 있습니다. (최근 10분 내 2번째)" |
| 2. 0건 보정(`Math.max`) | 같은 클래스 - 0건이면 1회로 보정, 기존 문구·`repeatCount=1` |
| 3. 파생 쿼리 실 DB 미실행 | `SosRepeatCountIntegrationTest` - 창 시작 시각과 같은 행은 셈, 1초 이른 행·다른 피보호자 행은 안 셈 |

3번은 운영 로그(gosky 2026-09-30 `최근 10분 내 4번째`)로도 쿼리가 실제로 도는 것이 확인됐다.

## #245 L-5 - 강제 연결 시 피보호자 화면 실시간 갱신 누락

- 확인(SilverBridgeFe `dc04d2d`, `src/lib/realtime/connectionSocket.ts`): `WARD_CONNECTION_TOPICS`에 `connection-accepted`가 없다. 백엔드 `handleForced`는 피보호자에게 `connection-accepted`를 보내므로 **피보호자 화면은 새로고침 전까지 연결을 모른다**(FCM `CONNECTION_FORCED`는 도착).
- **결정(2026-09-30): 백엔드 무변경, FE에 구독 추가 요청.** 전용 이벤트명(`connection-forced`)을 새로 두는 안은 양쪽 변경이 필요해 택하지 않았다.
- FE 요청 문구:
  > `connectionSocket.ts`의 `WARD_CONNECTION_TOPICS`에 `{ destination: 'connection-accepted', type: 'CONNECTION_ACCEPTED' }`를 추가해 주세요. 관리자가 대신 연결해 줄 때(강제 연결) 피보호자에게 이 이벤트가 가는데, 지금은 구독하지 않아 피보호자 화면의 보호자 목록이 새로고침 전까지 갱신되지 않습니다. 페이로드는 보호자 쪽 수락 이벤트와 같은 형태이며, 받으면 활성 보호자 목록을 다시 조회하면 됩니다.

## #257 M-1 - 알림 이력 실서버 기록 확인

gosky(V54, 2026-09-23 기동)에서 사용자가 연결 해제 → 요청 → 수락 → SOS → 해제를 실행한 뒤 `notification_log`를 조회(SELECT, 사용자 승인): 요청·수락·해제 2·SOS 2건이 각 1행씩 `DELIVERED`, 연결 알림의 `ward_id`(#258 L-3)가 모두 채워짐. 배포 후 7일 `[NOTIFY-LOG-FAILED]` 0건.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `connection/service/ConnectionService.java` | `tearDownConnectionsOnWithdrawal` → `REQUIRES_NEW` |
| `medication/service/MedicationWithdrawalService.java` | `removeMedicationsRegisteredBy` → `REQUIRES_NEW` |
| `anomaly/repository/AnomalyIncidentRepository.java` | `findByIdForUpdate` 추가 |
| `anomaly/service/GuardianAnomalyService.java` | `submitFeedback`이 잠금 조회 사용 |
| `anomaly/entity/AnomalyIncident.java` | `@DynamicUpdate` |
| `test/.../GuardianAnomalyServiceTest.java` | 목 대상 `findById` → `findByIdForUpdate` |
| `test/.../SosNotificationListenerTest.java` | 경계값 2·0 케이스 |
| `integrationTest/.../WithdrawalListenerCommitIntegrationTest.java` | 신규 |
| `integrationTest/.../AnomalyReviewConcurrencyIntegrationTest.java` | 신규 |
| `integrationTest/.../SosRepeatCountIntegrationTest.java` | 신규 |

## 테스트 결과

- `./gradlew test` **683 / 0 실패**(스킵 1, 기존 681 + 2) · `./gradlew build -x test` 통과
- vkcs 통합 테스트(작업 트리를 임시 폴더로 보내 `integration-test.sh`와 같은 컨테이너로 실행, 배포 폴더 미접촉): 수정 전 **26건 중 4건 실패**(위 재현) → 수정 후 **26 / 0 실패**. 임시 폴더·테스트 컨테이너 잔존 0.
