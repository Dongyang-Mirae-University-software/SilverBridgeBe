-- admin_audit_logs.action CHECK를 AdminAuditAction enum 전수와 동기화 (C-S3-1)
--
-- V1의 chk_admin_audit_action은 ANNOUNCEMENT_DRAFT_* 4종(V14에서 추가된 임시저장 기능의
-- 감사 액션)을 허용하지 않아, 임시저장 생성/수정/삭제/게시 시 감사 로그 insert가
-- CHECK 위반(23514)으로 실패하고 같은 트랜잭션의 본 작업까지 롤백돼 항상 500이 났다.
-- 허용 목록을 코드의 AdminAuditAction enum 전수와 일치시킨다.
-- (V1의 ANNOUNCEMENT_PUBLISH는 enum에 없는 값이라 제거 — 코드가 쓴 적 없어 기존 행 영향 없음)

ALTER TABLE admin_audit_logs DROP CONSTRAINT chk_admin_audit_action;

ALTER TABLE admin_audit_logs ADD CONSTRAINT chk_admin_audit_action CHECK (action IN (
    'USER_STATUS_CHANGE', 'USER_ROLE_CHANGE', 'USER_FORCE_DELETE',
    'FORCE_CONNECT', 'FORCE_DISCONNECT',
    'ANNOUNCEMENT_CREATE', 'ANNOUNCEMENT_UPDATE', 'ANNOUNCEMENT_DELETE',
    'ANNOUNCEMENT_DRAFT_CREATE', 'ANNOUNCEMENT_DRAFT_UPDATE',
    'ANNOUNCEMENT_DRAFT_DELETE', 'ANNOUNCEMENT_DRAFT_PUBLISH'
));
