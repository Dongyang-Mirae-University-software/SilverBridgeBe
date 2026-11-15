-- 안부 말벗 여러 날 위험 누적 - 위험 판단 어댑터 눈금 칸 (2026-11-15)
--
-- AI #296(11/15)부터 위험 확률을 위험 판단 어댑터(LoRA r1)로 계산할 수 있고, 턴 응답 riskModel 로 눈금을 알린다
-- ("v9" = 기본 모델, "v9+<어댑터>" = 어댑터). 두 눈금은 같은 숫자라도 뜻이 달라 누적 기준을 따로 둔다
-- (v9 0.97 · 어댑터 0.3, DGX 7일 흉내 시험). 기존 max_risk_prob 은 v9 눈금 전용으로 남긴다.
-- 열 추가뿐 - 기존 행은 바꾸지 않는다(11/15 01:18 이후 gosky 대화의 max_risk_prob 에 어댑터 값이 섞였지만 옮기지 않음,
-- 누적 알림 스위치가 꺼져 있던 기간이라 영향 없음 - 사용자 결정). 되돌리기: 칸을 지우면 된다.

-- 대화 중 가장 높았던 턴 위험 확률(어댑터 눈금). null = 어댑터 눈금 확률을 받은 턴이 없음.
ALTER TABLE companion_session ADD COLUMN max_risk_prob_lora NUMERIC(5, 4);
ALTER TABLE companion_session ADD CONSTRAINT chk_companion_session_max_risk_prob_lora
    CHECK (max_risk_prob_lora IS NULL OR (max_risk_prob_lora >= 0 AND max_risk_prob_lora <= 1));
