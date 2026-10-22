# AI 챗봇 예약 재점검 수정분(#34) 기능 점검 (2026-10-22)

> 템플릿 B(기능 점검). **점검만 했고 코드는 수정하지 않았다.** 대상은 AI 서버 PR #34(`b752780`, gosky 반영본)이며 근거는 `(2026-10-22) audit-ai-reservation-confirm-flow.md` §0-1이다. #34는 같은 세션에서 작성한 코드라, 코드 검토는 작성자 판단에 기대지 않도록 **독립 에이전트(Sonnet)**에 맡기고 결과를 직접 다시 확인했다. 실서버 시험은 gosky에서 가상 사용자로 했다.

## 0. 종합 판정

**조건부 PASS** - 🔴 0 · 🟠 1 · 🟡 1 · 🟢 4

- **#34가 의도한 6가지는 모두 동작한다**(코드 + 실서버). 대리 상담에서 다른 이름·번호를 말해도 카드와 실제 예약의 환자는 피보호자였고, 고치기 도중 피보호자를 바꾸면 만료됐으며, 로그에 시험 문장·번호가 0건이었다.
- 🟠 1건은 #34와 **무관하게 전부터 있던** 문제다. 예약 API 키가 챗 기록(`chat_logs.context_json`)에 평문으로 저장된다(운영 328건, 2026-10-22 집계).
- 🟡 1건은 #34가 정리한 "로그에 원문을 남기지 않는다"는 기준에서 빠진 3곳이다.

## 1. 기준과 환경

| 항목 | 값 |
|---|---|
| 기준 커밋 | AI main `b752780` = gosky 반영본 |
| pytest | 442 passed (로컬 임시 가상환경) |
| 실서버 | gosky AI(`:6017`) 직접 호출, 가상 사용자 `qarc-g1`(보호자)·`qaw901`/`qaw902`(피보호자)·`qarc-s1`(본인). BE가 보내는 형태(wardId·wardName·wardPhone, name·phone을 피보호자 값으로)를 흉내 냈다. 실제 계정·예약은 쓰지 않았다 |
| 안 한 것 | BE 경유 호출(테스트 보호자 계정의 연결 피보호자가 실제 사용자라 그 예약 계정을 건드리게 됨), KST 0~9시 실측(점검 시각 13시라 UTC·KST 날짜가 같다 - 단위 테스트로 대체), 30분 만료 실측(시각 조작 불가 - 단위 테스트로 대체) |

## 2. 항목 × 결과

| # | 항목 | 코드(독립 검토) | 실서버 | 근거 |
|---|---|---|---|---|
| 1 | 상대 날짜 KST | PASS | 카드 "10월 23일(금)" = 내일 | `_find_date`(`reservation_intake.py:203`) 외 지난 날짜 검사·오늘 슬롯 필터·카드 표기·취소 목록·LLM 프롬프트 모두 KST. reservation 코드에 `date.today()` 없음 |
| 2 | 대리 환자 고정 | PASS | **PASS** - 채팅에 다른 이름·번호를 넣어도 카드 "가상환자가님", 연락처 끝 9901, 생성된 예약 `patient_name`=피보호자·끝 9901 | `_pin_ward_patient`가 `_advance` 시작과 `_submit` 직전에 있고, 확인 클릭·시간 재선택·변경·이어쓰기가 모두 이 둘을 지난다 |
| 3 | 고치기 wardId 결속 | PASS(옛 행 예외 §4 L-2) | 버튼: **PASS**(만료) / 문장: 환자는 B로 정상, 병원은 A 것이 따라옴(§4 L-1) | `reservation_orchestrator.py:777`, `reservation_confirm_flow.py:535` |
| 4 | 확인 카드 30분 만료 | PASS | 정상 카드 확인·취소 동작 | `pendingAction` 생성 3곳 모두 `createdAt`, 검증은 `_valid_pending` 한 곳. 시계가 뒤로 가면(음수) 만료 - 안전한 쪽 |
| 5 | 로그 원문 제거 | 부분 FAIL(§3 M-1) | **PASS** - 시험 시간대 로그 227줄에 시험 이름·번호·문장 0건, `[라우터]`는 `text_len`만, 트레이스는 `patient_name_len`·`phone_last4`만 | 바꾼 3곳(`chat_service.py:117`, `:166`, `:258`)은 정리됨 |
| 6 | `_followup_ui()` | PASS | 생성·취소 후 "예약 목록 보기" 선택지 정상 | 필드·라벨·옵션이 기존 두 곳과 글자 단위로 같음 |
| - | 본인 상담 | - | **PASS** - 입력한 이름이 그대로 카드에, 대리 문구 없음 | wardId 없으면 고정이 즉시 return |

## 3. 이슈 (🟠·🟡)

### 🟠 H-1 예약 API 키가 챗 기록에 평문으로 저장되고 AI 기록 조회 API로 나간다 (#34 이전부터)

