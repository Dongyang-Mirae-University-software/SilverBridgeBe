-- 안부 말벗 턴 기록·대화 내용 저장 동의 (2026-11-11 사용자 결정)
--
-- 보호자 "대화 보기"용. 턴별 기분(글 없음)은 항상 저장하고, 말벗 문장·어르신 대답(받아 적은 글 포함)은
-- 피보호자가 따로 동의했을 때만 저장한다(개인정보 보호법 제23조 민감정보 별도 동의). 글은 30일 뒤 매일 04:55에
-- 비운다(제21조 보유기간 경과 시 파기). 동의 철회 시 그 피보호자의 글을 즉시 비운다. 원문 미저장(결정 B, 11/02)을
-- 동의 조건부로 바꾼 것이다.
-- 되돌리기: 두 테이블을 지우면 된다(데이터 손실은 이 기능 기록뿐) - 비가역 아님.

-- 피보호자당 1행. 행이 있으면 동의. 철회 = 행 삭제(+ 글 비움). 탈퇴는 FK CASCADE, 역할 변경은 리스너가 지운다.
CREATE TABLE companion_transcript_consent (
    ward_id      VARCHAR(6)  PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    consented_at TIMESTAMPTZ NOT NULL
);

-- 턴 1행. mood NULL = 판단할 수 없음. bot_text·answer_text는 동의했을 때만, 30일 뒤 NULL.
CREATE TABLE companion_turn (
    id          BIGSERIAL    PRIMARY KEY,
    session_id  BIGINT       NOT NULL REFERENCES companion_session(id) ON DELETE CASCADE,
    turn_no     INT          NOT NULL,
    mood        VARCHAR(20),
    bot_text    TEXT,
    answer_text TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_companion_turn_session_turn UNIQUE (session_id, turn_no),
    CONSTRAINT chk_companion_turn_mood CHECK (mood IN ('JOY', 'CALM', 'WORRY', 'DEPRESSED'))
);

-- 30일 정리(글이 남은 행만)
CREATE INDEX idx_companion_turn_text_created ON companion_turn (created_at)
    WHERE bot_text IS NOT NULL OR answer_text IS NOT NULL;
