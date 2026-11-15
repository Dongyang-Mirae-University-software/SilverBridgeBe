# 기능 점검 - 말벗 위험 판단 어댑터(AI #296·#298) + 누적 기준 눈금 분리(BE #487) (2026-11-15)

## 대상

| 저장소 | 커밋 | 내용 |
|---|---|---|
| AI | #296 `dd9c0a2` · #298 `1a62553`(다른 세션, r1 → r2·기분 e900 → e1300 기본값) | v9 위험 확률을 `risk` 어댑터로, 기준 0.5 / 없거나 `COMPANION_RISK_LORA_SUBDIR=`면 기본 모델 + 0.99 / 응답 `riskModel` |
| BE | #487 `996aa54c` | `riskModel`로 눈금 구분, V73 `max_risk_prob_lora`, 누적 높은 날 = v9 ≥ 0.97 또는 어댑터 ≥ 0.3, 스위치 꺼짐 |

반영: gosky AI `1a62553`(main, `path=v9+lora-risk-r2 threshold=0.50`), gosky·vkcs BE `996aa54c`(V73 적용).

점검 파일: AI `config.py`·`llm_client.py`(AdapterGate·adapter_dirs·_attach_adapters·_load)·`risk.py`·`pipeline.py`·`companion_router.py`·`docs/companion-contract.md`·`tests/test_companion_risk_lora.py`·`tests/test_companion_judgment_audit_fix.py` / BE V73·`CompanionRiskProbability`·`CompanionSession`·`CompanionService.turnRisk`·`CompanionSessionWriter`·`CompanionRiskPattern`·`CompanionRiskPatternListener`·`CompanionProperties`·`application.yaml`·`CompanionRiskScaleTest`·`CompanionRiskPatternFullIntegrationTest`.

## A. 보안·로그 - PASS

- 확률·`riskModel` 원문은 로그·알림 문구에 없다. BE 모르는 눈금 WARN은 길이만(`riskModelLength`). AI 시작 로그는 경로 이름·기준값만(개인 정보 없음).
- 새 로그는 말벗 도메인 로그 검사(`CompanionTranscriptLogGuardTest`)를 통과(전체 빌드 2686건).

## B. 기능 정합성 - PASS

| 확인 | 결과 |
|---|---|
| AI 확률 경로 = 기준 경로 | `risk_adapter_active`가 `has_adapter`(모델 적재 후 어댑터 목록 고정)로 정하고 확률·기준·`riskModel`이 같은 함수를 쓴다. 적재 실패(LlmUnavailable)면 확률도 None |
| v4·risk_first on/off·확인 턴·확신도 낮은 턴 | v4는 어댑터 무시·`riskModel` 미출력, 세 경로 모두 `is_risk(prob, llm)`(테스트 고정) |
| AI ↔ BE 계약 | AI `"v9"`·`"v9+<폴더>"`, BE는 `"v9"`·`"v9+"` 접두로 판별. r2(`v9+lora-risk-r2`)도 BE 변경 없이 맞는다. 계약 문서 r2로 갱신됨 |
| BE 누적 판정 | 리스너가 엔티티를 읽어 `qualifyingDays`로 판정(DB에서 미리 거르지 않음) → 새 칸 반영. L-1·D-1·ACTIVE 필터·마감 경로는 확률을 쓰지 않거나 그대로 |
| **실서버 끝까지** | gosky 시험 피보호자 테스트 대화 1턴(평범한 문장, 대화 602): `max_risk_prob` NULL · `max_risk_prob_lora` 0.0000, 모르는 눈금 WARN 0 |
| 스위치를 켤 때 | gosky 최근 7일 168대화(16명): v9 칸 0.97 이상 2(모두 11/15 01:18 전 v9 값), 섞인 기간(01:18~17:53) 대화 3개는 모두 0.3 미만 → 섞인 데이터가 판정에 끼지 않는다 |

## C. 구조·계약 - PASS

트랜잭션·AFTER_COMMIT 경계 변화 없음. V73 열 추가·CHECK 0~1·NUMERIC(5,4)(두 서버 기동 시 매핑 정상). `lora-threshold` 기본값은 단위 테스트로 확인.

## D. 테스트

AI 19건(경로·기준·전환·깨진 어댑터) + 기존 동시 실행 테스트(가짜 `has_adapter`가 항상 참이라 #296 뒤로는 위험 어댑터 경로를 실제 게이트 규칙으로 탄다 - 6명 동시에 위험 판정 유실 없음). BE 7건(파싱·칸·경계·섞인 주·이벤트·기본값).

## 이슈

🔴 0 · 🟠 0 · 🟡 0 · 🟢 4

| # | 내용 | 근거 | 제안 |
|---|---|---|---|
| L-1 | 위험 확률이 기본 모델(None) → `risk` 어댑터로 바뀌어, 다른 대화의 기본 모델 생성(신호·요약)이 끝날 때까지 기다린다(전에는 함께 들어갔다). 우선 호출이라 대기는 진행 중 생성 1건으로 묶이고 시간 상한(위험 25초·턴 60초) 안이며 동시 6턴 테스트로 유실 없음을 확인했다. 실서버 동시 부하 지연은 미측정 | AI `llm_client.py` `AdapterGate._can_enter` | 수용 가능. 실사용이 늘면 DGX에서 동시 2~6턴 위험 지연 측정 |
| L-2 | 눈금별 기준 고르기가 두 곳 - 턴 이벤트(`CompanionRiskProbability.atOrAbove`)와 높은 날(`CompanionRiskPattern.isHigh`). 정책 "판정은 한 곳"과 어긋날 여지 | BE `CompanionSessionWriter`·`CompanionRiskPattern.isHigh` | 한쪽이 다른 쪽을 부르게 합치기 |
| L-3 | 실 DB 통합 테스트(`CompanionRiskPatternFullIntegrationTest`)에 어댑터 눈금 경로(AI 응답 `riskModel` → `max_risk_prob_lora` → 리스너)가 없다. 이번 점검에서 실서버로 저장은 확인 | BE `src/integrationTest/.../CompanionRiskPatternFullIntegrationTest.java` | 어댑터 눈금 사례 1건 추가 |
| L-4 | `lora-threshold`(기존 `threshold`도) 범위 검사가 없어 오타(예: 3)면 누적 규칙이 조용히 꺼진다 | BE `CompanionProperties.RiskPattern` | 0 초과 1 이하 검사 또는 기동 WARN |

참고(이슈 아님): 어댑터 칸에 r1(18:07 전)·r2 값이 같은 칸에 섞인다 - 정책 파일에 기록됨(스위치 꺼진 기간). 프롬프트가 v4면 위험 어댑터는 올라가지만 쓰이지 않는다(GPU 88MB, 운영은 v9).

## 종합

**PASS** (Low 4건). 스위치를 켜기 전 막는 것 없음. L-2·L-3·L-4는 한 PR로 묶을 수 있다.

수정용 커밋 메시지 초안: `fix: 말벗 누적 위험 기준 고르기를 한 곳으로 + 기준값 범위 검사 + 어댑터 눈금 실 DB 테스트 (점검 L-2·L-3·L-4)`
