-- 미복용 시 보호자 알림 (2026-08-05, 3차)
--
-- 2차(V36)는 피보호자에게 복용 시각 알림 + 재알림까지였다. 그 뒤로도 끝내 체크되지 않으면
-- 아무 일도 일어나지 않아, 보호자는 앱을 열어봐야만 알 수 있었다. 이제 저녁에 한 번 요약해 알린다.
--
-- ⚠️ 이 알림은 "약을 안 드셨다"가 아니라 "체크되지 않았다"를 알린다 —
--    실제로는 드시고 체크만 안 한 경우가 흔하며, 보호자에게 사실이 아닌 단정을 통보하면 안 된다.

-- 보호자별 미복용 알림 수신 여부.
-- 행이 없으면 애플리케이션 기본값(ON)을 따른다 → 기존 사용자 백필 불필요.
--
-- 보호자가 "이 알림만" 끌 수 있어야 한다. 그러지 못하면 알림 피로로 앱 알림을 통째로 꺼버리고,
-- 그때 SOS·이상감지 같은 필수 알림까지 함께 죽는다.
CREATE TABLE guardian_medication_setting (
    id                   BIGSERIAL   PRIMARY KEY,
    guardian_id          VARCHAR(6)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    missed_alert_enabled BOOLEAN     NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_guardian_medication_setting UNIQUE (guardian_id)
);

-- 미복용 요약 발송 기록 겸 중복 방지.
--
-- 축이 (보호자, 피보호자, 날짜)인 이유 — 요약은 "그 보호자가 그 피보호자에 대해 하루 한 번" 받는다.
-- 약 단위인 medication_reminder_log(2차)와 의미가 달라 테이블을 분리한다.
--
-- 2차와 동일하게 발송 "전"에 이 행을 먼저 커밋하고 보낸다(선점 후 발송).
CREATE TABLE medication_missed_alert_log (
    id           BIGSERIAL   PRIMARY KEY,
    guardian_id  VARCHAR(6)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ward_id      VARCHAR(6)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    dose_date    DATE        NOT NULL,   -- KST 기준
    missed_count INT         NOT NULL,   -- 체크되지 않은 약 수
    total_count  INT         NOT NULL,   -- 판정 시각까지 예정된 약 수
    sent_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_medication_missed_alert UNIQUE (guardian_id, ward_id, dose_date)
);

-- "오늘 이 피보호자 건을 이미 보냈는지" 조회용.
CREATE INDEX idx_medication_missed_alert_lookup ON medication_missed_alert_log (dose_date, ward_id);
