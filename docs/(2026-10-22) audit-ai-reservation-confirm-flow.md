# AI 챗봇 예약 확인·취소·변경 흐름 재점검 - 조치 후 코드 기준 (2026-10-22)

> 템플릿 B(기능 점검). **점검만 했고 코드는 수정하지 않았다.** 같은 날 오전 점검(`(2026-10-22) audit-ai-chat-reservation-confirm.md`, 기준 `298b3e2`)의 이슈를 조치한 AI #28·#30 이후 코드를 다시 본 것이다. 앞 점검 결과를 전제로 삼지 않고 PHASE 0부터 코드로 다시 확인했다.

## 0. 종합 판정

**조건부 PASS** - 🔴 0 · 🟠 1 · 🟡 1 · 🟢 4

- 확인 없이 예약·취소가 일어나는 경로, 남의 초안·남의 예약을 건드리는 경로는 이번에도 없다. 동시 클릭 중복은 DB 선점(`reservation_token_claims`)과 예약 서비스 부분 유니크 인덱스로 막혀 있다.
- 🟠 1건은 보안이 아니라 **날짜 오류**다. AI 컨테이너가 UTC라서 KST 00:00~08:59에 "내일"이라고 하면 **KST 오늘**로 잡힌다. 확인 카드에 "날짜: 오늘"이 보이므로 사용자가 알아챌 수는 있지만, 매일 아침 9시간 동안 반드시 재현된다.
- 🟡 1건은 대리 상담에서 **채팅 문장의 이름·전화가 BE가 정한 피보호자 값을 덮어쓴다**는 것이다. 예약은 그 피보호자 계정에 생기지만 환자명·연락처는 문장에서 온 값이 들어간다.

## 1. 기준과 환경 (PHASE -1)

