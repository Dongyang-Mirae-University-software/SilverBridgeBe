# 챗봇 109 - SOS 발생 경로 `CHATBOT_109` (2026-11-20)

## 배경
- 챗봇 피보호자 화면의 긴급 카드를 동그란 119 버튼으로 바꾸면서, 자살·자해 위기 답(AI `layout.crisis`)에는
  자살예방 상담전화 109 버튼을 119와 함께 두기로 했다(사용자 시안 선택: C 동그란 버튼 + F 109·119 함께).
- 109 버튼도 SOS 119와 같이 **앱 안 키패드만 열고 실제 전화는 걸지 않는다**. 키패드 통화 버튼 = 보호자 알림.
- 2026-11-17에는 위기 종류를 보호자에게 드러내지 않으려고 `CHATBOT_119` 하나로 묶었다. 사용자가 이 노출을
  확인한 뒤 109를 **별도 경로로 나누기로 결정**했다(보호자 이력에 "챗봇 109"로 표시).

## 변경 (BE)
| 파일 | 내용 |
|---|---|
| `SosTriggerType` | `CHATBOT_109` 추가, `CHATBOT_119` 주석 갱신 |
| `SosHistoryPage.Counts` | `chatbot109` 칸 추가(`all`에 합산) |
| `GuardianSosService.toCounts` | default 없는 switch에 `CHATBOT_109` |
| `SosTriggerRequest`·`SosHistoryItem`·`WardSosController`·`GuardianSosController`·`SwaggerConfig` | 허용 값·설명 |
| `policy-sos.md` | 결정 기록 |

## 바뀌지 않는 것
- 알림: `WARD_SOS` 강제 푸시·WS·문구 그대로(경로로 알림을 가르지 않음 - 규칙 ②).
- DB: `trigger_type`은 VARCHAR(20)·CHECK 없음 → 마이그레이션 없음. 묶음·필터 쿼리는 이름 문자열 그대로.
- 기존 값(`SOS_BUTTON`·`GUARDIAN_CALL`·`CHATBOT_119`)의 저장·조회.

## 응답 변화 (FE 영향)
- `GET /api/guardian/sos/history`의 `counts`에 `chatbot109`가 더해진다(추가만, 기존 칸 유지).
- `content[].triggerType`에 `CHATBOT_109`가 올 수 있다 → FE 보호자 이력 표시 맵(`TRIGGER_TYPE_META`)이 먼저 알아야 한다.

## 배포 순서
BE → FE. FE가 먼저 나가면 109 통화가 400(알 수 없는 enum)으로 실패한다.

## 테스트
- `WardSosControllerHttpTest.챗봇109` - 요청 수용
- `GuardianSosServiceTest.getHistory_챗봇109_필터와건수`·`getGroupedHistory_챗봇109_전체묶음수` - 필터·건수 합산
