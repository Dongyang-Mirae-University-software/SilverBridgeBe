-- 복약 알림 발송 (2026-08-05, 2차)
--
-- 1차(V35)는 등록·체크·조회까지였고 "정해진 시각에 먼저 울려주는" 발송 주체가 없었다.
-- 스케줄러가 복용 시각에 피보호자에게 알림을 보내고, 체크되지 않으면 한 번 더 보낸다.

-- 재알림 사용 여부. 어르신은 한 번으로 놓치는 경우가 많아 기본값은 켜짐이되,
-- 문자를 켜둔 사용자에게는 한 번 복용에 문자가 2건까지 나가므로 끌 수 있어야 한다.
-- NOT NULL DEFAULT true — 기존 행은 자동 백필된다.
ALTER TABLE medication_setting
    ADD COLUMN remind_again_enabled BOOLEAN NOT NULL DEFAULT true;

-- 발송 이력 겸 중복 발송 방지 기록.
--
-- 스케줄러는 1분마다 돌기 때문에 기록이 없으면 유예 창(기본 30분) 동안 같은 알림을 30번 보낸다.
-- UNIQUE (medication_id, dose_date, attempt)가 최종 방어선이다 — 주기가 겹쳐 돌거나 앱이 재기동돼도
-- 같은 (약, 날짜, 회차)는 한 번만 나간다.
--
-- 발송 "전"에 이 행을 먼저 남기고(선점) 커밋한 뒤 실제 발송한다. 발송이 실패해도 재시도하지 않는다 —
-- 알림이 두 번 가는 쪽이 한 번 빠지는 쪽보다 나쁘고, 재알림(attempt=2)이 자연스러운 두 번째 기회가 된다.
--
-- medication FK CASCADE — 약이 hard delete되면(피보호자·등록 보호자 탈퇴) 발송 이력도 함께 정리된다.
CREATE TABLE medication_reminder_log (
    id            BIGSERIAL   PRIMARY KEY,
    medication_id BIGINT      NOT NULL REFERENCES medication(id) ON DELETE CASCADE,
    dose_date     DATE        NOT NULL,   -- KST 기준 복용 예정일
    attempt       INT         NOT NULL,   -- 1=최초 발송, 2=재알림
    sent_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_medication_reminder UNIQUE (medication_id, dose_date, attempt)
);

-- 재알림 대상 조회는 "오늘 + attempt=1 + 발송 시각 구간"으로 찾는다.
CREATE INDEX idx_medication_reminder_retry ON medication_reminder_log (dose_date, attempt, sent_at);
