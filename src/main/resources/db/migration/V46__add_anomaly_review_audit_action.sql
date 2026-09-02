-- admin_audit_log.action CHECK에 이상감지 판정 정정(ANOMALY_REVIEW_RESOLVE) 추가 (2026-09-02)
--
-- 왜 마이그레이션이 필요한가: AdminAuditAction enum에 값을 더하는 것만으로는 부족하다.
-- action 컬럼에 CHECK 제약이 걸려 있어, 허용 목록에 없는 값을 넣으면 insert가 실패하고
-- 같은 트랜잭션의 본 작업(판정 정정)까지 롤백돼 500이 난다.
--
-- 이 함정은 이미 한 번 터졌다: V1의 CHECK가 V14에서 추가된 ANNOUNCEMENT_DRAFT_* 4종을
-- 허용하지 않아 공지 임시저장 기능 전체가 CHECK 위반(23514)으로 죽었고, V27에서 고쳤다(C-S3-1).
-- 그 재발을 막으려고 AdminAuditActionCheckSyncTest가 enum 전수와 이 CHECK를 대조한다 -
-- enum에 값을 더하면서 이 파일을 빼먹으면 테스트가 먼저 실패한다.
--
-- 목록은 V27(enum 전수와 맞춘 판)을 기준으로 삼고 새 값 하나만 더한다. V1에 있던
-- ANNOUNCEMENT_PUBLISH는 enum에 없어 V27이 이미 뺐으므로 되살리지 않는다.
--
-- 안전한 이유: 허용 값을 늘리기만 한다. 기존 행은 전부 새 목록에도 포함되므로 검증에 걸리지 않는다.

ALTER TABLE admin_audit_log DROP CONSTRAINT chk_admin_audit_action;

ALTER TABLE admin_audit_log ADD CONSTRAINT chk_admin_audit_action CHECK (action IN (
    'USER_STATUS_CHANGE', 'USER_ROLE_CHANGE', 'USER_FORCE_DELETE',
    'FORCE_CONNECT', 'FORCE_DISCONNECT',
    'ANNOUNCEMENT_CREATE', 'ANNOUNCEMENT_UPDATE', 'ANNOUNCEMENT_DELETE',
    'ANNOUNCEMENT_DRAFT_CREATE', 'ANNOUNCEMENT_DRAFT_UPDATE',
    'ANNOUNCEMENT_DRAFT_DELETE', 'ANNOUNCEMENT_DRAFT_PUBLISH',
    'ANOMALY_REVIEW_RESOLVE'
));
