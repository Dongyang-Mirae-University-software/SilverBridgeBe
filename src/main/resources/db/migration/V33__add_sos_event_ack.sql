-- 보호자의 SOS 처리 결과(ACK) 기록 컬럼. 보호자가 SOS 이력을 확인하고 "안전 확인" 또는 "응급 출동"으로
-- 처리 결과를 남긴다.
--
-- 이력 행 하나당 ACK 하나다 — 보호자가 여러 명이어도 "그 SOS가 어떻게 처리됐는지"는 공유된 하나의 사실이라
-- 별도 테이블(보호자별 ACK) 없이 sos_event 에 직접 붙인다. 마지막에 처리한 보호자·시각으로 덮어써진다.
--
-- 미처리 건은 네 컬럼이 모두 NULL 이다 → 기존 이력 백필 불필요.
-- ack_by 는 회원 탈퇴(hard delete) 시 ON DELETE SET NULL — ward_id·access_log 와 동일한 익명 보존 정책.
--
-- 조회는 "ACTIVE 연결된 피보호자들의 최근 이력"(ward_id IN (...) ORDER BY created_at DESC)이라
-- 기존 idx_sos_events_ward_created (ward_id, created_at DESC) 를 그대로 타므로 신규 인덱스는 두지 않는다.
--
-- ⚠️ ACK 는 기록일 뿐 알림 발송 조건에 개입하지 않는다. SOS 보호자 알림은 WARD_SOS(필수 알림)로 사용자
--    설정과 무관하게 항상 발송되며, ACK 여부·SOS 동작 설정으로 억제되지 않는다(2026-07-23 정책).
ALTER TABLE sos_event
    ADD COLUMN ack_status VARCHAR(30)  NULL,   -- SAFE_CONFIRMED / EMERGENCY_DISPATCHED (NULL = 미처리)
    ADD COLUMN ack_by     VARCHAR(6)   NULL,   -- 처리한 보호자 (탈퇴 시 NULL)
    ADD COLUMN ack_at     TIMESTAMPTZ  NULL,
    ADD COLUMN ack_note   VARCHAR(200) NULL,   -- 처리 메모 (선택, 예 "통화 연결 · 안전 확인")
    ADD CONSTRAINT fk_sos_event_ack_by FOREIGN KEY (ack_by) REFERENCES users (id) ON DELETE SET NULL;