| 항목 | 값 |
|---|---|
| 점검일 | 2026-10-22 |
| 기준 커밋 | AI main `50b0497`(#31, 문서만) = 코드 기준 `34aeb03`(#30). 프롬프트의 `bfd4a05`(#27)는 오전 점검 이전 커밋이라, 사용자 지시("현재 시점으로")에 따라 현재 main으로 고정 |
| gosky 반영 | `34aeb03` (코드 동일) |
| pytest | **420 passed** (로컬 임시 가상환경, `torch`·`ultralytics`·`transformers` 없이도 전부 수집·통과) |
| 함께 본 것 | FE develop `996ac32`(`ChatbotContent`·`ChatUiPrompt`·`ChatToolCard`), 예약 서비스 `a6e0f06`(`reservation.service.ts`) |
| 방법 | 코드 직독 + 임시 재현 테스트 3건(작업 디렉터리, 커밋 안 함) + gosky AI 컨테이너 시간대 읽기(읽기 전용) |
| 안 한 것 | 실서버 가상 계정 시험(② 폴백 정리 배포와 시각 조율 필요 - §7), 실제 LLM 대량 문장 측정 |

## 2. 흐름 × 상태 (PHASE 0)

### 2-1. 상태 저장 위치

| 상태 | 저장 | 키 | 유효 범위 |
|---|---|---|---|
| 확인 대기 `pendingAction`(kind=create/cancel, token, draft 또는 reservationId, **wardId**, replaces) | `chat_log.context_json.upstreamMeta` | 사용자 ID의 **가장 최근 로그 1행** + 같은 `sessionId` | 다음 턴 한 번. 시간 제한 없음(§5 L-3) |
| 고치기·변경 초안 `flowState`(draft, replaces, editToken) | 같음 | 같음 | 같음. **wardId 없음**(§5 L-1) |
| 토큰 선점 | `reservation_token_claims` (user_id, token) PK | 사용자 + 토큰 | 10분 뒤 정리 |
| 버튼 값 | `confirm:/edit:/yes:/no:{12자 hex}`, pick은 예약 id | - | 초안 내용은 싣지 않음 |

AI 재시작 후에도 상태는 DB에 남아 확인 카드가 그대로 동작한다(메모리 상태 없음).

### 2-2. 흐름

| 흐름 | 경로 | 외부 호출 |
|---|---|---|
| 새 예약 | `handle` → `build_reservation_draft` + LLM 추출 → `_advance`(병원→날짜→시간→환자) → `_confirm_reply`(pendingAction 저장) → 버튼 `confirm:` → `_valid_pending` → `required_gap` → **선점** → `_create_from_draft`(같은 시간 사전 확인) | `create_reservation` 1곳(`reservation_confirm_flow.py:272`) |
| 고치기 | `edit:` → flowState(editToken) → `reservation_edit` → 항목 비우고 `_advance` → 새 카드 | 없음 |
| 취소 | 문장 → `_start_pick`(오늘 이후 10건) → pick → `_load_cancellable`(예약 서비스 재조회, 키 소유자만) → 카드 `yes:` → **선점** → 취소 | `cancel_reservation` (`:443`) |
| 변경 | 문장 → pick → 기존 예약 재조회 → `replaces` 붙여 `_advance` → 카드 → 새 예약 성공 **뒤에만** 기존 취소 | `:328`, 실패 시 취소 재확인 카드 |
| 날짜 미입력 | 새 요청이면 이력 날짜를 버리고 이번 문장 날짜만 → 없으면 날짜 UI | - |
| 보호자 대리 | 예약 키 = `ensure_credential(wardId or userId)`(`chat_service.py:166-172`), 환자명·전화 = `wardName`/`wardPhone`(BE가 검증·덮어씀) → 이후 LLM 추출이 덮을 수 있음(§4 M-1) | - |

## 3. A. 보안·인가

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| A-1 | 확인 우회(카드 없이 uiSelection) | **PASS** | 생성·취소는 `_valid_pending`(kind·token·wardId 일치) 뒤에만. 테스트 `test_confirm_without_matching_pending_never_books`·`test_cancel_keep_and_forged_token_do_not_cancel`·`test_cross_kind_and_empty_tokens_are_rejected` |
| A-2 | 초안 바꿔치기(다른 사용자·세션 토큰) | **PASS** | 상태는 요청자 userId의 최근 로그 + sessionId에서만 읽는다(`chat_service.py:315-327`). 초안은 서버 저장값이고 버튼엔 토큰만. `test_two_sessions_of_the_same_user_do_not_cross` |
| A-3 | 남의 reservationId 취소 | **PASS** | 내 계정 키로 `get_reservation` 재조회 → 예약 서비스 404 메시지 그대로, 취소 호출 없음. `test_someone_elses_reservation_id_relays_the_service_404` |
| A-4 | 대리 예약의 "누구의 예약" | **부분 PASS** | 기본값은 BE가 넘긴 피보호자 값이고 확인 시점 wardId 불일치는 거절. 다만 채팅 문장(LLM 추출)이 환자명·전화를 덮고 카드 첫 줄도 그 이름을 쓴다(§4 M-1), flowState는 wardId에 묶이지 않는다(§5 L-1) |
| A-5 | 로그·decisionTrace의 전화·상담 원문 | **PASS(예약 경로)** | 트레이스는 `phone_last4`·`patient_name_len`만, 예약 API 로그도 끝 4자리. 예약 밖 `[라우터]` INFO 로그는 §5 L-2 |

## 4. 이슈 (🟠·🟡)

### 🟠 H-1 상대 날짜("오늘·내일·모레")를 컨테이너 시간대(UTC)로 계산한다

- **위치**: `app/services/reservation_intake.py:200` `_find_date`의 `base = base or date.today()`. 이 값이 LLM 추출값보다 우선한다(`reservation_orchestrator.py:787`, `:813`, `reservation_intake.py:286`).
- **환경**: gosky `silverbridge-ai-server` 컨테이너 `date` = UTC, `TZ` 미설정(2026-10-22 읽기 확인). Dockerfile·compose에도 TZ 설정이 없다. LLM 프롬프트(`reservation_prompts.py:78`)·지난 날짜 검사(`reservation_orchestrator.py:38`)·카드 날짜 표기(`reservation_common.py:21`)는 KST를 쓴다 - 룰 파서만 어긋난다.
- **재현**(임시 테스트, `date.today()`를 KST 전날로 고정 = UTC 컨테이너의 KST 00:00~08:59): "내일 ○○병원 내과 오후 2시 예약해줘" → 초안 날짜 = **KST 오늘**, 카드 "날짜: 오늘". 같은 시간대의 "오늘"은 KST 어제가 되어 예약 서비스의 지난 날짜 거절로 이어진다.
- **영향**: 매일 9시간 동안 반드시 재현된다. 확인 카드에 "오늘"이 보여 막을 수는 있지만, 어르신이 "내일"이라고 말하고 카드를 그대로 누르면 **하루 이른 병원 예약**이 생긴다. 오전 점검의 "상대 날짜 KST" 항목은 LLM 프롬프트만 확인했고 룰 파서는 보지 않았다.
- **테스트**: 없음. `test_reservation_date_ask.py`는 "날짜를 묻는가"만 본다.

### 🟡 M-1 대리 상담에서 채팅 문장이 피보호자 이름·전화를 덮어쓴다

- **위치**: `build_reservation_draft`가 BE 값(`wardName`·`wardPhone`)을 먼저 넣고(`reservation_intake.py:259-260`), `_apply_extracted`가 LLM 추출 `patient_name`·`phone`으로 **무조건 덮는다**(`reservation_orchestrator.py:481-484`). 카드 첫 줄도 초안 이름을 우선한다(`reservation_confirm_flow.py:217` `who = name or wardName`).
- **재현**(임시 테스트): 피보호자 A 대리 상담에서 LLM이 다른 이름·번호를 추출 → 카드 "○○님의 예약을 보호자님이 대신…", 예약은 **A 계정 키로** 다른 이름·번호로 생성.
- **영향**: 권한 밖 접근은 아니다(계정은 BE가 ACTIVE 연결로 검증한 피보호자 것). 그러나 BE 정책 "대리 상담의 이름·전화는 BE가 정한다(클라이언트 값 불신, 2026-10-20 ⑦)"의 의도가 AI 쪽에서 우회된다. 피보호자 A의 예약 목록에 다른 사람 이름·연락처가 남고, 병원에는 A가 아닌 사람의 정보가 간다.
- **판단 필요**: 대리 상담에서 다른 가족(예: 배우자)의 예약도 허용할지는 제품 결정이다. 허용하지 않으면 wardId가 있을 때 추출된 `patient_name`·`phone`을 버리고, 허용하면 카드 첫 줄만큼은 "{wardName}님 계정으로 예약돼요"처럼 계정 주인을 BE 값으로 보여야 한다.

## 5. 이슈 (🟢)

### 🟢 L-1 고치기·변경 상태(`flowState`)가 wardId에 묶이지 않는다

- `pendingAction`은 wardId를 저장·비교하지만(`reservation_confirm_flow.py:182`), `flowState`는 저장하지 않고(`:200`, `:491`) `reservation_edit`(`:512`)·문장 이어쓰기(`reservation_orchestrator.py:773-782`)도 비교하지 않는다.
- **재현**(임시 테스트): 피보호자 A 카드에서 "고치기" → 다음 요청부터 `wardId`를 B로 → B 계정 키로 예약되는데 환자명·전화는 A 값. 변경 흐름이면 A의 기존 예약 취소를 B 키로 불러 실패 안내가 뜬다(A 예약은 그대로).
- **낮은 이유**: FE는 피보호자를 바꾸면 `sessionId`를 새로 만들고 화면을 비운다(`ChatbotContent.tsx:220-227`). 그래서 정상 화면으로는 재현되지 않고 API를 직접 만든 요청만 가능하다. 그 경우에도 두 피보호자 모두 BE가 ACTIVE 연결로 검증한 대상이다.
- **제안**: flowState에 wardId를 넣고 이어 쓰기 전에 비교한다(pendingAction과 같은 방식).

### 🟢 L-2 `[라우터]` INFO 로그에 증상 요약·예약 문장이 남는다 (#26 이전부터)

- `chat_service.py:156`이 예약 경로는 규격화 문장, 상담 경로는 `symptom_summary`(상담 내용 요약)를 INFO로 남긴다(`251d006` 라우터 도입 때부터). 예약 흐름 자체의 로그는 깨끗하다.
- 참고: `chat_service.py:251` `_LOG.exception(..., exc)`는 선점 DB 오류 때 SQL 파라미터(userId·토큰)를 남긴다. 개인정보는 아니다.
- **제안**: 라우터 로그는 경로·엔진·길이만 남긴다(BE 챗 중계 "본문 로그 금지"와 같은 기준). 범위가 챗 전체라 별도 작업으로 둔다.

### 🟢 L-3 확인 카드에 시간 제한이 없다

- 유효 조건은 "같은 세션의 직전 턴"뿐이다. 마지막 대화가 확인 카드면 며칠 뒤에도 "예약하기"가 동작한다. 만료 안내 문구는 "확인 시간이 지났어요"다.
- 지난 날짜는 예약 서비스가 거절하고(`reservation.service.ts:18-19`), 같은 시간 중복은 사전 확인·유니크 인덱스가 막는다. 실해는 작다.
- **제안**: pendingAction에 생성 시각을 넣고 30분 등 상한을 둔다(선택).

### 🟢 L-4 응답 조립 중복

- 확인 흐름(`reservation_confirm_flow.py`)은 `_flow_message`·`_flow_error` 헬퍼를 써서 새 중복은 거의 없다. 다만 `reservation_followup` UiPayload가 두 곳(`:318-323`, `:450-455`)에 복제돼 있다.
- 오케스트레이터의 목록·상세 응답 dict(`reservation_orchestrator.py:621-766`)는 #26 이전부터 리터럴로 반복된다.

## 6. B·C·D 결과

### B. 기능 정합성

| 항목 | 결과 | 근거 |
|---|---|---|
| "예약하기" 두 번(순차·동시) | PASS | 순차: 직전 턴이 아니면 만료. 동시: DB 선점 PK + 사전 같은 시간 확인 + 예약 서비스 부분 유니크 인덱스. `test_simultaneous_confirm_clicks_create_exactly_one_reservation`, `test_many_threads_claiming_one_token_get_exactly_one_winner` |
| 선점 DB 오류 | PASS | IntegrityError 외 오류는 전파 → `chat_service`가 폴백 응답, 예약 호출 없음. `test_database_error_during_claim_stops_the_booking` |
| 변경 순서 | PASS | 새 예약 성공 뒤에만 기존 취소, 새 예약 실패 시 기존 유지, 기존 취소 실패 시 재확인 카드. `test_change_*` 4건 |
| 날짜 미입력 | PASS | 묻는다. `test_request_without_date_asks_for_date_instead_of_assuming_today` |
| 상대 날짜 KST | **FAIL** | §4 H-1 |
| 확인 대기 중 다른 말 | PASS | 상담·인사는 새 로그를 쓰므로 카드 만료. 예약 문장은 카드를 다시 띄움. 새 요청은 고치기 흐름 탈출. `test_unrelated_turn_in_between_invalidates_the_pending_confirm`, `test_typing_yes_*`, `test_new_request_text_leaves_the_edit_flow_without_booking` |
| 응급 문장 | PASS | `_force_emergency`가 라우팅 전에 상담으로 보낸다. `test_emergency_text_in_every_stage_goes_to_counselling` |
| AI 재시작 | PASS | 상태가 모두 DB라 카드는 그대로 동작한다(유효 시간 없음은 L-3) |

### C. 구조·계약

- 응답 키(`type`·`ui`·`tool`·`toolData`)는 그대로다. 새 field 5종은 FE `ChatUiPrompt`가 `kind`(select)로 그리고, `cancel_reservation` 도구 카드는 FE가 몰라 그리지 않는다(`ChatToolCard`에 분기 없음 → null). 오전 점검과 같다.
- 응답 dict 중복은 L-4.

### D. 테스트 공백

| 항목 | 고정됨 | 비고 |
|---|---|---|
| 확인 전 미호출·위조·교차 토큰·세션 교차 | ✅ | confirm_cancel·scenarios |
| 동시 클릭 1건 | ✅ | 메모리 선점 + DB 선점 각각 |
| 남의 예약 404 | ✅ | |
| 대리 카드 표시·wardId 불일치 거절 | ✅ | `test_guardian_proxy_card_says_whose_reservation_it_is`, `test_confirm_from_a_different_ward_context_is_rejected` |
| 대리 상담에서 추출 이름이 피보호자 이름을 덮지 않음 | ❌ | M-1 결정 뒤 |
| 고치기 중 wardId 변경 거절 | ❌ | L-1 - 기계적으로 막을 수 있음 |
| KST 기준 "내일" | ❌ | H-1 - `_find_date(base=)` 주입 또는 KST 고정으로 기계적 검증 가능 |
| 로그에 이름 없음 | ✅ | `test_patient_name_is_not_written_to_trace_or_logs` |

## 7. 실서버 시험

**하지 않았다.** 프롬프트상 ② 폴백 정리의 AI 재시작과 시각이 겹치면 안 되고 실행 전에 확인을 받아야 한다. 이번 이슈 3건은 코드·임시 테스트·컨테이너 시간대 확인으로 원인이 확정돼 실서버 시험이 판정을 바꾸지 않는다. H-1을 실서버에서 보려면 KST 09시 이전에 가상 계정으로 "내일" 예약 카드까지만 띄우면 된다(예약하기 전 "그만두기").

## 8. 수정용 프롬프트 초안 (승인 후 별도 작업)

```
[AI 예약 수정] 상대 날짜 KST·대리 환자 정보·고치기 wardId  [모델: sonnet, H-1 테스트 설계는 opus 확인]
대상: SilverBridgeAiServer main, 브랜치 fix/reservation-kst-and-ward-binding
PHASE 0. 재점검 문서 §4·§5 재현 테스트를 tests/에 먼저 추가해 실패 확인
PHASE 1 계획(승인 후 구현):
  H-1 _find_date 기본 base를 datetime.now(ZoneInfo("Asia/Seoul")).date()로. (선택) Dockerfile TZ=Asia/Seoul은
      다른 시각 계산(클립·로그)에 영향이 있어 하지 않는다. 테스트: base 주입 + date 몽키패치로 UTC 전날 상황 고정
  M-1 (사용자 결정 필요) wardId가 있으면 추출된 patient_name·phone 무시 / 또는 카드에 계정 주인(wardName) 별도 표기
  L-1 flowState에 wardId 저장, reservation_edit·문장 이어쓰기에서 불일치면 _stale
  (선택) L-3 pendingAction createdAt + 30분 상한
검증: pytest 전체, 변이(KST 되돌림·wardId 비교 제거) 시 실패 확인
배포: gosky AI 재시작은 다른 AI 작업과 시각 조율 후, 카메라 트래픽 없는 시각에
커밋 메시지 초안: fix: 예약 상대 날짜를 KST로 계산하고 대리 예약 환자 정보를 피보호자에 묶기
```

## 9. 참고

- 앞 점검: `(2026-10-22) audit-ai-chat-reservation-confirm.md`(기준 `298b3e2`, 이슈 5건 조치 + 한계 해소).
- 근거 문서: `(2026-10-20) analysis-ai-chat-reservation.md` 3-2절(②가 수정 중이라 이 점검은 건드리지 않음).
- 임시 재현 테스트 3건은 커밋하지 않았다(작업 디렉터리에만 있다).
