-- 안부 말벗(checkin) 1차 (2026-11-01)
--
-- 하루 다섯 번(09·12·15·18·21시) 피보호자에게 짧게 안부를 여쭙고, 대화가 끝나면 AI가 정리한 정서·생활 신호를 저장한다.
-- 대화 원문(받아쓴 글자·AI 문장)과 음성·사진은 BE에 저장하지 않는다 - AI 서버에만 30일 남고(탈퇴 시 삭제),
-- BE는 정리 결과(요약 고정 문장·신호·근거 턴 번호)만 갖는다. 보호자 화면에도 원문은 보여 주지 않는다(2026-11-01 결정 Q3).
--
-- 네 테이블 모두 피보호자 탈퇴 시 CASCADE로 사라진다(탈퇴자 데이터를 붙들지 않는다).
-- slot 은 AI 계약 값('09:00' 등)을 그대로 쓴다.

-- 보호자가 정한 시각별 질문. 피보호자 단위(보호자끼리 공유, 마지막 저장이 적용 - 결정 Q4). 행이 없으면 기본 질문.
CREATE TABLE checkin_question (
    id          BIGSERIAL    PRIMARY KEY,
    ward_id     VARCHAR(6)   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    slot        VARCHAR(5)   NOT NULL,
    question    VARCHAR(200) NOT NULL,
    -- 마지막으로 고친 보호자. 그 보호자가 탈퇴해도 질문은 피보호자 것이라 남긴다.
    updated_by  VARCHAR(6)   REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_checkin_question_ward_slot UNIQUE (ward_id, slot),
    CONSTRAINT chk_checkin_question_slot CHECK (slot IN ('09:00', '12:00', '15:00', '18:00', '21:00'))
);

-- 피보호자 본인 설정. 표정 분석 동의는 기본 꺼짐 - 꺼져 있으면 사진을 AI로 보내지 않는다.
CREATE TABLE checkin_setting (
    id            BIGSERIAL   PRIMARY KEY,
    ward_id       VARCHAR(6)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    face_consent  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_checkin_setting_ward UNIQUE (ward_id)
);

-- 대화 한 번. session_id 는 BE가 만든 무작위 값(사용자 식별자 금지 - AI·로그로 나가는 값이다).
CREATE TABLE checkin_session (
    id               BIGSERIAL   PRIMARY KEY,
    session_id       VARCHAR(64) NOT NULL,
    ward_id          VARCHAR(6)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    check_date       DATE        NOT NULL,           -- KST
    slot             VARCHAR(5)  NOT NULL,
    question         VARCHAR(200) NOT NULL,          -- 시작 때 보낸 질문(보호자가 나중에 바꿔도 이 대화의 질문은 그대로)
    status           VARCHAR(20) NOT NULL,
    turn_count       INT         NOT NULL DEFAULT 0,
    ai_done          BOOLEAN     NOT NULL DEFAULT FALSE,  -- AI가 대화를 마무리했다고 알린 턴이 있었는지
    risk_emergency   BOOLEAN     NOT NULL DEFAULT FALSE,  -- 턴 중 한 번이라도 위험이 올라갔는지(정리하지 않고 끝나도 남는다)
    risk_self_harm   BOOLEAN     NOT NULL DEFAULT FALSE,
    end_reason       VARCHAR(20),
    started_at       TIMESTAMPTZ NOT NULL,
    finished_at      TIMESTAMPTZ,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_checkin_session_session_id UNIQUE (session_id),
    CONSTRAINT chk_checkin_session_slot CHECK (slot IN ('09:00', '12:00', '15:00', '18:00', '21:00')),
    CONSTRAINT chk_checkin_session_status CHECK (status IN ('IN_PROGRESS', 'FINISHED', 'EXPIRED')),
    CONSTRAINT chk_checkin_session_end_reason CHECK (end_reason IS NULL OR end_reason IN ('TURN_LIMIT', 'TIME_LIMIT', 'USER_END', 'AI_END')),
    CONSTRAINT chk_checkin_session_turn_count CHECK (turn_count BETWEEN 0 AND 20)
);

-- 같은 피보호자·날짜·시각에 진행 중인 대화는 하나뿐(동시 시작 경합의 최종 방어선).
CREATE UNIQUE INDEX uq_checkin_session_in_progress
    ON checkin_session (ward_id, check_date, slot) WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_checkin_session_ward_date ON checkin_session (ward_id, check_date);

-- 대화 정리 결과. 시각마다 하나(2차 평소 대비 판정·보호자 조회의 근거).
CREATE TABLE checkin_result (
    id                  BIGSERIAL    PRIMARY KEY,
    session_id          BIGINT       NOT NULL REFERENCES checkin_session(id) ON DELETE CASCADE,
    ward_id             VARCHAR(6)   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    check_date          DATE         NOT NULL,
    slot                VARCHAR(5)   NOT NULL,
    emotion             VARCHAR(20)  NOT NULL,
    self_report         VARCHAR(20),
    sleep               VARCHAR(10),
    meal                VARCHAR(10),
    pain                VARCHAR(10),
    mood                VARCHAR(10),
    lonely              VARCHAR(10),
    risk_emergency      BOOLEAN      NOT NULL DEFAULT FALSE,
    risk_self_harm      BOOLEAN      NOT NULL DEFAULT FALSE,
    -- AI의 단정하지 않는 고정 문장("~라고 하셨어요"). 대화 원문이 아니다.
    summary             VARCHAR(500),
    -- [{signal, turnNo}] - 어느 턴이 근거인지만(원문 없음)
    evidence            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    turns               INT          NOT NULL DEFAULT 0,
    end_reason          VARCHAR(20)  NOT NULL,
    avg_answer_sec      DOUBLE PRECISION,
    avg_speech_sec      DOUBLE PRECISION,
    avg_pause_ratio     DOUBLE PRECISION,
    avg_chars_per_sec   DOUBLE PRECISION,
    avg_loudness_db     DOUBLE PRECISION,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_checkin_result_session UNIQUE (session_id),
    CONSTRAINT uq_checkin_result_ward_date_slot UNIQUE (ward_id, check_date, slot),
    CONSTRAINT chk_checkin_result_slot CHECK (slot IN ('09:00', '12:00', '15:00', '18:00', '21:00')),
    CONSTRAINT chk_checkin_result_emotion CHECK (emotion IN ('JOY', 'CALM', 'LONELY', 'LOW_ENERGY', 'WORRIED', 'UNDECIDED')),
    CONSTRAINT chk_checkin_result_self_report CHECK (self_report IS NULL OR self_report IN ('JOY', 'CALM', 'LONELY', 'LOW_ENERGY', 'WORRIED')),
    CONSTRAINT chk_checkin_result_sleep CHECK (sleep IS NULL OR sleep IN ('GOOD', 'BAD')),
    CONSTRAINT chk_checkin_result_meal CHECK (meal IS NULL OR meal IN ('ATE', 'SKIPPED')),
    CONSTRAINT chk_checkin_result_pain CHECK (pain IS NULL OR pain IN ('YES', 'NO')),
    CONSTRAINT chk_checkin_result_mood CHECK (mood IS NULL OR mood IN ('GOOD', 'NORMAL', 'BAD')),
    CONSTRAINT chk_checkin_result_lonely CHECK (lonely IS NULL OR lonely IN ('YES', 'NO')),
    CONSTRAINT chk_checkin_result_end_reason CHECK (end_reason IN ('TURN_LIMIT', 'TIME_LIMIT', 'USER_END', 'AI_END'))
);

CREATE INDEX idx_checkin_result_ward_date ON checkin_result (ward_id, check_date);
