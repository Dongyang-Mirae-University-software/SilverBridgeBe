-- 이메일 알림 채널 도입(2026-10-13): 발송 구현체가 없던 때 설정 API가 EMAIL 값을 저장해 왔다.
-- 구현체가 생기면 그때 켜 둔 값이 의도 없이 발송으로 이어지므로, 기존 EMAIL 설정을 모두 OFF로 되돌려
-- "명시적으로 켠 사용자만 받는다"는 기본 정책(결정 D)을 지킨다. 비가역(이전 값은 보관하지 않는다).
UPDATE user_notification_setting
   SET enabled = false,
       updated_at = now()
 WHERE channel_type = 'EMAIL'
   AND enabled = true;
