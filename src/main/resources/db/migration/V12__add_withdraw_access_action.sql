-- access_logs.action 에 WITHDRAW 값 추가 (본인 회원 탈퇴 이력 기록용)
-- 기존 CHECK 제약을 교체하여 WITHDRAW 값을 허용한다.

ALTER TABLE access_logs DROP CONSTRAINT chk_access_logs_action;

ALTER TABLE access_logs ADD CONSTRAINT chk_access_logs_action CHECK (
    action IN ('LOGIN', 'LOGOUT', 'KAKAO_LOGIN', 'TOKEN_ISSUE', 'PASSWORD_RESET', 'WITHDRAW')
);
