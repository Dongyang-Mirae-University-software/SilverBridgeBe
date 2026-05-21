-- =============================================
-- V20: 연결(connections)의 priority(통화 우선순위) 컬럼 제거
--
--      2026-05-19 미검증 API 정리에서 priority 변경/재정렬 경로
--      (WardConnectionController.updatePriority, ConnectionService.updatePriority,
--       Connection.updatePriority, ConnectionPriorityUpdateRequest)가 제거되어,
--      priority는 연결 생성 시 1로만 고정되는 죽은 컬럼이 되었다.
--      값이 전부 1이라 OrderByPriorityAsc 정렬도 사실상 insertion order와 동일해 무의미.
--      → 컬럼/체크제약/정렬 인덱스를 제거하고, ward 조회 정렬을 created_at 기반으로 대체한다.
--
-- ⚠️ 비가역 DDL (DROP COLUMN). 값이 전부 1이라 의미가 없어 별도 백업 불요.
--    DROP COLUMN이 의존 객체(chk_connections_priority 체크제약,
--    idx_connections_ward_status_priority 복합 인덱스)를 PostgreSQL 규칙에 따라
--    자동 cascade 제거한다.
-- =============================================

-- 1. priority 컬럼 제거 (체크제약·복합인덱스 자동 cascade)
ALTER TABLE connections
    DROP COLUMN priority;

-- 2. ward 상태별 조회의 created_at 정렬 대체 인덱스
--    findByWardIdAndStatusOrderByCreatedAtAsc  (ACTIVE 보호자 목록)
--    findByWardIdAndStatusOrderByCreatedAtDesc (PENDING 요청온 목록)
--    asc/desc 모두 동일 인덱스로 커버.
CREATE INDEX IF NOT EXISTS idx_connections_ward_status_created_at
    ON connections (ward_id, status, created_at);