- **위치**: `chat_service.py:206`이 오케스트레이터에 넘기는 `meta`에 `reservationApiKey`(예약 서비스 사용자 API 키)와 `storedFlow`가 들어 있고, 오케스트레이터가 같은 `meta`를 응답 `meta`로 돌려줘 `:282` `upstreamMeta`로 `chat_logs.context_json`에 저장된다. AI `GET /api/v1/chat/logs`·`/logs/{id}`(`:521`, `:535`, `:558`, `:572`)는 `contextJson`·`upstreamMeta`를 그대로 준다.
- **실측(값 출력 없이 건수만)**: 이번 시험 기록 26건 전부에 키가 있었다(22건에는 피보호자 전화 전체도). **운영 DB 전체 328건**의 `context_json`에 예약 API 키가 있다.
- **영향**: BE 중계는 `ChatLogProjection` 허용 목록으로 이 필드를 걸러 FE에 내보내지 않는다 - BE 경유 노출은 없다. 그러나 ① 키가 `reservation_credentials` 외에 두 번째 테이블에 퍼져 있고 ② AI 키를 가진 호출자는 기록 조회로 키를 읽을 수 있다(예약 키를 읽는 API는 원래 없다) ③ BE 필터가 유일한 방어선이다. 키를 가지면 그 사용자 이름으로 예약 조회·생성·취소가 된다.
- **수정 제안**: 저장 직전에 `upstreamMeta`에서 `reservationApiKey`·`storedFlow`(·`rawMessage`)를 뺀다(응답 처리 로직은 그대로). 기존 328건은 같은 키를 JSON에서 지우는 일회성 정리(운영 DB 쓰기라 실행 전 승인·백업). 회귀 테스트: 예약 흐름 한 바퀴 뒤 `context_json`에 키 문자열이 없음.

### 🟡 M-1 원문이 로그·트레이스에 남는 곳 3곳

| 위치 | 내용 |
|---|---|
| `reservation_orchestrator.py:434` | `_trace(..., "reservation_extraction_failed", error=str(exc))` - LLM 추출 예외 문구가 INFO 로그와 `decisionTrace`(chat_log)에 그대로. 독립 검토가 예외 문구에 이름·번호를 넣어 그대로 남는 것을 재현 |
| `reservation_confirm_flow.py:324` | `reservation_submit_failed`의 `message=msg[:200]` - 예약 서비스 오류 문구(입력값을 되돌려 주면 유출) |
| `medical_llm_service.py:256` | JSON 파싱 실패 시 모델 출력 앞 300자(상담 요약 원문)를 WARN으로 |

- **수정 제안**: 예외는 클래스명, 예약 서비스 오류는 `code`·`http_status`, 모델 출력은 글자 수만. `_trace`의 값에 원문이 들어가지 않음을 테스트로 고정.

## 4. 이슈 (🟢)

### 🟢 L-1 피보호자를 바꾸면 라우터 세션 기억으로 앞 피보호자의 병원이 따라온다 (실서버 S3)

- 고치기 도중 B로 바꿔 문장을 보내면 flowState는 버려지지만(환자 B 정상), 라우터가 같은 세션의 직전 규격화 문장(`_last_reservation_state`, `chat_service.py:114`·`:145`)을 이어 써서 A 상담에서 고른 병원·날짜가 B의 새 카드에 들어갔다. 예약은 확인 카드를 눌러야 생긴다.
- 단위 테스트(`test_switching_ward_while_continuing_by_text_does_not_carry_the_draft`)는 오케스트레이터만 거쳐 이 경로를 못 본다.
- **낮은 이유**: FE는 피보호자를 바꾸면 sessionId를 새로 만든다(`ChatbotContent.tsx:220-227`). API를 직접 만든 요청만 해당하고, 두 피보호자 모두 BE가 ACTIVE 연결로 검증한 대상이다.
- **제안**: 라우터 기억(`meta.router`)에도 wardId를 남기고 다르면 이어 쓰지 않는다.

### 🟢 L-2 배포 전에 저장된 wardId 없는 고치기 상태가 본인 상담으로 이어진다

- 옛 flowState에는 wardId가 없어 `"" == ""`로 본인 요청과 일치한다(`reservation_orchestrator.py:777`, `reservation_confirm_flow.py:535`). 대리 상담 중이던 옛 상태를 본인 요청으로 이어 보내면 앞 피보호자의 초안(환자·전화·`replaces`)이 이어졌다(독립 검토 재현). 같은 세션의 직전 턴일 때만이라 배포 시점의 짧은 구간 한정이고, 대리 상담으로 이어 가면 만료돼 안전하다.
- **제안**: `"wardId" in flow`를 요구한다(키 자체가 없으면 이어 쓰지 않음).

### 🟢 L-3 피보호자 전화가 없을 때 입력 연락처가 `context.phone`에 밀린다

