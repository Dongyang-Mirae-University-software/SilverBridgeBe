-- 피보호자별 SOS 동작 설정 테이블 (환경설정 > SOS 동작 설정의 계정 단위 동기화)
-- 기존에는 프론트 localStorage에만 저장돼 기기·브라우저를 바꾸면 초기화됐다 → 계정에 보관한다.
--
-- 이 설정은 "119를 어떻게 연결·안내할지"(프론트 흐름)만 정한다.
-- 보호자 알림은 WARD_SOS(필수 알림) 정책상 어떤 값에서도 항상 발송되며 이 설정으로 끌 수 없다.
--
-- 행이 없으면 애플리케이션 기본값(CALL_119_AND_NOTIFY)을 따른다 → 기존 사용자 백필 불필요.
-- users 참조는 ON DELETE CASCADE — 회원 탈퇴(hard delete) 시 자동 정리.
CREATE TABLE sos_setting (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    VARCHAR(6)   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sos_action VARCHAR(30)  NOT NULL,   -- CALL_119 / CALL_119_AND_NOTIFY / NOTIFY_GUARDIAN_FIRST
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sos_setting_user UNIQUE (user_id)
);
