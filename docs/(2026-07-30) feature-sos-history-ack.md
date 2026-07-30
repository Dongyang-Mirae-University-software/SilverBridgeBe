# 보호자용 SOS 이력 조회 + 처리 결과(ACK) — 2026-07-30

> 브랜치 `feature/sos-history-ack` (base `dev` @ `a5a3b8d`) · 마이그레이션 **V33**

## 1. 배경

- 피보호자 SOS는 **발생**만 구현돼 있었다(`POST /api/ward/sos` → `sos_event` 적재 + 보호자 긴급 알림).
  보호자는 푸시를 받은 뒤 **"지난 SOS가 언제 몇 건 있었고 어떻게 처리됐는지" 볼 수단이 없었다** — 이력 조회 API 자체가 없었고 `SosEventRepository`는 빈 인터페이스였다.
- 요청 근거는 보호자 대시보드 프로토타입("김영희 님 SOS 이력" 카드 리스트, 건별 `안전 확인`/`응급 출동` 배지).
  ⚠️ **프로토타입은 목업이며 운영(gosky) FE에 반영되지 않았다.** 로컬 FE 리포에도 보호자 SOS 이력 화면·API 호출이 전무해(`(guardian)` 하위에 sos 디렉터리 없음) **API 계약은 백엔드가 정의**했다.

## 2. 범위

| 포함 | 제외 |
|---|---|
| 보호자용 이력 조회(페이징, 최신순) | **위치 표시(📍)** — SOS는 버튼 입력이라 위치 데이터 소스가 없다. `camera.label`은 카메라 전용이고, SOS 발생 API에 위치를 추가하면 요청 계약 변경 + FE 동시 수정이 필요해 별도 작업으로 분리 |
| 처리 결과(ACK) 기록·재기록 | 화면 문구 조립("통화 연결 · 안전 확인") — 프론트 책임 |
| ACK 후 실시간 동기화(WebSocket) | 피보호자 본인의 이력 조회 API(보호자용만) |
| ACTIVE 연결 기반 인가(IDOR 차단) | ACK에 대한 푸시·문자 발송 |

**기존 동작 무변경**: `SosService.trigger()`, `POST /api/ward/sos` 요청·응답, `SosNotificationListener`(WARD_SOS 강제 발송), `SosNotificationCooldown`, SOS 동작 설정(V32) 전부 손대지 않았다.

## 3. 정책 결정

1. **ACK는 이력 행당 하나** — `sos_event`에 컬럼 4개를 직접 붙였다(별도 보호자별 ACK 테이블 없음). "그 SOS가 어떻게 처리됐는지"는 보호자가 여러 명이어도 공유된 하나의 사실이고, 프로토타입 배지도 건당 1개다. 여러 보호자가 처리하면 **마지막 처리로 덮어써진다**.
2. **미처리는 `ack_status IS NULL`** — enum에 `PENDING`을 두지 않았다(NULL과 의미 중복). 기존 이력 백필 불필요.
3. **재ACK 허용** — "안전 확인"으로 남겼다가 실제 출동으로 바뀌는 현실 흐름을 반영.
4. **인가 = 현재 ACTIVE 연결** — 연결이 해제되면 그 피보호자의 **과거 이력도 조회되지 않는다**(연결 종료 후 개인정보 잔존 방지). 연결 없는 대상 접근은 404 위장 대신 **403 + `[IDOR-ATTEMPT]` WARN**(2026-07-14 정책 준수).
5. **`wardId`는 선택 파라미터** — 지정하면 해당 피보호자만, 생략하면 ACTIVE 연결된 피보호자 전원의 이력을 병합(보호자 카메라 목록 `GET /api/guardian/camera`의 allowlist 방식과 동일). 응답에 `wardId`·`wardName`이 있어 두 화면 모두 대응된다.
6. **ACK 알림은 WebSocket만** — FCM·SMS·알림톡 없음. 이미 종료된 긴급 상황의 상태 갱신이라 푸시 가치보다 소음이 크다. 수신자는 **ACTIVE 보호자 전원 + 피보호자 본인**(피보호자에게는 "보호자가 확인했다"는 안심 신호, 처리 보호자 본인도 다중 기기 동기화용으로 포함).
7. ⚠️ **ACK는 알림 발송에 개입하지 않는다** — SOS 보호자 알림은 `NotificationType.WARD_SOS`(`FORCED_PUSH_WITH_SMS_FALLBACK`) 필수 알림으로 항상 발송되며, 이미 처리된 SOS라도 정책이 바뀌지 않는다(2026-07-23 규칙 유지).
8. **페이지 크기 상한 50** — 과대 요청으로 전체 이력을 한 번에 끌어가는 것을 막는다(음수 `page`는 0으로 보정 → `PageRequest` 예외로 500 나는 것 방지).

