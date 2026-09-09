-- users.status CHECK에 RESTRICTED(이용 제한) 추가 (2026-09-09)
--
-- 왜 새 상태값인가: 관리자 회원관리의 "계정 일시 중지"를 기존 INACTIVE로 표현할 수 없다.
-- INACTIVE는 탈퇴 전용이고, WithdrawnUserPurgeScheduler가 updated_at이 10분 지난
-- INACTIVE 행을 좀비 계정으로 판정해 영구 삭제한다(2026-06-11 INACTIVE 불변식).
-- 정지에 INACTIVE를 재사용하면 정지시킨 계정이 20분 안에 통째로 사라진다.
--
-- 왜 마이그레이션이 필요한가: V4가 chk_users_status를 ('ACTIVE','INACTIVE')로 좁혀 놨다.
-- Status enum에 값만 더하면 UPDATE가 CHECK 위반(23514)으로 실패하고 정지 조작이 500이 난다.
-- admin_audit_log.action에서 이미 한 번 터진 함정과 같다(C-S3-1).
--
-- 안전한 이유: 허용 값을 늘리기만 한다. 기존 행(ACTIVE·INACTIVE)은 전부 새 목록에도 포함된다.
-- 백필 없음 - 기존 회원은 전원 ACTIVE 또는 탈퇴 중인 INACTIVE이며, RESTRICTED는 관리자 조작으로만 생긴다.

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_status;

ALTER TABLE users ADD CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'RESTRICTED', 'INACTIVE'));
