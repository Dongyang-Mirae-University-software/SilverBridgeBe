# 말벗 여러 날 위험 누적 - 위험 확률 눈금별 기준 (2026-11-15)

## 배경

- AI #296(11/15, gosky 01:18 배포)부터 말벗 위험 확률을 **위험 판단 어댑터(EXAONE 3.5 2.4B LoRA r1)**로 계산한다. 즉시 위험 기준은 0.5(전에는 기본 모델 + 0.99).
- AI 턴 응답에 `riskModel`이 붙는다: `"v9"`(기본 모델) · `"v9+lora-risk-r1"`(어댑터). `riskProbability`가 null이면 null.
- BE 여러 날 누적 규칙(V70, `CompanionRiskPattern`)은 `max_risk_prob >= 0.97`(v9 눈금) 하나뿐이었다. 어댑터 확률에 그대로 쓰면 0.97 이상인 턴은 이미 즉시 위험 알림을 받은 턴이라 누적 규칙이 사실상 쉰다.
- DGX r1 7일 흉내 시험(`~/llmbench/cmp/sim_week_r1.py`)은 누적 기준을 0.3으로 쟀다: 위험 주 알림 78% → 96%(소음)·74% → 100%, 안전 주 헛알림 33% → 30%·37% → 19%(v9 → r1).

## 결정 (사용자, 2026-11-15)

| 항목 | 결정 |
|---|---|
| 어댑터 눈금 누적 기준 | **0.3** (DGX 시험 값) |
| 누적 알림 스위치 | **꺼 둔다** (실제 어르신 대화 검증 전 - 기존 한계 유지) |
| 이미 섞인 데이터 | **옮기지 않는다** (11/15 01:18 이후 gosky 대화의 `max_risk_prob`에 어댑터 값이 들어감 - 스위치가 꺼진 기간이라 영향 없음) |

## 변경

| 파일 | 내용 |
|---|---|
| `V73__add_companion_max_risk_prob_lora.sql` | `companion_session.max_risk_prob_lora NUMERIC(5,4)` + 0~1 CHECK. 열 추가뿐(비가역 아님) |
| `CompanionRiskProbability` (새 값 타입) | 확률 + 눈금(`lora`). `atOrAbove(v9기준, 어댑터기준)` |
| `CompanionSession` | 눈금별 최댓값 칸. 기존 `recordTurn(…, BigDecimal, …)`은 v9 눈금으로 위임 |
| `CompanionService.turnRisk` | `riskModel` 없음·`"v9"` → v9, `"v9+…"` → 어댑터, 그 밖 → 기록 안 함(WARN, 값 없이 길이만) |
| `CompanionSessionWriter` | 턴 이벤트 조건을 그 턴 눈금의 기준으로 |
| `CompanionRiskPattern.isHigh` | v9 ≥ `threshold` **또는** 어댑터 ≥ `loraThreshold` |
| `CompanionProperties`·`application.yaml` | `lora-threshold`(`COMPANION_RISK_PATTERN_LORA_THRESHOLD`, 기본 0.3) |

## 바뀌지 않는 것

즉시 위험 알림(riskFlag), 위험 알림 대화당 1번 선점, 7일에 1번 UPSERT, D-1·ACTIVE 보호자 필터, L-1 같은 순간 두 건 방지, 알림 문구·채널, 스위치 기본값 false, v9 기준 0.97.

## 검증

- 단위: `CompanionRiskScaleTest` 7건(파싱·눈금별 최댓값·BigDecimal 위임·isHigh 경계·섞인 주·writer 이벤트·기본값). 기존 테스트의 모호해진 `recordTurn(null)` 호출 7곳은 형만 밝혔다(동작 동일).
- 말벗 도메인 단위 테스트 256건 통과. 전체 빌드·vkcs 통합 테스트(V73) 결과는 PR 본문.
- 실서버 관찰(11/15 gosky, AI 직접 호출 24턴): r1 확률은 위험 문장 0.99 이상, 아닌 말 0.003 이하로 양 끝에 몰렸다 - 이 표본으로는 0.3과 0.97이 같은 결과라 기준값은 DGX 7일 시험을 따랐다.

## 켤 때

`.env.dev`에 `COMPANION_RISK_PATTERN_ENABLED=true`(백업 후) + `up -d api`. 기존 L-5 주의(테스트 대화도 집계 → 시험 보호자 대체 문자 과금) 그대로.
