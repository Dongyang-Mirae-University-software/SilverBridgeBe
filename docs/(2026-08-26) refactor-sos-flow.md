# SOS 흐름 정비 - 처리 결과(ACK) 제거 · 119 정책 재정의 · 보호자 직접 전화 이력화

- 작성일: 2026-08-26
- 브랜치: `refactor/sos-remove-ack`
- 마이그레이션: `V39__drop_sos_event_ack_add_trigger_type.sql` (⚠️ 비가역)

---

## 1. 배경

프론트에 SOS 화면이 아직 붙지 않은 상태에서 SOS 비즈니스 로직을 확정했다. 확정된 흐름은 다음과 같다.

1. 피보호자가 SOS를 누르면 **119가 입력된 키패드 화면**이 뜬다. 실제 전화는 걸지 않는다(학생 프로젝트).
2. SOS를 누르면 보호자에게 **자동으로 알림**이 간다. FCM이 안 되면 SMS로 간다.
3. 피보호자가 SOS 화면에서 **보호자를 고르면 그 보호자에게 바로 전화**가 걸린다.
4. SOS 이력은 전부 기록되고 **보호자가 볼 수 있어야** 한다.
5. **처리 결과(ACK)는 필요 없다** - 언제 발생했는지만 남긴다.

착수 전 코드를 확인한 결과 2·4·6은 이미 구현돼 있었고(백엔드 변경 불필요), 실제로 손볼 것은
① ACK 제거 ② 119 문구 재정의 ③ 발생 경로 기록 세 가지였다.

## 2. 착수 전 확인한 사실 (PHASE 0)

| # | 확인 내용 | 결과 |
|---|---|---|
| D-1 | "119에 전화 걸지 않기"를 위한 백엔드 작업 | **불필요.** 백엔드는 원래 119에 관여하지 않는다. 다만 `tel:119`는 다이얼러를 열어 사용자가 통화 버튼을 누르면 실제로 걸리므로, 발신을 원천 차단하려면 프론트가 **자체 키패드 UI**를 그려야 한다 |
| D-2 | 보호자 직접 전화 | 전화 연결 자체는 **이미 동작 중**(FE `GuardianCard`가 카드 전체를 `tel:` 링크로 덮음, 백엔드도 `partnerPhone` 제공). 이력화만 필요 |
| D-3 | ACK 데이터 존재 여부 | **배포 서버 두 곳 모두 0건** (vkcs-linux `sos_event` 0행 / gosky 1행이나 `ack_status` 0건). Flyway는 양쪽 V38 |
| D-4 | STOMP 인터셉터의 `sos-acknowledged` 화이트리스트 | **개별 등록 없음**(범용 `{userId}` 검증뿐) → 인터셉터 변경 불요 |
| D-5 | `SosAction` 동작 분기 | 저장·조회 전용, **분기 0건** → 문구 수정만으로 충분 |
| D-6 | V33이 추가한 것 | 컬럼 4개 + FK `fk_sos_event_ack_by` → V39에서 함께 정리 |
| D-7 | `ErrorCode.SOS_EVENT_NOT_FOUND` | `acknowledge()` 전용 → 제거 대상 |

## 3. 변경 내용

### 3-1. 처리 결과(ACK) 제거

**왜 제거했나.** 보호자 앱에 처리 결과 입력 화면이 붙은 적이 없어 `ack_status`가 전건 NULL이다.
이 상태로 관리자 화면에 "미처리 SOS" 지표를 만들면 발생한 SOS 100%를 "보호자 전원 무응답"으로
표시하는 거짓 경보가 된다. 화면 연동 없이 컬럼만 남겨두는 대신 기능을 접었다.

삭제한 것:

- `PATCH /api/guardian/sos/{sosEventId}/ack`
- WebSocket 토픽 `sos-acknowledged`
- `sos_event.ack_status` · `ack_by` · `ack_at` · `ack_note` + FK `fk_sos_event_ack_by`
- `SosAckStatus` · `SosAckRequest` · `SosAcknowledgedEvent` · `SosAckNotificationListener`
- `ErrorCode.SOS_EVENT_NOT_FOUND`

유지한 것:

- `GET /api/guardian/sos/history` (경로·페이징·인가 그대로, **응답 필드만 축소**)
- 열람 인가 규칙(요청 시점 ACTIVE 연결만, 위반 시 403 + `[IDOR-ATTEMPT]` WARN)

### 3-2. 발생 경로(`trigger_type`) 추가

피보호자 SOS 화면에는 두 경로가 있다. 둘 다 이력을 남기고 보호자 알림도 나가지만, 보호자 이력
화면에서 구분해 보여줘야 하므로 경로를 이력에 기록한다.

| 값 | 의미 |
|---|---|
| `SOS_BUTTON` | 긴급 SOS 버튼을 눌렀다 (기본값) |
| `GUARDIAN_CALL` | SOS 화면에서 보호자를 골라 전화를 걸었다 |

- `POST /api/ward/sos` 바디에 `triggerType`(선택) 추가. 생략하면 `SOS_BUTTON` - **기존 호출 방식 하위호환**.
- 보호자 카드를 눌러 전화를 거는 시점에 프론트가 이 API를 함께 호출해야 그 전화가 이력에 남는다.
  호출하지 않으면 백엔드가 감지할 방법이 없다.
- ⚠️ **표시 전용이다.** 두 경로 모두 ACTIVE 보호자 전원에게 동일하게 알림이 나간다 - 전화받은
  보호자 외 나머지도 상황을 알아야 하기 때문이다. 알림 분기 용도로 쓰지 말 것.

### 3-3. 119 정책 재정의 (문구만, 값·스키마 불변)

`SosAction`의 세 값은 그대로 두고 의미만 다시 적었다. "119 연결"이 아니라 **"119 안내 화면 표시"**다.

