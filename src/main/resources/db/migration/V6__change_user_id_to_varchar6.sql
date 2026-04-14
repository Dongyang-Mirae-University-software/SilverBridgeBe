-- users.id 및 참조 컬럼을 VARCHAR(36) → VARCHAR(6)으로 변경
-- FK 제약 조건 순서: 참조 컬럼 먼저 변경 후 PK 변경

-- refresh_tokens
ALTER TABLE refresh_tokens ALTER COLUMN user_id TYPE VARCHAR(6);

-- access_logs
ALTER TABLE access_logs ALTER COLUMN user_id TYPE VARCHAR(6);

-- connections
ALTER TABLE connections ALTER COLUMN guardian_id  TYPE VARCHAR(6);
ALTER TABLE connections ALTER COLUMN ward_id      TYPE VARCHAR(6);
ALTER TABLE connections ALTER COLUMN initiated_by TYPE VARCHAR(6);

-- anomaly_events
ALTER TABLE anomaly_events ALTER COLUMN ward_id TYPE VARCHAR(6);

-- game_results
ALTER TABLE game_results ALTER COLUMN user_id TYPE VARCHAR(6);

-- hospital_reservations
ALTER TABLE hospital_reservations ALTER COLUMN user_id TYPE VARCHAR(6);

-- announcements
ALTER TABLE announcements ALTER COLUMN author_id TYPE VARCHAR(6);

-- admin_audit_logs
ALTER TABLE admin_audit_logs ALTER COLUMN admin_id TYPE VARCHAR(6);

-- users (PK 마지막에 변경)
ALTER TABLE users ALTER COLUMN id TYPE VARCHAR(6);
