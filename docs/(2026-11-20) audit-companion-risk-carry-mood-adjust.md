# (2026-11-20) 기능 점검 - 말벗 위험 이어짐·회피 보정·기분 점수 (AI #326)

- 대상: AI main `eda3052`(#326, 재희 `feat/companion-quality-eval` 91d607a 병합)의 `risk_carry.py`·`mood_adjust.py`·`pipeline.py` `textMood.scores`·`reply_service.py` 몸 응급 안내(I-21)와, 이것이 지나가는 `companion_router._run_turn`·`_turn_response`·`_queue_for_review`·`dialogue_store`
- 기준: AI main `ee55dd2` = gosky 저장소·컨테이너(11/20 16:50 KST 기동). 실제 설정 `COMPANION_RISK_CARRY=true`·`COMPANION_AVOID_MOOD=true`·`COMPANION_REPLY_HISTORY=off`, 기분 e1600soup·위험 r2(0.50)·대답 cc7
- 방법: 코드 읽기 + `risk_carry.py` 파일만 불러 문장 시험(모델·API 키 미사용) + BE companion 도메인 읽기. 코드 수정 없음
- 테스트 기준선: 운영 이미지 임시 컨테이너 pytest 4390 통과 / 1 실패(`test_clip_encoder` 타임아웃 - 컨테이너에 `pgrep` 없음, 환경)

## 종합 판정: 조건부 PASS - 🔴 0 · 🟠 1 · 🟡 1 · 🟢 4

보호자 알림·여러 날 위험 누적·BE 저장은 영향이 없다. 결함은 **대화 안 안전 안내**에 있다: 위험한 말 바로 뒤에 도움을 거부하면 이어짐이 풀린다(H-1).

## 이상 없음 (PASS)

| 항목 | 근거 |
|---|---|
| 로그에 원문 없음 | 새 로그는 `[COMPANION-RISK-CARRY] carried=1 body=%d` 한 줄뿐 |
| 이어 간 턴이 다른 단계에 덮이지 않음 | 말 피함 마무리(`_apply_avoid_end`)·화제 전환(`_flat_topic_switch`)이 `riskFlag`·`SAFETY` 턴을 건너뜀, 되풀이 교체는 SAFETY 유지(`companion_keep_fixed_replies`) - `test_internal_source_is_safety_so_topic_switch_and_avoid_skip_it` |
| 보호자 알림 수 | 이어 간 턴은 직전 위험 턴 뒤에만 생기고, BE 위험 알림은 대화당 1번(`risk_notified_at` 선점) - 알림이 늘지 않음 |
| 여러 날 위험 누적 | 이어짐은 `riskProbability`를 바꾸지 않음 → BE `max_risk_prob(_lora)` 그대로 |
| BE 응답 읽기 | `AiCompanionClient`가 `JsonNode`로 읽음 - 새 필드(`riskCarry`·`textMood.scores`·`adjusted`)로 깨지지 않음 |
| 회피 보정 범위 | `textMood.label`만 바꾸고 `turnLabel`(대답 선택)·`turnResult`(BE 저장 기분)는 그대로, `usable=false`·CALM 아님·회피 아님은 그대로 - `test_companion_mood_adjust.py` 7건 |
| `scores` 안전성 | 4코드가 다 있을 때만 싣고 아니면 null - 다른 엔진(ggara02, 라벨 이름 다름)·라벨만 읽힌 경우도 null |
| 위험 판정 자체 | 이어짐은 판정(r2·0.5·kw4) 뒤에 덧붙는 단계라 판정 결과를 바꾸지 않음 |

## 🟠 H-1 위험 직후 도움 거부가 "도움 받겠다"로 읽혀 이어짐이 풀린다

- 위치: `app/services/companion/risk_carry.py` `ACCEPT_PATTERN`, `companion_router._carry_risk`
- `ACCEPT_PATTERN`이 "병원 가"·"119"·"전화하"·"연락하"만 보고 뒤의 거부를 보지 않는다. 시험 결과 `is_downplay` = False:
  "병원 가기 싫어" · "119 부르지 마" · "전화하지 마 아무한테도" · "연락하지 마" · "병원 가 봤자 소용없어"
- 결과: 그 턴은 이어 가지 않고 `d.lastRisk = False`로 직전 위험 상태까지 지운다. 그 말이 스스로 위험 판정(r2·kw4)에 걸리지 않으면 평소 꼬리질문이 나간다 - 기능이 막으려던 상황("넘기거나 얼버무리면 평소 질문으로 돌아갔다")과 같은 종류이고, 도움 거부는 넘기는 말보다 더 위험한 신호다.
- 영향 범위: 대화 안 안내만. 보호자 위험 알림은 직전 턴에서 이미 나갔다.
- 수정 제안: 거부 꼴(`(가기|부르기|하기)\s?싫`, `(부르|전화하|연락하|가)지\s?마`, `안\s?(갈|가|부를)`, `소용\s?없`, `필요\s?없`)을 `ACCEPT_PATTERN`보다 먼저 보아 넘기는 말로 친다. 위 5문장 + 수락 3문장("119 부를게"·"딸한테 전화해 볼게"·"병원 다녀왔어") 고정 테스트, `test_rule_matches_the_reviewed_labels_in_the_quality_set` 유지 확인.

## 🟡 M-1 "안 괜찮아"를 "괜찮다고 하셔도"로 받는다

- `DOWNPLAY_PATTERN`의 `괜찮`이 부정을 보지 않아 "하나도 안 괜찮아"·"괜찮지 않아"도 넘기는 말이 된다. 위험을 이어 가는 것은 맞지만, 안내 문장(`CARRY_REPLY`·`CARRY_BODY_REPLY`)이 "괜찮다고 하셔도…"로 시작해 어르신 말과 반대로 받는다(품질 기준 R5 넘겨짚기와 같은 종류).
- 수정 제안: 부정된 괜찮(`안\s?괜찮|괜찮지\s?않`)이면 이어 가되 "괜찮다고 하셔도"가 없는 안내 문장을 쓴다. 고정 테스트 2건.

## 🟢 Low

- **L-1 되묻기 턴이 직전 위험 상태를 지운다**: `_carry_risk`가 CONFIRM(낱말 의심·받아쓰기 확신도 낮음) 턴에도 돌아, 넘기는 말이 아니면 `lastRisk=False`가 된다. 위험 → 알아듣기 어려운 말 → 다시 말한 "괜찮아"는 이어지지 않는다. 음성 대답에서만 생긴다(글 대답은 되묻지 않음). 제안: CONFIRM 턴은 무응답 턴처럼 `lastRisk`를 그대로 둔다.
- **L-2 숨 막힘 + '죽'**: `is_body_risk("숨이 막혀 죽겠어")` = False(`MIND_HINT`의 `죽`). kw4 몸 응급 낱말(`body_emergency_only`)에 걸리면 몸 쪽 안내가 되므로 실제 영향은 kw4 결과에 달렸다 - 이번 점검에서 kw4 쪽은 돌려 보지 않았다(로컬에 앱 의존성이 없음). 수정 때 같이 확인.
- **L-3 문서 drift**: AI `docs/companion-contract.md`에 `textMood.scores`·`textMood.adjusted`가 없다(`riskCarry`는 있음). BE `CompanionDtos` 테스트 화면 `textMood` 설명(label·turnLabel·confidence·usable·reason)에도 없다 - 테스트 계정 화면용이라 동작 영향 없음.
- **L-4 회피 보정 설명이 실제와 다름**: `mood_adjust.py` 머리 설명이 "보고되는 글 기분(textMood.label, 백엔드 여러 날 기분 집계용)"이라 하지만 BE 하루 기분·주의 알림은 `turnResult`(`moodCode`·`label`)를 쓴다. 보정은 운영 기분 기록에 들어가지 않고 테스트 화면·측정에만 보인다. 운영 기록에 반영하려면 별도 결정(보정을 `turnResult`까지 내릴지)이 필요하다.

## 테스트 (PHASE D)

`test_companion_risk_carry.py`(10건)·`test_companion_mood_adjust.py`(7)·`test_companion_mood_scores.py`(3)·`test_companion_body_hint_reply.py`(4)는 정상 경로와 스위치를 덮는다. **빠진 것**: 도움 거부(H-1), 부정된 괜찮(M-1), 되묻기 턴 상태 유지(L-1) - 0건.

## 수정 범위 제안 (실행은 "시작해" 후, AI 저장소)

H-1·M-1·L-1을 한 PR로(`risk_carry.py`·`_carry_risk` + 테스트), L-3 계약 문서 두 줄 포함. 위험 판정(r2·0.5·kw4)·대답 프롬프트는 바꾸지 않는다. 확인: pytest 전체 + DGX 재생기(`tools/eval/companion_quality_dgx_run.py`)로 품질 세트 위험 놓침 0/7·헛경보 3/134·이어짐 3이 나빠지지 않는지. gosky 반영은 AI 재시작 확인 지점을 거친다. L-4는 사용자 결정 사항.
