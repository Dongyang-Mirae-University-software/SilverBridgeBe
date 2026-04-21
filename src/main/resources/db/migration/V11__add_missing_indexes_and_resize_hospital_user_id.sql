-- =============================================
-- V11: 누락된 복합 인덱스 추가 + hospital_reservations.user_id VARCHAR(6) 통일
-- =============================================

-- 1. connections: 보호자 기준 상태 필터링 복합 인덱스
--    (엔티티 @Index(idx_connections_guardian_status) 선언과 일치시킴)
--    사용 쿼리: findByGuardianIdAndStatus, findByGuardianIdAndStatusIn
CREATE INDEX IF NOT EXISTS idx_connections_guardian_status
    ON connections (guardian_id, status);

-- 2. connections: 피보호자의 보호자 목록 (상태 + 우선순위) 페이징 쿼리용
--    사용 쿼리: findByWardIdAndStatusOrderByPriorityAsc
--    필터(ward_id, status) + 정렬(priority)을 한 인덱스로 커버
CREATE INDEX IF NOT EXISTS idx_connections_ward_status_priority
    ON connections (ward_id, status, priority);

-- 3. game_results: 사용자별 최근 게임 결과 페이징 쿼리용
--    (엔티티 @Index(idx_game_results_user_played) 선언과 일치시킴)
--    사용 쿼리: findByUserIdOrderByPlayedAtDesc, findRecentByUserId, findByFilters
CREATE INDEX IF NOT EXISTS idx_game_results_user_played
    ON game_results (user_id, played_at DESC);

-- 4. refresh_tokens: 만료 토큰 정리 스케줄러용
--    사용 쿼리: TokenCleanupScheduler.deleteByExpiresAtBefore (매일 새벽 3시)
--    기존엔 expires_at 인덱스 없어서 풀스캔 발생
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at
    ON refresh_tokens (expires_at);

-- 5. access_logs: action + 시간 범위 집계 쿼리용
--    사용 쿼리: countByActionAndCreatedAtAfter ("오늘 로그인 수" 등)
CREATE INDEX IF NOT EXISTS idx_access_logs_action_created_at
    ON access_logs (action, created_at);

-- 6. hospital_reservations.user_id: VARCHAR(36) → VARCHAR(6)
--    V7에서 users.id를 VARCHAR(6)으로 축소했으나 이 테이블은 누락됨
--    FK가 users(id)를 참조하므로 기존 데이터는 이미 6자 이하 보장
ALTER TABLE hospital_reservations
    ALTER COLUMN user_id TYPE VARCHAR(6);