- `_pin_ward_patient`의 `ctx.wardPhone or ctx.phone`(`reservation_confirm_flow.py:170`) - `wardPhone`이 없고 `phone`만 남아 있으면 입력 연락처를 덮는다. BE는 피보호자 전화가 없으면 `phone`도 지우므로 계약상 문제는 없다(방어 차원).

### 🟢 L-4 날짜 뒤에 조사가 붙으면 날짜를 못 읽는다 (#34 이전부터)

- `_find_date` 정규식의 끝 `\b`가 숫자와 한글 사이를 경계로 보지 않아 "2026-10-30에 예약"이 None이다(`reservation_intake.py:204`, 실행 확인). LLM 추출이 보완하는 경우가 많지만 규칙 파서가 우선이라 빠질 수 있다. 제안: `(?!\d)`로 바꾼다.

## 5. 테스트 공백

| 항목 | 고정됨 | 비고 |
|---|---|---|
| 상대 날짜 KST(`_find_date`·카드 표기) | ✅ | 지난 날짜 검사·슬롯 필터·라벨의 KST 일관성은 없음 |
| 대리 환자 고정(문장·고치기·전화 없음·본인) | ✅ | 저장된 초안으로 바로 확인하는 경로·시간 재선택 경로는 없음 |
| 고치기 wardId A→B(버튼·문장·변경) | ✅ | 본인↔대리 전환, wardId 없는 옛 행(L-2), 라우터 기억(L-1) 없음 |
| 카드 30분 만료·시각 없는 카드·취소 카드 | ✅ | 시계 역행 없음 |
| 라우터·폴백 로그 | ✅ | `_trace` 값의 원문(M-1) 없음 |
| `context_json`에 키 없음 | ❌ | H-1 |

## 6. 실서버 시험 기록 (gosky, 가상 사용자만)

- 13:27~13:30 KST. AI 재시작·설정 변경 없음, 송출 트래픽 0건 확인 후 실행.
- 예약 1건 생성(id 250, 피보호자 `qaw901`) → 챗봇 취소 흐름으로 즉시 취소 → 활성 예약 0건 확인.
- 정리: AI 챗 기록(`qarc-g1`·`qarc-s1`) 삭제 200, 예약 API 키(`qaw901`·`qaw902`·`qarc-s1`) 삭제 200.
- **남은 것**: 예약 서비스의 가상 사용자 3명(id 38·39·40, `sb-qaw901`·`sb-qarc-s1`·`sb-qaw902`)과 취소된 예약 1건. 예약 서비스에 삭제 API가 없어 승인 후 가드 스크립트로 지운다(지난 점검과 같은 방식). 현재 사용자 17명·예약 167건(시험 전 14명·166건).
- 첫 실행은 스크립트가 병원 검색 카드(`toolData`)를 고르지 못해 카드 전에 멈췄다(예약 생성 없음). FE와 같은 `hospital_confirm` 값으로 고쳐 다시 실행했다.

## 7. 수정용 프롬프트 초안 (승인 후 별도 작업)

```
[AI 예약 수정] 챗 기록의 예약 API 키 제거·로그 원문 3곳·세션 기억 wardId  [모델: opus - 자격 증명 저장]
대상: SilverBridgeAiServer main, 브랜치 fix/chat-log-credential-strip
PHASE 0. context_json 저장 경로·읽는 곳 전수(_last_flow_state·_last_reservation_state는 upstreamMeta의 pendingAction·flowState·router만 읽는지)
PHASE 1 계획(승인 후 구현):
  H-1 upstreamMeta 저장 전 reservationApiKey·storedFlow·rawMessage 제거 + 회귀 테스트(context_json에 키 없음)
      기존 328행 정리 스크립트(⚠️ 운영 DB 쓰기 - 백업 후 승인받고 실행, 건수만 출력)
  M-1 _trace error=클래스명, submit_failed message 제거(code·http_status만), medgemma 파싱 실패 로그는 글자 수만
  L-1 router meta에 wardId 저장, 다르면 prev_reservation 미사용
  L-2 flowState에 wardId 키가 없으면 이어 쓰지 않음
  L-3 _pin_ward_patient 전화: wardPhone만 사용(context.phone 폴백 제거 여부는 BE 계약 확인 후)
  L-4 _find_date 끝 경계를 (?!\d)로
검증: pytest 전체 + 변이(키 제거 되돌림·wardId 비교 제거)
배포: gosky AI 재시작은 시각 확인 후
커밋 메시지 초안: fix: 챗 기록에 예약 API 키를 저장하지 않고 로그 원문을 걷어내기
```

## 8. 참고

- 앞 점검: `(2026-10-22) audit-ai-reservation-confirm-flow.md`(이슈 6건 → #34 조치).
- 독립 검토의 임시 프로브는 작업 디렉터리에만 있다(커밋 안 함).
