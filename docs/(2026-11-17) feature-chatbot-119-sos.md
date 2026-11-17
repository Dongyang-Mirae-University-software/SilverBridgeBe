# 챗봇 119 버튼 → SOS 발생 경로 `CHATBOT_119` (2026-11-17)

## 배경
챗봇이 응급·마음 위기 답을 하면 FE가 119 버튼을 띄운다. 이 버튼을 SOS와 같은 119 키패드 화면으로 바꾸고,
누르면 보호자에게 SOS 알림이 가게 한다. BE는 `POST /api/ward/sos`의 발생 경로에 "챗봇 119"를 받는 것만 맡는다.

## 변경
| 파일 | 내용 |
|---|---|
| `SosTriggerType` | `CHATBOT_119` 추가 - 응급·마음 위기 구분 없이 이 값 하나(보호자 이력·알림에 위기 종류를 드러내지 않음) |
| `SosHistoryPage.Counts` | `chatbot119` 필드 추가, `all = sosButton + guardianCall + chatbot119` |
| `GuardianSosService` | 경로별 건수를 `EnumMap`으로 모은 뒤 default 없는 switch로 옮김 - 경로를 또 추가하면 컴파일이 실패해 `counts.all`·묶음 `totalElements`에서 조용히 빠지지 않는다 |
| Swagger | `WardSosController`·`GuardianSosController`·`SosTriggerRequest`·`SosHistoryItem`·`SwaggerConfig` 설명·`allowableValues` |

**마이그레이션 없음** - `sos_event.trigger_type`은 V39 `VARCHAR(20)`이고 CHECK가 없다(V39 이후 이 컬럼을 건드린 마이그레이션 없음). `CHATBOT_119`는 11자.

## 고치지 않았다면 생겼을 문제
`Counts`가 두 경로 합으로 `all`을 계산했고, 묶음 모드(`group=true`)는 필터 없을 때 `totalElements = counts.all()`이라
챗봇 119 건이 전체 탭 숫자·묶음 총계·페이지 수에서 빠졌을 것이다(묶음 목록 자체에는 보이는데 수가 맞지 않음).

## 바뀌지 않는 것
- 알림: `WARD_SOS` 그대로(강제 푸시·문자 폴백·쿨다운 10초·이력 항상 저장). `SosTriggeredEvent`에 경로가 없어 리스너가 경로로 가를 수 없다(policy-sos 규칙 ②).
- 보호자 열람 인가(ACTIVE 연결·D-1), 문자 시간당 30건 상한.
- `calledGuardianId`: `GUARDIAN_CALL`일 때만 저장. `CHATBOT_119`와 함께 오면 연결 확인 없이 버리고 `[SOS-CALLED-GUARDIAN-IGNORED] reason=NOT_GUARDIAN_CALL` WARN, SOS는 201.
- 모르는 `triggerType` 값은 기존처럼 400 `INVALID_INPUT`.

## FE 계약
- 요청: 챗봇 119 버튼으로 연 119 키패드에서 **통화 버튼을 누를 때** `POST /api/ward/sos`(키패드를 여는 것만으로는 보내지 않는다 - 잘못 눌러도 알림이 가지 않게, FE #277에서 확정) 본문 `{"triggerType":"CHATBOT_119"}` (선택 `location` 그대로 가능, `calledGuardianId`는 보내지 않음).
- 응답(보호자 이력): `content[].triggerType`에 `CHATBOT_119`가 올 수 있다. `counts.chatbot119` 추가(`all`에 포함). 필터 `?triggerType=CHATBOT_119`, 묶음 모드도 같은 값으로 나뉜다.
- 보호자 대시보드 최근 SOS(`latest.triggerType`)에도 같은 값이 올 수 있다 - 표시 문구는 FE 몫(예: "챗봇 119").

## 테스트
- `WardSosControllerHttpTest`: 챗봇 119 → 201·경로 전달 / `calledGuardianId` 동반 시 연결 확인 없이 버림 / 모르는 값 400 `INVALID_INPUT`·저장 없음
- `SosServiceTest`: 챗봇 119 저장 + SOS 버튼과 같은 `SosTriggeredEvent` 발행
- `GuardianSosServiceTest`: 필터·counts / 묶음 모드 counts·`totalElements` / 묶음 필터 이름
- `SosHistoryTriggerTypeIntegrationTest`(실 DB): 저장·건수·필터·묶음 파티션

## FE 반영 (2026-11-17, FE #277 `a438a9f`, gosky dev 반영)
- 피보호자 챗봇의 `tel:119`를 없애고 앱 안 키패드(`src/components/emergency/Emergency119Dialpad`)를 연다. 통화 버튼을 누르면 `CHATBOT_119`를 보낸다(전송 중 잠금, 성공 뒤 같은 키패드에서 재전송 없음, 실패하면 다시 누를 수 있음).
- 자해·위기 답(AI `layout.crisis === true`, AI #317·BE #500·FE #279 - 처음의 '경고 없음 = 위기' 판정은 자해 고정 답에도 경고가 있어 틀렸다, 점검 H-1)은 키패드 아래에 109 안내 한 줄(글자만). 연결된 보호자가 없으면 "알림은 가지 않아요" 문구(M-1). 보호자 챗봇에는 버튼이 없다.
- 영향 점검 H-1·M-1: 보호자 이력에 "챗봇 119" 필터·항목 배지·`counts.chatbot119` 반영. 모르는 경로 값 대체 표시는 FE 후속 PR.
