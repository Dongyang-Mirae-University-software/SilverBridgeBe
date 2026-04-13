-- users 테이블의 status CHECK 제약조건에서 PENDING 제거
-- PENDING은 구 카카오 신규 가입 역할 선택 대기 상태로 더 이상 사용하지 않음

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_status;
ALTER TABLE users ADD CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
