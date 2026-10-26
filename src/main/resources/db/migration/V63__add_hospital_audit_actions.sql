-- V63: admin_audit_log.action CHECK 재정의 - 제휴 병원 관리 7종 추가 (2026-10-26)
--
-- 관리자 제휴 병원 관리(예약 서비스 중계)의 등록·수정·삭제·상태·진료과 변경을 감사 로그에 남긴다.
-- 병원 데이터는 예약 서비스 DB에 있고 BE DB에는 감사 로그만 쌓인다(조회는 남기지 않는다).
--
-- ⚠️ enum(AdminAuditAction)에 값만 더하면 insert가 CHECK 위반으로 실패한다(V46·V48·V50과 같은 함정).
--    AdminAuditActionCheckSyncTest가 대조한다. 기존 값은 그대로 두고 더하기만 하므로 기존 행과 충돌하지 않는다.
ALTER TABLE admin_audit_log DROP CONSTRAINT chk_admin_audit_action;
ALTER TABLE admin_audit_log ADD CONSTRAINT chk_admin_audit_action CHECK (action IN (
    'USER_STATUS_CHANGE', 'USER_ROLE_CHANGE', 'USER_NAME_CHANGE', 'USER_FORCE_DELETE',
    'FORCE_CONNECT', 'FORCE_DISCONNECT',
    'ANNOUNCEMENT_CREATE', 'ANNOUNCEMENT_UPDATE', 'ANNOUNCEMENT_DELETE',
    'ANNOUNCEMENT_DRAFT_CREATE', 'ANNOUNCEMENT_DRAFT_UPDATE',
    'ANNOUNCEMENT_DRAFT_DELETE', 'ANNOUNCEMENT_DRAFT_PUBLISH',
    'ANOMALY_REVIEW_RESOLVE',
    'INQUIRY_ANSWER',
    'HOSPITAL_CREATE', 'HOSPITAL_UPDATE', 'HOSPITAL_DELETE', 'HOSPITAL_STATUS_CHANGE',
    'HOSPITAL_DEPARTMENT_ADD', 'HOSPITAL_DEPARTMENT_UPDATE', 'HOSPITAL_DEPARTMENT_DELETE'
));
