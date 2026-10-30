# SOS 보호자 연락 - 전화받은 보호자 기록·이름 칩 (2026-10-30)

## 1. 배경

보호자 SOS 이력 시안이 "보호자에게 직접 전화"(GUARDIAN_CALL) 줄 오른쪽에 전화받은 보호자 이름 칩("박지영")을 보여 준다.
`sos_event`에는 피보호자·위치·경로·시각만 있어 누구에게 전화했는지 알 수 없었다. 직전 작업: #401 연타 묶음(`group=true`).

## 2. 결정 (사용자 승인 2026-10-30)

| | 결정 |
|---|---|
| Q1 | `POST /api/ward/sos` 본문에 선택 필드 `calledGuardianId` |
| Q2 | **SOS는 거절하지 않는다.** GUARDIAN_CALL이 아님 / 형식(영숫자 1~6자) 오류 / 그 피보호자의 ACTIVE 보호자 아님 / 확인 실패 → 값만 NULL로 저장, `[SOS-CALLED-GUARDIAN-IGNORED] wardId=…, reason=NOT_GUARDIAN_CALL\|FORMAT\|NOT_CONNECTED\|CHECK_FAILED` WARN(입력 원문 미기록) |
| Q3 | V64: `sos_event.called_guardian_id VARCHAR(6) NULL`, FK `users` `ON DELETE SET NULL`, 부분 인덱스(값 있는 행). 기존 행 NULL(백필 없음). **가역**(DROP COLUMN) |
| Q4 | 이력 응답 `calledGuardianName` - 그 보호자가 **지금** 그 피보호자와 ACTIVE 연결일 때만, 아니면 생략(JSON에 없음). 묶지 않는·묶음 모드 모두. 대시보드 `latest`는 그대로. ~~`calledGuardianId`~~는 점검 M-1(2026-10-30)로 응답에서 뺐다 - 보호자끼리 서로의 사용자 ID를 볼 이유가 없다 |
| Q5 | 묶음(`group=true`) 기준에 전화받은 보호자 포함 - 1분 안이어도 다른 보호자에게 건 전화는 다른 줄. SOS 버튼은 그대로. 기록 없는 보호자 연락끼리는 같은 값으로 묶음. 칩은 묶음의 최신 건 기준 |
| Q6 | 알림 무변경 - `SosTriggeredEvent`에 싣지 않아 리스너가 읽을 수 없다 |

## 3. 구현에서 정한 것 - 확인은 SOS 저장 트랜잭션 밖에서

`ConnectionService.isActiveConnection`은 자체 `@Transactional` 메서드다. SOS 저장 트랜잭션 안에서 부르다 예외가 나면 잡더라도 바깥 트랜잭션이 rollback-only로 표시돼 **커밋 시 SOS 이력까지 잃는다.** 그래서 확인을 `SosCalledGuardianResolver`(트랜잭션 없음)로 분리해 컨트롤러가 `SosService.trigger` 전에 부르고, 확인된 값만 `trigger(…, verifiedCalledGuardianId)`로 넘긴다. 확인기에 `@Transactional`을 걸지 말 것(`SosCalledGuardianResolverTest`가 고정).

## 4. API

요청 (피보호자, 보호자 카드를 눌러 전화할 때):
```json
POST /api/ward/sos
{ "triggerType": "GUARDIAN_CALL", "calledGuardianId": "G1B2C3" }
```
`calledGuardianId` = 피보호자 보호자 목록(`GET /api/ward/connection/active`)의 `partnerUserId`.

응답 (보호자 이력 `GET /api/guardian/sos/history[?group=true]`) 항목에 추가:
```json
{ "triggerType": "GUARDIAN_CALL", "calledGuardianName": "박지영", ... }
```
SOS 버튼·옛 이력·해제/탈퇴한 보호자면 칸이 없다. 전화받은 보호자의 사용자 ID는 응답에 싣지 않는다(점검 M-1).

## 5. 바뀌지 않는 것

SOS 알림(ACTIVE 보호자 전원 - 전화받은 보호자도 포함)·쿨다운·`repeatCount`·문자 폴백, `SosAction`, SOS 버튼 이력·묶음, 보호자 대시보드, 인가 메서드(재사용만), `calledGuardianId` 없는 요청·응답.

## 6. 변경 파일

- `V64__add_sos_event_called_guardian.sql`
- `sos/entity/SosEvent` (`calledGuardianId`), `sos/dto/SosTriggerRequest` (필드), `sos/controller/WardSosController` (확인 → 저장, Swagger)
- `sos/service/SosCalledGuardianResolver` (신규), `sos/service/SosService` (4인자 `trigger`, 3인자는 위임)
- `sos/repository/SosEventRepository` (묶음 파티션에 `COALESCE(called_guardian_id, '')`)
- `sos/dto/SosHistoryItem` (두 칸, `withCalledGuardian`), `sos/service/GuardianSosService` (지금 연결된 보호자만 이름, 피보호자별 연결 목록 1회·이름 1회 조회)
- 테스트: `SosCalledGuardianResolverTest`(신규), `WardSosControllerHttpTest`(신규), `SosServiceTest`·`GuardianSosServiceTest`·`GuardianSosControllerHttpTest`·`WardSosControllerSecurityTest`·`SosTriggerRequestValidationTest` 갱신, `SosCalledGuardianIntegrationTest`(신규)

## 7. 검증 가이드

- vkcs: `~/SilverBridgeBe/tools/integration-test.sh feature/sos-called-guardian` - V64 적용, 보호자 삭제 시 SET NULL(이력 보존), 다른 보호자 따로 묶음, #401 묶음 테스트 회귀
- 실서버: 피보호자 토큰으로 `{"triggerType":"GUARDIAN_CALL","calledGuardianId":"<연결된 보호자>"}` → 보호자 이력에 이름 칩. 연결 안 된 ID·긴 값도 201(칩 없음)

## 8. 테스트 결과

- 로컬 `./gradlew build`(단위 테스트 포함): **BUILD SUCCESSFUL** - 2113건, 실패 0·오류 0·건너뜀 1(기존).
  - 신규: `SosCalledGuardianResolverTest`(연결/미연결/확인 실패/경로 아님/형식 오류 7종/트랜잭션 없음), `WardSosControllerHttpTest`(확인 → 저장 순서·1000자 값도 201·바디 없음), `SosServiceTest` +2(저장되지만 이벤트에 없음), `GuardianSosServiceTest` +3(지금 연결된 보호자만 이름·연결 목록 1회·묶음 최신 건).
- vkcs `tools/integration-test.sh feature/sos-called-guardian` @ `0989d9a8`: **통과** - 전체 113건 실패 0, `SosCalledGuardianIntegrationTest` 3건(저장·보호자 삭제 시 SET NULL·보호자별 묶음·미기록 연락/SOS 버튼 묶음) + `SosHistoryPressGroupIntegrationTest` 8건 회귀 통과.
