-- V51: 조회 경로에 인덱스가 없던 두 곳 보강 (2026-09-11 기술 점검 D-1·D-2). 비가역 아님.
--
-- fcm_token.updated_at   : FcmTokenCleanupScheduler(매일 04:00)가 60일 미갱신 토큰을 지울 때 풀스캔하던 컬럼
-- anomaly_incident.started_at : 관리자 대시보드가 "오늘 시작한 상황"을 셀 때 쓰는 컬럼.
--   기존 복합 인덱스는 선두가 ward_id·review_status라 시각 범위 단독 조회에는 쓰이지 않았다.
CREATE INDEX IF NOT EXISTS idx_fcm_token_updated_at        ON fcm_token (updated_at);
CREATE INDEX IF NOT EXISTS idx_anomaly_incident_started_at ON anomaly_incident (started_at DESC);
