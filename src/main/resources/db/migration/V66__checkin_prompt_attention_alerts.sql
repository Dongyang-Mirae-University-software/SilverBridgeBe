-- 안부 말벗 2차 (2026-11-01): 5시각 안부 알림·평소 대비 "확인 필요"·보호자 알림
--
-- 모두 추가만 한다(기존 열·행 변경 없음, 비가역 DDL 없음).

-- 안부 알림 받기(피보호자 단위). 기본 꺼짐 - 보호자가 켜야 하루 다섯 번 피보호자에게 푸시가 간다.
-- 피보호자 본인도 끌 수 있다. 말벗을 켠 서버의 모든 피보호자에게 갑자기 푸시가 가지 않게 하려는 것이다.
ALTER TABLE checkin_setting ADD COLUMN prompt_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- 마지막으로 바꾼 사람(보호자 또는 본인). 탈퇴하면 비운다.
ALTER TABLE checkin_setting ADD COLUMN prompt_changed_by VARCHAR(6) REFERENCES users(id) ON DELETE SET NULL;
CREATE INDEX idx_checkin_setting_prompt_enabled ON checkin_setting (ward_id) WHERE prompt_enabled;

-- 평소 대비 판정(정리 때 한 번 계산해 저장). 근거는 고정 코드 목록(원문 없음).
ALTER TABLE checkin_result ADD COLUMN attention BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE checkin_result ADD COLUMN attention_reasons JSONB NOT NULL DEFAULT '[]'::jsonb;

-- 피보호자 안부 알림 발송 기록 - 선점 후 발송(먼저 커밋한 뒤 보낸다). UNIQUE가 중복 발송의 최종 방어선이다.
CREATE TABLE checkin_prompt_log (
    id          BIGSERIAL   PRIMARY KEY,
    ward_id     VARCHAR(6)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    check_date  DATE        NOT NULL,
    slot        VARCHAR(5)  NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_checkin_prompt_log UNIQUE (ward_id, check_date, slot),
    CONSTRAINT chk_checkin_prompt_log_slot CHECK (slot IN ('09:00', '12:00', '15:00', '18:00', '21:00'))
);
CREATE INDEX idx_checkin_prompt_log_date_slot ON checkin_prompt_log (check_date, slot);

-- 보호자 알림 발송 기록 - 위험·확인 필요는 시각마다, 미응답은 하루 한 번(서비스가 날짜 단위로 먼저 확인).
CREATE TABLE checkin_guardian_alert_log (
    id           BIGSERIAL   PRIMARY KEY,
    guardian_id  VARCHAR(6)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ward_id      VARCHAR(6)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    check_date   DATE        NOT NULL,
    slot         VARCHAR(5)  NOT NULL,
    kind         VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_checkin_guardian_alert_log UNIQUE (guardian_id, ward_id, check_date, slot, kind),
    CONSTRAINT chk_checkin_guardian_alert_log_slot CHECK (slot IN ('09:00', '12:00', '15:00', '18:00', '21:00')),
    CONSTRAINT chk_checkin_guardian_alert_log_kind CHECK (kind IN ('RISK', 'ATTENTION', 'MISSED'))
);
CREATE INDEX idx_checkin_guardian_alert_log_ward_date ON checkin_guardian_alert_log (ward_id, check_date);

-- 보호자 알림 종류 설정: 안부 미응답 알림(끌 수 있다, 기본 ON). 위험 알림은 필수라 설정이 없다.
ALTER TABLE guardian_notification_preference
    ADD COLUMN checkin_missed_enabled BOOLEAN NOT NULL DEFAULT TRUE;
