# 보호자 챗봇 - 예약 대상 선택 지원 (`guardianWards`·`reservation_patient`) (2026-11-11)

> 브랜치 `feature/guardian-chat-patient-choice` · 마이그레이션 없음 · BE·AI·FE 공통 계약(바꾸려면 세 세션 모두에 알릴 것)

## 배경 (사용자 결정 11/11)

보호자 챗봇 위쪽의 "상담할 피보호자" 선택 칸을 없애고, 평소에는 보호자 본인 상담으로 시작한다.
예약을 시작할 때만 AI가 "누구 예약인가요? [나] [어머니]"를 묻는다. AI는 보호자의 피보호자 목록을 모르므로 BE가 넘긴다.

## 계약

| # | 주체 | 내용 |
|---|---|---|
| ① | BE → AI | 보호자 경로 요청 `context.guardianWards = [{wardId, name, relation}]` - ACTIVE 연결만, 이름·관계만(전화·주소·생년월일 제외). `context.wardId`는 지금처럼 ACTIVE 연결 검증 뒤에만 통과(아니면 403) |
| ② | AI | 보호자 경로에서 예약이 시작될 때 `context.wardId`가 없으면 먼저 선택지를 준다. **`field = "reservation_patient"`**, 값 = 각 wardId, 표시 "이름(관계)". **정정(사용자 결정, AI #255)**: 챗봇 예약은 피보호자만 대상이라 처음 계약의 `"__self__"`(나)·"목록이 비면 본인 예약"은 쓰지 않는다 - 목록이 비면 AI가 안내 문장. BE는 옛 값 `__self__`를 검증 없이 통과(해 없음) |
| ③ | FE | 선택지를 누르면 `uiSelection`을 보내고, 값이 wardId면 **그 턴부터** 예약이 끝날 때까지 `context.wardId`에 같은 값을 싣는다(`__self__`면 싣지 않음) |
| ④ | AI | wardId가 실린 턴부터 그 피보호자를 환자로 예약 초안을 묶는다(토큰·세션·wardId가 같을 때만 실행 규칙 유지) |
| 추가 | AI | **상담 기록(`userContext`)에 `guardianWards`를 저장하지 않는다**(사용자 결정 B, 아래 "기록") |

## BE 변경

| 파일 | 내용 |
|---|---|
| `connection/dto/ActiveWardSummary` (신규) | `(wardId, name, relation)` record - 전화·주소·생년월일 금지 |
| `ConnectionService.getActiveWardSummaries(guardianId, limit)` | ACTIVE 연결만(최신 연결순, 같은 피보호자 중복 제거, 상한), 이름은 `users` 한 번 조회(`findAllById`), 회원 행 없는 연결 제외, 빈 관계는 `null` |
| `ChatRelayService` | ① 클라이언트 `guardianWards`는 모든 경로에서 삭제, 보호자 경로는 DB 목록(최대 20명, 없으면 `[]`)으로 채움 ② `uiSelection.field == "reservation_patient"`이고 값이 `__self__`가 아니면: 형식 → `isActiveConnection` 검증(AI 호출 전) ③ 같은 턴 `context.wardId`와 다르면 400 ④ 고른 턴도 AI 응답 뒤 연결 재확인(끊겼으면 응답 폐기) ⑤ 피보호자 경로에서 누군가를 고르면 403 |
| `GuardianChatController` | Swagger 설명에 위 계약·오류 코드 |

### 처리 순서 (보호자 경로)

킬 스위치 → 본문 검증 → 속도 제한 → `context.wardId` 형식·ACTIVE → **예약 대상 선택 형식·ACTIVE·일치** → 본인 행(본인 상담) → `guardianWards` 조회 → context 8KB 검사 → AI 호출 → 대리 상담이면 연결 재확인

### 오류

| 경우 | 응답 | 로그 |
|---|---|---|
| 연결되지 않은 wardId 선택 | 403 `CHAT_WARD_NOT_CONNECTED`, AI 미호출 | `[IDOR-ATTEMPT]` WARN, guardianId·wardId |
| 형식이 틀린 선택 값 | 403 `CHAT_WARD_NOT_CONNECTED`, DB 조회 없음 | `[IDOR-ATTEMPT]` WARN, **길이만** |
| 같은 턴 `context.wardId`와 선택 값이 다름 | 400 `CHAT_INVALID_REQUEST` | 없음 |
| 피보호자 경로에서 누군가 선택 | 403 `CHAT_WARD_NOT_CONNECTED` | `[IDOR-ATTEMPT]` WARN, 길이만 |

## 판단 근거

- **칸 이름으로만 알아본다**: 선택 값이 wardId 형식(영숫자 6자 이내)인지만 보면 시각 `0930`·병원 ID 같은 다른 선택 값이 걸린다. `reservation_patient` 칸만 검증한다.
- **선택 턴도 검증한다**: 계약 ③상 FE가 선택 턴부터 `context.wardId`를 싣지만, AI가 선택 값을 그대로 환자로 묶을 수 있어 BE가 `uiSelection` 자체도 검증한다(심층 방어).
- **크기**: 이름 20자·관계 10자 최대에서 20명이 약 2.7KB라 context 8KB 안에 들어간다(`상한_20명은_8KB_안`). 21명 이상 연결된 보호자는 최신 20명만 고를 수 있다(수용).
- **피보호자 상태**: `isActiveConnection`과 같은 기준이라 이용 제한·탈퇴 진행 피보호자도 연결이 ACTIVE면 목록에 들어간다(기존 대리 상담과 같음).

## 기록 (사용자 결정 B)

AI는 요청 context 사본(`userContext`)을 보호자 **본인 상담 기록**에도 저장한다. 피보호자 탈퇴(#129)·연결 해제(L-13) 정리는 대리 상담 기록만 지우므로, `guardianWards`가 저장되면 떠난 피보호자의 이름·관계가 보호자 기록에 남는다. AI가 기록 저장 때 이 키를 빼도록 AI 세션에 요청했다. BE는 기록 응답 허용 목록을 바꾸지 않아 FE로는 어떤 경우에도 나가지 않는다(`기록_응답에_없음`).

## 영향 범위 점검 후속 (2026-11-11, `audit-impact-guardian-chat-patient-choice.md`)

- L-1: 칸 이름에 `reservation_patient`가 들어 있기만 하면 검증한다(AI는 NBSP까지 지운 뒤 비교 - 같음 비교는 앞뒤 글자로 건너뛸 수 있었다). 테스트 5건(공백·탭·NBSP·전각 공백).
- L-2: 위 계약 ② 정정, Swagger·정책 문서 문구.
- M-1(AI): 대상 질문 답 기록의 선택지 표시 글자를 저장하지 않고 `reservationTargetAsk.wardIds`를 정리 대상 ID로 본다.

## FE 영향 (FE 세션 전달용)

- 피보호자 선택 칸을 없애면 `context.wardId`는 예약 대상 선택 뒤에만 싣는다. 끝나는 신호(완료·취소·새 대화)는 AI 응답 칸으로 정한다(AI·FE 세션).
- 기록 목록(`GET /api/guardian/chat/logs`)은 BE가 걸러 주지 않고 보호자 기록 전체를 준다. 기록마다 `wardId`(대리 상담이면 그 피보호자, 본인 상담 null)가 있으니, 선택 칸이 없어진 뒤에는 한데 보여 주거나 `wardId` 칩으로 나눈다.

## 테스트

- `ChatRelayGuardianWardsTest` 12건: DB 목록으로 덮어쓰기(3칸만, 위조 값 없음)·빈 배열·피보호자 경로 미탑재·남의 wardId 403(AI 미호출)·형식 오류 길이만 로그·선택 통과 뒤 연결 끊김 폐기·선택 그대로 전달·`context.wardId` 불일치 400·`__self__`/다른 칸 통과·피보호자 경로 403·기록 응답에 없음·20명 8KB 안
- `ConnectionActiveWardSummaryTest` 3건: ACTIVE만·순서·빈 관계 null / 없으면 회원 조회 안 함 / 회원 행 없는 연결 제외·상한
- `ChatRelayWardContextTest`: 본인 상담 2건의 "connectionService 호출 없음"을 "연결 검증 호출 없음"으로 좁힘(목록 조회가 새로 들어가서). 피보호자 경로는 그대로 호출 없음.
