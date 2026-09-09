-- users.status_reason 추가 - 관리자가 계정을 이용 제한한 사유 (2026-09-09)
--
-- 왜 감사 로그만으로는 부족한가: 정지 사유를 admin_audit_log.detail에만 남기면 아무도 볼 수 없다.
-- 관리자 감사 로그 "조회" API는 2026-06-11에 제거돼 지금은 쓰기 경로만 남아 있어서,
-- DB를 직접 열지 않으면 "이 계정이 왜 잠겼는지"에 답할 수 없다.
-- 정지는 "본인 확인이 될 때까지의 임시 조치"라 해제 판단을 하려면 사유가 화면에 보여야 한다.
--
-- 값의 수명: 이용 제한으로 바꿀 때 채우고, 이용 중으로 되돌리면 NULL로 지운다.
-- 즉 이 컬럼은 "지금 왜 잠겨 있는가"만 답하며, 변경 이력은 admin_audit_log가 계속 담당한다.
--
-- 안전한 이유: NULL 허용 컬럼 추가라 기존 행에 영향이 없다. 백필 없음(정지 계정이 0건이다).

ALTER TABLE users ADD COLUMN IF NOT EXISTS status_reason VARCHAR(200) NULL;

COMMENT ON COLUMN users.status_reason IS '이용 제한 사유. RESTRICTED일 때만 채워지고 해제 시 NULL로 지운다.';
