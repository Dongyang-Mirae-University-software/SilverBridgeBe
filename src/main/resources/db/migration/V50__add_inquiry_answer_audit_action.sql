-- V50: admin_audit_log.action CHECK 재정의 - INQUIRY_ANSWER 추가
--
-- 문의 답변은 보호자 개인 문의를 열어 답하는 쓰기 조작인데 감사 로그를 남기지 않았다
-- (2026-09-10 횡단 점검 A-2). "개인 이력을 변경하는 관리자 API는 반드시 남긴다" 규칙에 맞춘다.
--
-- ⚠️ enum(AdminAuditAction)에 값만 더하면 insert가 CHECK 위반으로 실패하고 같은 트랜잭션의
--    답변까지 롤백돼 500이 난다(C-S3-1·V46·V48과 같은 함정). AdminAuditActionCheckSyncTest가 대조한다.
ALTER TABLE admin_audit_log DROP CONSTRAINT chk_admin_audit_action;
ALTER TABLE admin_audit_log ADD CONSTRAINT chk_admin_audit_action CHECK (action IN (
    'USER_STATUS_CHANGE', 'USER_ROLE_CHANGE', 'USER_NAME_CHANGE', 'USER_FORCE_DELETE',
    'FORCE_CONNECT', 'FORCE_DISCONNECT',
    'ANNOUNCEMENT_CREATE', 'ANNOUNCEMENT_UPDATE', 'ANNOUNCEMENT_DELETE',
    'ANNOUNCEMENT_DRAFT_CREATE', 'ANNOUNCEMENT_DRAFT_UPDATE',
    'ANNOUNCEMENT_DRAFT_DELETE', 'ANNOUNCEMENT_DRAFT_PUBLISH',
    'ANOMALY_REVIEW_RESOLVE',
    'INQUIRY_ANSWER'
));
