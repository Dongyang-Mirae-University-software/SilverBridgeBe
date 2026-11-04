-- 사용자 알림 기록(종 아이콘) - 읽음 표시 (2026-11-04)
--
-- 왜 필요한가: 보호자·피보호자가 그동안 받은 알림을 웹에서 다시 보고, 안 읽은 수를 뱃지로 본다.
-- 발송 기록은 이미 notification_log에 수신자 1명당 1행으로 남으므로 같은 행에 읽은 시각만 더한다
-- (별도 테이블을 두면 보관 정리·탈퇴 CASCADE를 따로 맞춰야 하고 행 수가 늘어난다).
--
-- 사용자에게 보이는 행 = result <> 'NOT_SENT' (실제로 보낸 알림만, 2026-11-04 사용자 결정).
-- 칼럼·인덱스 추가와 기존 행 읽음 표시뿐이라 되돌릴 수 있다(칼럼을 지우면 원상태).

ALTER TABLE notification_log ADD COLUMN read_at TIMESTAMPTZ;

-- 배포 전에 이미 쌓인 알림(최대 90일치)은 읽은 것으로 둔다 - 그대로 두면 배포 직후 뱃지가 수백 건이 된다.
-- 목록에는 그대로 보인다.
UPDATE notification_log SET read_at = created_at WHERE read_at IS NULL;

-- 내 알림 목록(최신순). 부분 인덱스 조건은 쿼리의 result <> 'NOT_SENT' 리터럴과 같아야 쓰인다.
CREATE INDEX idx_notification_log_recipient_visible
    ON notification_log (recipient_id, created_at DESC)
    WHERE result <> 'NOT_SENT';

-- 안 읽은 수(자주 불린다) - 안 읽은 행만 담아 작게 유지한다.
CREATE INDEX idx_notification_log_recipient_unread
    ON notification_log (recipient_id, created_at)
    WHERE read_at IS NULL AND result <> 'NOT_SENT';
