-- admin_audit_log.action CHECK에 회원 이름 수정(USER_NAME_CHANGE) 추가 (2026-09-09)
--
-- 왜 필요한가: AdminAuditAction enum에 값을 더하는 것만으로는 부족하다. action 컬럼의 CHECK가
-- 허용하지 않는 값을 넣으면 insert가 실패하고, 같은 트랜잭션의 본 작업(회원 정보 수정)까지
-- 롤백돼 500이 난다. V1↔V14의 C-S3-1, V46과 같은 함정이며 AdminAuditActionCheckSyncTest가 막는다.
--
-- 목록은 V46(가장 최근에 enum 전수와 맞춘 판)을 기준으로 새 값 하나만 더한다.
--
-- 안전한 이유: 허용 값을 늘리기만 한다. 기존 행은 전부 새 목록에도 포함된다.

ALTER TABLE admin_audit_log DROP CONSTRAINT chk_admin_audit_action;

ALTER TABLE admin_audit_log ADD CONSTRAINT chk_admin_audit_action CHECK (action IN (
    'USER_STATUS_CHANGE', 'USER_ROLE_CHANGE', 'USER_NAME_CHANGE', 'USER_FORCE_DELETE',
    'FORCE_CONNECT', 'FORCE_DISCONNECT',
    'ANNOUNCEMENT_CREATE', 'ANNOUNCEMENT_UPDATE', 'ANNOUNCEMENT_DELETE',
    'ANNOUNCEMENT_DRAFT_CREATE', 'ANNOUNCEMENT_DRAFT_UPDATE',
    'ANNOUNCEMENT_DRAFT_DELETE', 'ANNOUNCEMENT_DRAFT_PUBLISH',
    'ANOMALY_REVIEW_RESOLVE'
));
