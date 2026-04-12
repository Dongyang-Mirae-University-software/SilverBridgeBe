-- =============================================
-- V3: email_verified 컬럼 제거
--     이메일 인증 기능 제거에 따른 불필요 컬럼 삭제
-- =============================================
ALTER TABLE users DROP COLUMN IF EXISTS email_verified;
