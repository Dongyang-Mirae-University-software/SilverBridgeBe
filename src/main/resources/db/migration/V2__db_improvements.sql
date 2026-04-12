-- =============================================
-- V2: DB 개선사항 적용
-- =============================================

-- 1. users.phone UNIQUE 제약 추가
--    기존 비고유 인덱스 제거 후 UNIQUE partial 인덱스로 교체
--    NULL 허용 (카카오 가입 시 phone 없는 경우 대비)
DROP INDEX IF EXISTS idx_users_phone;
CREATE UNIQUE INDEX uq_users_phone ON users(phone) WHERE phone IS NOT NULL;

-- 2. anomaly_events 복합 인덱스 추가
--    특정 피보호자의 날짜 범위 이상감지 조회 최적화
CREATE INDEX idx_anomaly_events_ward_detected ON anomaly_events(ward_id, detected_at);

-- 3. game_results 복합 인덱스 추가
--    특정 사용자의 게임 유형별 조회 최적화
CREATE INDEX idx_game_results_user_type ON game_results(user_id, game_type);

-- 4. connections.initiated_by FK 정책 변경
--    CASCADE → SET NULL: 요청자 탈퇴 시 연결 이력 보존
ALTER TABLE connections DROP CONSTRAINT fk_connections_init;
ALTER TABLE connections ALTER COLUMN initiated_by DROP NOT NULL;
ALTER TABLE connections ADD CONSTRAINT fk_connections_init
    FOREIGN KEY (initiated_by) REFERENCES users(id) ON DELETE SET NULL;
