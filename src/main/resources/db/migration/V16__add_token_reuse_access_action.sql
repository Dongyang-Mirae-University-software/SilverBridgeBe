-- V16: access_logs.action 에 TOKEN_REUSE_DETECTED 값 추가
-- Refresh Token 재사용(도난 신호) 감지 시 강제 폐기와 함께 access_logs에 기록한다.
-- V12에서 WITHDRAW 추가 시와 동일한 패턴으로 CHECK 제약을 교체한다.

ALTER TABLE access_logs DROP CONSTRAINT chk_access_logs_action;

ALTER TABLE access_logs ADD CONSTRAINT chk_access_logs_action CHECK (
    action IN ('LOGIN', 'LOGOUT', 'KAKAO_LOGIN', 'TOKEN_ISSUE', 'PASSWORD_RESET', 'WITHDRAW', 'TOKEN_REUSE_DETECTED')
);