-- =============================================
-- V22: 보호자 조회 정렬 커버 인덱스로 교체
--
--      getMyWards 는 (guardian_id, status IN) 필터 + created_at 내림차순 정렬을 수행하는데,
--      기존 idx_connections_guardian_status (guardian_id, status)는 정렬을 커버하지 못해
--      인메모리 정렬이 발생했다. created_at을 포함한 3컬럼 인덱스로 교체해 필터+정렬을 함께 커버한다.
--      guardian_id 프리픽스는 getMyConnectionRequests(guardian_id 필터)에도 그대로 사용된다.
--
-- 가역적 변경: 인덱스 교체(DROP + CREATE). 데이터 영향 없음.
-- =============================================

DROP INDEX IF EXISTS idx_connections_guardian_status;

CREATE INDEX IF NOT EXISTS idx_connections_guardian_status_created_at
    ON connections (guardian_id, status, created_at);