| 값 | 이전 문구 | 새 문구 |
|---|---|---|
| `CALL_119` | 119 즉시 연결 | 119 화면 바로 표시 |
| `CALL_119_AND_NOTIFY` | 119 연결 + 보호자 알림 안내 | 119 화면 + 보호자 알림 안내 (기본값) |
| `NOTIFY_GUARDIAN_FIRST` | 보호자 먼저 알린 뒤 119 연결 안내 | 보호자 먼저 알린 뒤 119 화면으로 안내 |

값 이름의 `CALL_119`는 이제 "전화를 건다"가 아니라 "119 화면을 띄운다"는 뜻이다. 이름과 의미가
어긋나 있으니 이름만 보고 발신 로직을 넣지 말 것(enum javadoc에 명시).

### 3-4. 부수적으로 고친 버그

`GuardianSosService.resolveWardNames()`가 빈 결과로 `Map.of()`를 반환하는데, 탈퇴 피보호자의 익명
이력(`wardId == null`)만 조회되면 호출부의 `names.get(null)`이 **NPE(500)**를 던졌다.
`Collections.emptyMap()`으로 교체했다. 리팩터 이전부터 있던 문제이며, 이번에 추가한 테스트가 잡았다.

## 4. 변경 파일

**삭제 (5)**

```
domain/sos/dto/SosAckRequest.java
domain/sos/entity/SosAckStatus.java
domain/sos/event/SosAcknowledgedEvent.java
domain/sos/listener/SosAckNotificationListener.java
test/.../sos/listener/SosAckNotificationListenerTest.java
```

**신규 (2)**

```
db/migration/V39__drop_sos_event_ack_add_trigger_type.sql
domain/sos/entity/SosTriggerType.java
```

**수정 (11)**

| 파일 | 내용 |
|---|---|
| `SosEvent` | ack 필드·메서드 제거, `triggerType` 추가(null이면 `SOS_BUTTON`) |
| `SosHistoryItem` | ack 4필드 제거, `triggerType` 추가, `of(event, wardName)`로 시그니처 축소 |
| `SosTriggerRequest` | `triggerType` 선택 필드 추가 |
| `SosService` | `trigger(wardId, location, triggerType)`, 로그에 경로 포함 |
| `GuardianSosService` | `acknowledge()` 제거, 이름 조회를 wardId만으로 단순화, NPE 수정 |
| `GuardianSosController` | `PATCH .../ack` 제거, Swagger 갱신 |
| `WardSosController` | `triggerType` 전달, Swagger에 경로·"실제 발신 안 함" 명시 |
| `SosAction` | javadoc 재정의(119 화면 표시, 발신 없음) |
| `WardSosSettingController` | Swagger description 재정의 |
| `SosSettingService` | javadoc 문구 정정 |
| `ErrorCode` | `SOS_EVENT_NOT_FOUND` 제거 |

**테스트 (3 수정, 1 삭제)**

- `GuardianSosServiceTest` - ACK 테스트 7건 제거, 익명 이력 조회 테스트 추가(위 NPE를 잡은 테스트)
- `SosServiceTest` - 발생 경로 기본값·`GUARDIAN_CALL` 테스트 2건 추가
- `GuardianSosControllerSecurityTest` - ACK 권한 테스트 제거
- `WardSosControllerSecurityTest` - 스텁 시그니처 수정

## 5. 검증

```
./gradlew build  →  BUILD SUCCESSFUL
총 399건 / 실패 0건 (SOS 도메인 34건 포함)
```

**마이그레이션 사전 검증** - dev DB(gosky)에서 트랜잭션으로 실행 후 롤백:

```
ALTER TABLE   (ack 컬럼 4개 + FK DROP)
ALTER TABLE   (trigger_type 추가)
 trigger_type | count
 SOS_BUTTON   |     1     ← 기존 1행이 정상 백필
```

롤백 후 `\d sos_event`로 스키마가 원상 그대로임을 확인했다(실제 적용은 배포 시).

## 6. 파괴적 변경 (프론트 전달 필요)

| 대상 | 변경 |
|---|---|
| `GET /api/guardian/sos/history` | 응답에서 `ackStatus`·`ackNote`·`acknowledgedByName`·`acknowledgedAt` **제거**, `triggerType` **추가** |
| `PATCH /api/guardian/sos/{id}/ack` | **삭제** (404) |
| WebSocket `sos-acknowledged` | **삭제** (구독해도 이벤트가 오지 않음) |
| `POST /api/ward/sos` | 변경 없음(바디에 `triggerType` 선택 필드가 늘어난 것뿐) |

노션 3개 페이지에 반영했다.

- SOS 이력 화면 (보호자용) - 조회 전용으로 전면 개편
- 환경설정 - SOS 동작 설정 - 119 발신 정책 정정
- SOS(긴급 도움 요청) - FCM 푸시 & 전화(tel) 연동 안내 - 119 정책 + 보호자 직접 전화 이력화 안내

## 7. 남은 작업 (프론트)

- 피보호자 SOS 화면: 119 키패드 UI(발신 없음), `sosAction` 값에 따른 분기
- 피보호자 SOS 화면: 보호자 카드 전화 시 `POST /api/ward/sos` (`triggerType: GUARDIAN_CALL`) 동반 호출
- 피보호자 SOS 화면: `location` 전송(현재 미전송이라 이력의 위치가 항상 null)
- 보호자 SOS 이력 화면 신규(백엔드 API는 준비 완료)
- 보호자 푸시 라우팅: `PushNotificationListener`가 현재 `CONNECTION_*`만 분기 - `WARD_SOS` 추가 필요
- 보호자 WebSocket `sos-triggered` 구독(현재 미구독)
