-- 안부 말벗 (2026-11-04, PR-A)
--
-- 피보호자가 하루 5번(09·12·15·18·21시) AI 말벗과 짧게 대화한다. 대화는 AI 서버가 하고(재희 AI 계약
-- feat/companion-ai-part), 백엔드는 질문을 고르고 결과만 저장한다.
--
-- ⚠️ 대답 원문·받아 적은 글·AI 응답 문장은 어디에도 저장하지 않는다(사용자 결정 B, 2026-11-02).
-- 이 파일의 표는 질문 번호·횟수·기분 코드·위험 여부만 담는다.

-- 시간대별 고정 질문. 질문의 주인은 백엔드다(AI는 받은 문장만 쓴다).
-- 같은 문장에는 같은 번호(id)를 쓴다 - 문장을 고치려면 행을 바꾸지 말고 active=false로 내리고 새 행을 넣는다
-- (지난 대화의 question_id가 다른 문장을 가리키게 되면 응답률 집계가 틀어진다).
CREATE TABLE companion_question (
    id         BIGSERIAL    PRIMARY KEY,
    slot       VARCHAR(5)   NOT NULL,
    text       VARCHAR(200) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_companion_question_slot CHECK (slot IN ('09:00', '12:00', '15:00', '18:00', '21:00'))
);

CREATE INDEX idx_companion_question_slot ON companion_question (slot, active);

-- 대화 1회 = 1행. (피보호자, 날짜, 시간대) 하나에 대화 하나만 연다.
-- status: OPEN(진행 중) / FINISHED(결과 받음) / LOST(AI 쪽 대화가 사라짐 - 재시작 등)
-- end_reason·mood는 AI의 한글 값을 영문 코드로 바꿔 저장한다(결정 D). mood NULL = 판단할 수 없음(0·정상으로 바꾸지 않는다).
-- alert_level: AI가 준 URGENT·INFO만. 주의(NOTICE)는 PR-B에서 백엔드가 날짜별로 계산한다.
-- 회원 행이 지워지면(탈퇴 hard delete) 결과도 함께 지운다.
CREATE TABLE companion_session (
    id                BIGSERIAL    PRIMARY KEY,
    ward_id           VARCHAR(6)   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_date      DATE         NOT NULL,   -- KST
    slot              VARCHAR(5)   NOT NULL,
    question_id       BIGINT       NOT NULL REFERENCES companion_question(id),
    dialogue_id       VARCHAR(64),             -- AI 대화 번호. AI 호출 전 선점 중에는 NULL
    max_turns         INT,
    status            VARCHAR(20)  NOT NULL,
    end_reason        VARCHAR(20),
    mood              VARCHAR(20),
    turn_count        INT          NOT NULL DEFAULT 0,
    answered_count    INT,
    no_response_count INT,
    risk_flag         BOOLEAN      NOT NULL DEFAULT false,
    alert_level       VARCHAR(10),
    risk_notified_at  TIMESTAMPTZ,             -- 위험 알림은 대화당 1번(조건부 UPDATE로 선점)
    prompt_version    VARCHAR(100),
    started_at        TIMESTAMPTZ  NOT NULL,
    last_activity_at  TIMESTAMPTZ  NOT NULL,
    ended_at          TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_companion_session_slot UNIQUE (ward_id, session_date, slot),
    CONSTRAINT uq_companion_session_dialogue UNIQUE (dialogue_id),
    CONSTRAINT chk_companion_session_slot CHECK (slot IN ('09:00', '12:00', '15:00', '18:00', '21:00')),
    CONSTRAINT chk_companion_session_status CHECK (status IN ('OPEN', 'FINISHED', 'LOST')),
    CONSTRAINT chk_companion_session_end_reason CHECK (end_reason IN ('MAX_TURNS', 'NO_RESPONSE', 'USER_STOP')),
    CONSTRAINT chk_companion_session_mood CHECK (mood IN ('JOY', 'CALM', 'WORRY', 'DEPRESSED')),
    CONSTRAINT chk_companion_session_alert CHECK (alert_level IN ('URGENT', 'INFO'))
);

-- 오래 멈춘 대화 마감(5분 주기)은 OPEN + 마지막 활동 시각으로 찾는다.
CREATE INDEX idx_companion_session_open ON companion_session (status, last_activity_at);

-- 질문 알림 발송 기록 겸 중복 방지(선점 후 발송, 복약 알림과 같은 방식).
CREATE TABLE companion_prompt_log (
    id          BIGSERIAL   PRIMARY KEY,
    ward_id     VARCHAR(6)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    prompt_date DATE        NOT NULL,
    slot        VARCHAR(5)  NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_companion_prompt UNIQUE (ward_id, prompt_date, slot)
);

-- 탈퇴한 피보호자의 AI 쪽 말벗 데이터 삭제 대기열(결정 C - 실패하면 재시도).
-- FK를 두지 않는다: 회원 행이 지워진 뒤에도 남아 있어야 재시도할 수 있다. 담는 것은 ID뿐이다.
CREATE TABLE companion_purge_request (
    ward_id         VARCHAR(6)  PRIMARY KEY,
    requested_at    TIMESTAMPTZ NOT NULL,
    attempts        INT         NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ
);

-- 초기 질문 (2026-11-04). 독거노인 우울·고립 연구의 영역(수면·식사·활동·사회 접촉·하루 정리)을 시간대에 나눠
-- AI가 판정하는 감정·신호가 대답에 드러나게 골랐다. 근거: docs/(2026-11-04) companion-question-research.md.
-- 재희 v1(questions.json, 검토 대기)을 바탕으로 예/아니오로 끝나는 문장은 열린 질문으로 바꿨다. 재희 확인 후 바뀔 수 있다.
INSERT INTO companion_question (slot, text) VALUES
    ('09:00', '지난밤에는 잠을 어떻게 주무셨어요?'),
    ('09:00', '아침에 일어나셨을 때 몸은 좀 어떠셨어요?'),
    ('09:00', '아침에 편안하게 일어나셨나요? 어떠셨는지 들려주세요.'),
    ('09:00', '오늘 하루는 어떻게 시작하셨어요?'),
    ('12:00', '점심은 무엇을 드셨어요?'),
    ('12:00', '요즘 입맛은 어떠세요?'),
    ('12:00', '점심은 어디서, 누구와 드셨어요?'),
    ('12:00', '점심은 맛있게 드셨나요? 어떤 음식이었어요?'),
    ('15:00', '오늘 오후에는 무엇을 하며 지내셨어요?'),
    ('15:00', '요즘 즐겨 하시는 일이 있으세요? 말씀해 주세요.'),
    ('15:00', '오늘 바깥 바람은 쐬셨어요? 어디에 다녀오셨는지 들려주세요.'),
    ('15:00', '오늘 기분은 어떠세요? 그런 이유가 있을까요?'),
    ('18:00', '저녁은 어떻게 챙겨 드셨어요?'),
    ('18:00', '오늘 누구와 이야기를 나누셨어요?'),
    ('18:00', '요즘 가족이나 이웃 소식은 어떠세요?'),
    ('21:00', '오늘 하루는 어떠셨어요? 기억에 남는 일이 있으세요?'),
    ('21:00', '내일은 어떤 일을 하실 생각이세요?'),
    ('21:00', '요즘 마음은 어떠신지 편하게 말씀해 주세요.'),
    ('21:00', '오늘 하루 중 가장 좋았던 때는 언제였어요?');
