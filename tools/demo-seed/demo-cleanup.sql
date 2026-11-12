-- 관리자 대시보드 데모 데이터 삭제 (표식: 이메일 @demo.silverbridge.test, 세션 ward_DemoSeed*, 임시저장 제목 [데모])
BEGIN;
CREATE TEMP TABLE demo_ids ON COMMIT DROP AS
  SELECT id FROM users WHERE email LIKE '%@demo.silverbridge.test';
DELETE FROM notification_log WHERE recipient_id IN (SELECT id FROM demo_ids);
DELETE FROM anomaly_incident_feedback WHERE guardian_id IN (SELECT id FROM demo_ids);
DELETE FROM anomaly_event WHERE ward_id IN (SELECT id FROM demo_ids);
DELETE FROM anomaly_incident WHERE ward_id IN (SELECT id FROM demo_ids);
DELETE FROM companion_notice_log WHERE ward_id IN (SELECT id FROM demo_ids);
DELETE FROM companion_session WHERE ward_id IN (SELECT id FROM demo_ids);
DELETE FROM companion_prompt_log WHERE ward_id IN (SELECT id FROM demo_ids);
DELETE FROM inquiry WHERE user_id IN (SELECT id FROM demo_ids);
DELETE FROM connection WHERE guardian_id IN (SELECT id FROM demo_ids) OR ward_id IN (SELECT id FROM demo_ids);
DELETE FROM guardian_anomaly_setting WHERE guardian_id IN (SELECT id FROM demo_ids);
DELETE FROM announcement_draft WHERE title LIKE '[데모] %';
DELETE FROM users WHERE id IN (SELECT id FROM demo_ids);
COMMIT;