## 4. API 계약

### `GET /api/guardian/sos/history` (GUARDIAN)

| 파라미터 | 필수 | 기본 | 설명 |
|---|---|---|---|
| `wardId` | 아니오 | — | 특정 피보호자만. 생략 시 ACTIVE 연결 전원 |
| `page` | 아니오 | 0 | 0-based |
| `size` | 아니오 | 20 | 최대 50 |

응답 `data` = `PageResponse<SosHistoryItem>`

```json
{
  "content": [{
    "sosEventId": 42, "wardId": "A1B2C3", "wardName": "김영희",
    "triggeredAt": "2026-04-22T19:42:11+09:00",
    "ackStatus": "SAFE_CONFIRMED", "ackNote": "통화 연결 · 안전 확인",
    "acknowledgedByName": "남궁명진", "acknowledgedAt": "2026-04-22T19:50:03+09:00"
  }],
  "page": 0, "size": 20, "totalElements": 2, "totalPages": 1, "last": true
}
```

- `ackStatus`: `SAFE_CONFIRMED`(안전 확인) · `EMERGENCY_DISPATCHED`(응급 출동) · `null`(미처리)
- `totalElements`로 "최근 N건" 표기 가능. 연결된 피보호자가 없으면 빈 `content`(200).
- 401 인증 없음 / 403 보호자 아님 또는 연결되지 않은 `wardId` 지정.

### `PATCH /api/guardian/sos/{sosEventId}/ack` (GUARDIAN)

```json
{ "ackStatus": "SAFE_CONFIRMED", "ackNote": "통화 연결 · 안전 확인" }
```

- `ackStatus` 필수, `ackNote` 선택(200자 이내, 공백만 입력 시 `null` 저장)
- 200 → `data` = 갱신된 `SosHistoryItem`. 400 검증 실패 / 403 연결 없음 / 404 없는 이력
- 커밋 후 WebSocket `/topic/{userId}/sos-acknowledged`
  → `{sosEventId, wardId, ackStatus, acknowledgedBy, acknowledgedByName}` (모두 문자열)

## 5. 변경 파일

**신규 (10)**

| 파일 | 역할 |
|---|---|
| `db/migration/V33__add_sos_event_ack.sql` | `ack_status`/`ack_by`(FK users ON DELETE SET NULL)/`ack_at`/`ack_note` 추가 |
| `sos/entity/SosAckStatus.java` | 처리 결과 enum 2값 |
| `sos/dto/SosHistoryItem.java`, `sos/dto/SosAckRequest.java` | 응답·요청 |
| `sos/service/GuardianSosService.java` | 조회·ACK + 인가 판정 |
| `sos/controller/GuardianSosController.java` | 엔드포인트 2개(`hasRole('GUARDIAN')`) |
| `sos/event/SosAcknowledgedEvent.java`, `sos/listener/SosAckNotificationListener.java` | AFTER_COMMIT + `@Async` WS 발송 |
| `GuardianSosServiceTest`, `GuardianSosControllerSecurityTest`, `SosAckNotificationListenerTest` | 테스트 19건 |

**수정 (4)**

- `sos/entity/SosEvent.java` — ACK 필드 4개 + `acknowledge()`·`isAcknowledged()`
- `sos/repository/SosEventRepository.java` — `findByWardIdInOrderByCreatedAtDesc(Collection, Pageable)`
- `connection/service/ConnectionService.java` — `getActiveWardIds()`, `isActiveConnection()` (연결 판정을 connection 도메인에 유지)
- `global/exception/ErrorCode.java` — `SOS_EVENT_NOT_FOUND`(404), `SOS_NOT_AUTHORIZED`(403)

