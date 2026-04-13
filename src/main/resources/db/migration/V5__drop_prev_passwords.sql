-- 비밀번호 재사용 방지 기능 제거 (필수 보안 수준 초과)
ALTER TABLE users DROP COLUMN IF EXISTS prev_password1;
ALTER TABLE users DROP COLUMN IF EXISTS prev_password2;
