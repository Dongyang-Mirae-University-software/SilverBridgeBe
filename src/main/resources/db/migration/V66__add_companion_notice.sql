-- 안부 말벗 주의 알림 (2026-11-04, PR-B)
--
-- 걱정·우울이 3일 이상 이어지면 ACTIVE 보호자에게 하루 1번 알린다(결정 I). 연속 일수는 BE가 날짜별 결과로 계산한다.
-- 하루 기분 = 그날 마지막으로 판정된 대화의 기분(판단 불가 제외), 대화가 없는 날은 연속을 끊는다(사용자 결정 2026-11-04).

-- 발송 기록 겸 하루 1번 보장(선점 후 발송, 복약 알림과 같은 방식). 관리자 "기분 변화 알림 보냄" 집계에도 쓴다.
-- 담는 것은 피보호자 ID·날짜·기분 코드·연속 일수뿐이다(대답 원문 없음).
CREATE TABLE companion_notice_log (
    id          BIGSERIAL   PRIMARY KEY,
    ward_id     VARCHAR(6)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notice_date DATE        NOT NULL,   -- KST
    mood        VARCHAR(20) NOT NULL,
    days        INT         NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_companion_notice UNIQUE (ward_id, notice_date),
    CONSTRAINT chk_companion_notice_mood CHECK (mood IN ('WORRY', 'DEPRESSED'))
);

-- 관리자 집계는 날짜로 센다.
CREATE INDEX idx_companion_notice_date ON companion_notice_log (notice_date);

-- 날짜별 결과 조회(연속 일수·집계)는 피보호자·날짜로 찾는다 - UNIQUE (ward_id, session_date, slot) 앞부분으로 충분하다.
-- 관리자 오늘 집계는 날짜로만 찾으므로 따로 둔다.
CREATE INDEX idx_companion_session_date ON companion_session (session_date);

-- 보호자 알림 종류 설정 - 말벗 주의 알림(COMPANION_NOTICE) 수신 여부. 기본 켜짐(끌 수 있다).
-- 위험 알림(COMPANION_RISK)은 필수라 칸을 만들지 않는다.
ALTER TABLE guardian_notification_preference
    ADD COLUMN companion_notice_enabled BOOLEAN NOT NULL DEFAULT true;
