-- =============================================
-- V15: users 테이블에 (role, status, created_at DESC) 복합 인덱스 추가
-- =============================================
-- 사용 쿼리:
--   - 관리자 회원관리 통합 검색 (searchByKeywordAndFilters)
--     : WHERE role/status 필터 + ORDER BY created_at DESC + LIMIT
--   - 관리자 회원관리 사용자 목록 (findByRoleInOrderByCreatedAtDesc)
--     : WHERE role IN (..) + ORDER BY created_at DESC
--   - 관리자 대시보드 최근 가입 회원 (findByRoleNotOrderByCreatedAtDesc)
--     : WHERE role <> ADMIN + ORDER BY created_at DESC + LIMIT
--   - 관리자 회원관리 탭별 건수 (countByRole) — 인덱스 only scan 가능
--
-- 기존엔 풀스캔 후 정렬이 발생했으나, 본 인덱스로 필터+정렬을 한 번에 커버한다.
CREATE INDEX IF NOT EXISTS idx_users_role_status_created_at
    ON users (role, status, created_at DESC);
