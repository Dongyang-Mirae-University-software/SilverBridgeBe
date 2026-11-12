# 영향 범위 점검 - 말벗 종료 이유 AVOIDED + 뇌졸중 징후 위험 낱말 (2026-11-12)

- 변경: BE #482 (`c068d75f`, V72) · FE #262 (`7a5c133`) · AI #275 (`3025382`) · 바탕 AI #272·#274
- 기준: BE dev `23ddbe5a`(#482 뒤 문서만) · FE develop(#262 뒤 말벗과 무관한 관리자·복약 문구 3건) · AI main `3025382`. gosky BE `c068d75f`·AI `3025382` 반영
- 점검만 했다. 코드는 바꾸지 않았다. Anthropic API 키·실서버 위험 문장은 쓰지 않았다.
- 시험: BE `./gradlew test` 2605 통과 · AI 3648 통과(실패 21·수집 오류 10은 main과 같은 환경 실패) - #482·#275 머지 때 값이며 그 뒤 코드 변경 없음

## 변경점과 불변식

1. 종료 이유 enum에 `AVOIDED`(한 대화에서 두 번 말을 피해 말벗이 마무리)
   - 불변식: AVOIDED는 USER_STOP·NO_RESPONSE와 섞이지 않고 저장·전달되며, 종료 이유로 분기하는 모든 곳이 AVOIDED를 의도대로 다룬다
2. AI kw4 위험 낱말에 뇌졸중 징후 20개(`STROKE_KEYWORDS`)
   - 불변식: 낱말 위험이 켜진 턴은 어느 턴 경로에서도 안전 안내 + 보호자 위험 알림으로 이어지고, 말 피함·조기 종료로 빠지지 않는다

## 사용처 × 불변식 판정

| 저장소 | 위치 | 내용 | 판정 |
|---|---|---|---|
| BE | `CompanionEndReason.fromAi` | `"AVOIDED"` → AVOIDED, 모르는 값 empty | PASS |
| BE | `CompanionResult.from` | empty면 USER_STOP (배포 순서가 바뀌면 AVOIDED가 USER_STOP으로 저장됨 - BE 먼저 배포로 해소) | PASS |
| BE | `CompanionResult.asAbandoned` | USER_STOP만 NO_RESPONSE로, AVOIDED 그대로(`CompanionResultTest`) | PASS |
| BE | `CompanionService` 턴 `ended` → `finalizeQuietly` | 그 턴에서 바로 결과 저장, 실패하면 마감 스케줄러가 다시 받음(그때도 AVOIDED 유지) | PASS |
| BE | `CompanionService` "마치기"(276행) | AI finish는 이미 끝난 대화의 이유를 바꾸지 않음(`store.finish`는 ended면 그대로) | PASS |
| BE | `CompanionService` 마감 스케줄러(306행) | `asAbandoned` - 위와 같음 | PASS |
| BE | `CompanionSessionWriter.finish` | OPEN일 때만 기록 - 겹친 마감이 AVOIDED를 덮지 않음 | PASS |
| BE | `CompanionSession.end_reason` / V72 CHECK | 4개 값, `CompanionCheckSyncTest`가 V72와 enum 대조 | PASS (L-2) |
| BE | `TurnResponse`·`ResultResponse` Swagger | AVOIDED 설명 있음 | PASS |
| BE | 보호자 today·week·days·sessions, 관리자 ai-stats·응답률, 주의 알림, 위험 누적, 테스트 모드 analysis | 종료 이유를 읽지 않음(grep 0건, JPQL·native 포함) | 해당 없음 |
| FE | `src/service/interface/ward/companion.ts` | 타입 union에만 있음, 화면 분기 없음(`ended`만 봄) | PASS |
| AI | `_apply_avoid_end` | 위험 턴 제외 두 번째 말 피함에 `AVOIDED` | PASS |
| AI | `dialogue_store.add_turn` | 연속 무응답 → MAX_TURNS → end_reason 순, 끝난 대화는 그대로 | PASS |
| AI | `finish_dialogue` | 진행 중이면 USER_STOP, 끝났으면 그대로 | PASS |
| AI | `turn_record.build_dialogue_result` | `d.endReason` 그대로 | PASS |
| AI | `config.py` 475행 주석 · `docs/companion-contract.md` 122행 · `docs/companion-team-status.md` 130행 | 종료 이유 목록에 AVOIDED 없음(옛 설명) | **FAIL - M-1** |
| AI | `pipeline._analyzed_turn`(249행) | `keyword_risk` → SAFETY + riskFlag, 말 피함 횟수 제외 | PASS (시험 고정) |
| AI | `pipeline._lexicon_checked_turn`(178·181행) | 위험 모델 켜짐/꺼짐 모두 `keyword_risk` 포함 → SAFETY | PASS (시험 없음 - L-3) |
| AI | `pipeline._low_confidence_turn`(155행) | `keyword_risk` 포함 → SAFETY, 연속 확인 종료에서 제외 | PASS (시험 없음 - L-3) |
| AI | 위험 확률(`riskProbability`)·BE 여러 날 누적 | v9 확률만 실음 - 낱말 규칙은 확률을 바꾸지 않아 누적 판정 영향 없음 | 해당 없음 |
| AI | 의료 챗봇 응급 규칙 | 말벗 `keyword_risk`를 쓰지 않음 | 해당 없음 |
| BE | 위험 알림 대화당 1번·테스트 대화 하루 1번 | `riskFlag` 경로 그대로 - 뇌졸중 낱말도 같은 선점 | PASS |
| AI | `avoidance` 몸 증상 제외 | 혀·입이 등은 말 피함에서 빠지고, 낱말 위험이면 riskFlag로 위험 턴 → 조기 종료 안 함 | PASS |

## 이슈

### 🟡 M-1 AI 계약 문서·주석의 종료 이유 목록이 옛 값

- `docs/companion-contract.md` 122행(`MAX_TURNS`·`NO_RESPONSE`·`USER_STOP`) - 백엔드·팀이 계약으로 읽는 문서다. `docs/companion-team-status.md` 130행(옛 한글 값), `app/core/config.py` 475행 주석("endReason USER_STOP")도 같다.
- 동작 영향 없음. 제안: 세 곳에 `AVOIDED`(두 번 말을 피해 말벗이 마무리, BE V72)를 적는다. AI 문서 PR.

### 🟢 L-1 배포 전 말 피함 대화가 USER_STOP으로 남아 있다

- 11/12 15:50~16:46 사이 말 피함 종료는 USER_STOP으로 저장됐다(확인된 것: 테스트 대화 `sessionId=198`). 구별할 표시가 없어 되돌릴 수 없다. 이 시간대는 시험 피보호자 테스트 대화뿐이었고 종료 이유를 쓰는 곳이 없어 수용한다(기록만).

### 🟢 L-2 CHECK 동기화 시험이 V72 파일 이름을 고정한다

- 다음에 값을 더하면 시험 경로도 바꿔야 한다(잊으면 새 마이그레이션이 아닌 V72와 대조해 실패 - 막히기는 한다). 제안: 그 제약을 정의한 마지막 마이그레이션을 찾아 대조하게 바꾼다.

### 🟢 L-3 시험 빈곳

- 뇌졸중 낱말 × 낱말 확인 턴·확신도 낮은 턴 시험이 없다(코드 경로는 위 표대로 PASS).
- AI가 내는 종료 이유 집합 ⊆ BE enum을 고정하는 시험이 없다. 제안: AI에 종료 이유 상수 집합 시험(`{"MAX_TURNS","NO_RESPONSE","USER_STOP","AVOIDED"}`). BE `CompanionValueMappingTest`는 4개 모두 매핑을 이미 확인한다.

### 🟢 L-4 남 이야기 속 뇌졸중 징후도 강제 알림이 된다

- "아들이 말이 어눌해졌어"처럼 다른 사람 이야기도 낱말이 맞으면 위험이다. 기존 몸 응급 낱말("영감이 쓰러졌어"의 `쓰러졌`)과 같은 동작이고 CareCall 24,248 대답 중 0건이라 수용(기록만).

## 결정 질문

- Q-1: 위험 턴 안내 문장(`SAFETY_REPLY`)이 "마음이 많이 무거우신 것 같아요… 119"라 뇌졸중·숨참 같은 몸 응급에는 맞지 않는다. 몸 응급 낱말이 걸린 턴에 몸 응급용 문장(예: "지금 몸이 많이 불편하신 것 같아요. 바로 119에 전화하시거나 가족에게 알려 주세요.")을 따로 쓸지.

## 종합 판정

**통과(PASS)** - 두 불변식을 어기는 사용처 없음. M-1(계약 문서 옛 값)은 동작 영향이 없는 문서 수정이고, L-1~L-4는 기록·시험 보강 제안이다.

수정 커밋 메시지 초안 (AI 문서·시험):

```
docs: 말벗 계약 문서·설정 주석의 종료 이유에 AVOIDED 추가
test: 뇌졸중 낱말 위험이 낱말 확인·확신도 낮은 턴에서도 안전 안내로 가는지와 종료 이유 집합 고정
```

## 조치 (2026-11-12)

| 이슈 | 조치 |
|---|---|
| M-1 | AI #276 - `companion-contract.md`·`companion-team-status.md`·`companion-data-handoff.md`·config 주석에 AVOIDED |
| L-1 | 수용(기록만) |
| L-2 | BE - `CompanionCheckSyncTest`가 제약마다 그 CHECK를 마지막으로 정의한 마이그레이션을 찾아 대조(파일 이름 고정 제거), 종료 이유는 V72 이상인지 확인 |
| L-3 | AI #276 - 뇌졸중 낱말 × 확신도 낮은 턴·낱말 확인 턴 시험, AI가 내는 종료 이유 집합 = BE enum 4개 시험 |
| L-4 | 수용(기록만) |
| Q-1 | AI #276 - 몸 응급 낱말만 걸린 위험 턴은 "많이 놀라셨겠어요. 몸이 많이 불편하시거나 위급한 상황이면 바로 119에 전화하시고, 가까운 가족에게도 알려 주세요."(`COMPANION_BODY_SAFETY_REPLY`, 기본 켬). 자해·죽음 낱말이 같이 있거나 모델 확률로만 위험이면 기존 안내. BE·FE는 안내 문장 글자에 의존하지 않음(grep 확인) |
