-- 안부 말벗 여러 날 위험 누적 알림 (2026-11-11)
--
-- 한 번의 대답으로는 riskFlag(AI 위험 확률 0.99)에 못 미치지만, 위험 확률 0.97 이상인 대답이 최근 7일 중 서로 다른 두 날에
-- 나오면 보호자에게 알린다. AI 턴 응답의 riskProbability(v9 척도, 계산 못 하면 null)를 대화마다 최댓값으로만 둔다.
-- 대답 원문은 저장하지 않는다(결정 B) - 확률 숫자 하나뿐이다. 되돌리기: 칸·테이블을 지우면 된다(데이터 손실은 이 기능 기록뿐).

-- 대화 중 가장 높았던 턴 위험 확률. null = 확률을 받은 턴이 없음(v4 프롬프트·계산 실패·무응답 턴뿐).
ALTER TABLE companion_session ADD COLUMN max_risk_prob NUMERIC(5, 4);
ALTER TABLE companion_session ADD CONSTRAINT chk_companion_session_max_risk_prob
    CHECK (max_risk_prob IS NULL OR (max_risk_prob >= 0 AND max_risk_prob <= 1));

-- 피보호자당 1행 - "7일에 1번" 선점. INSERT ... ON CONFLICT DO UPDATE ... WHERE last_notified_date <= 기준일 한 문장으로
-- 이긴 쪽만 보낸다(동시 턴·자정 경계에도 1번). 관리자 "알림 보냄" 오늘 집계에도 쓴다. 담는 것은 날짜·일수뿐이다.
CREATE TABLE companion_risk_pattern_notice (
    ward_id            VARCHAR(6)  PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    last_notified_date DATE        NOT NULL,   -- KST
    days               INT         NOT NULL,
    sent_at            TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_companion_risk_pattern_date ON companion_risk_pattern_notice (last_notified_date);