`SecurityConfig` 변경 없음 — `anyRequest().authenticated()` + 클래스 레벨 `@PreAuthorize`로 커버.

### 설계 주의점

- **`getMyWards()`를 인가 목록으로 쓰지 말 것** — ACTIVE+PENDING이 섞여 있어 수락 전 피보호자의 이력이 노출된다. 그래서 `getActiveWardIds()`를 새로 뒀다.
- 리포지토리 메서드 이름에 정렬이 고정돼 있으므로 **`Pageable`에 `Sort`를 넣으면 이중 적용**된다(테스트로 고정).
- 이름 조회는 `findAllById` 배치 1회(N+1 회피). 탈퇴 사용자는 맵에 없어 `null`로 표시된다(관리자 문의 목록과 동일 처리).
- `wardId`가 `null`인 익명 이력(피보호자 탈퇴, `ON DELETE SET NULL`)은 처리 주체가 없어 **403**으로 막는다.

## 6. 테스트 결과

`./gradlew build` **전체 통과 — 327건 / 실패·오류 0 / 스킵 1** (신규 19건)

- `GuardianSosServiceTest` 12건 — 전체 병합 조회·이름 매핑 / 특정 피보호자 조회 / **연결 없는 wardId 403 + 쿼리 미실행** / 연결 0건 빈 페이지 / size·page 보정 / ACK 기록·이벤트 발행 / 재ACK 덮어쓰기 / 404 / **연결 없는 이력 ACK 403(기록·이벤트 없음)** / 탈퇴 피보호자 이력 403 / 공백 메모 정규화 / 이름 폴백
- `GuardianSosControllerSecurityTest` 4건 — GUARDIAN 허용(조회·ACK), WARD·ADMIN 403
- `SosAckNotificationListenerTest` 3건 — 수신자(보호자 전원 + 본인, 그 외 발송 없음) / 페이로드 / 보호자 0명

## 7. 검증 가이드 (로컬·dev)

Docker가 없는 환경에서 작성돼 **Flyway 실적용 확인은 미수행**이다. 배포 전 아래를 확인한다.

1. `docker compose -f docker-compose.dev.yml up -d` → 기동 로그에 `Migrating schema "public" to version "33 - add sos event ack"`.
   `ddl-auto=validate`이므로 컬럼 타입 불일치가 있으면 **기동 실패**로 즉시 드러난다.
2. 피보호자로 `POST /api/ward/sos` 2회 → 연결된 보호자로 `GET /api/guardian/sos/history` → 2건 최신순, `ackStatus: null`.
3. `PATCH /api/guardian/sos/{id}/ack` `{"ackStatus":"SAFE_CONFIRMED","ackNote":"통화 연결 · 안전 확인"}` → 200,
   재조회 시 배지·메모·처리자 반영. 같은 건에 `EMERGENCY_DISPATCHED`로 재요청 → 덮어써짐.
4. **인가 확인**: 연결 없는 다른 피보호자 ID로 `?wardId=` 요청 → 403 + 서버 로그 `[IDOR-ATTEMPT]`.
   피보호자 토큰으로 두 엔드포인트 호출 → 403.
5. WebSocket: 보호자·피보호자 계정으로 `/topic/{userId}/sos-acknowledged` 구독 후 ACK → 양쪽 수신.

## 8. 후속 과제

- **위치(📍) 표시** — SOS 발생 시 위치를 어디서 얻을지(FE 지오코딩 vs 등록 주소 vs 카메라) 결정 후 `POST /api/ward/sos` 계약 확장.
- **피보호자 본인 이력 조회** — 본인 화면에 "내 SOS 기록"이 필요해지면 `/api/ward/sos/history`.
- **Solapi 음성 통화 채널**(SOS 전용) — 메모리에 기록된 다음 작업.
- 이력이 커지면 조회 기간 필터(`from`/`to`) 추가 검토.
