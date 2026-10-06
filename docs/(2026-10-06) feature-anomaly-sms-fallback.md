# 이상감지 알림 문자 대체 (2026-10-06)

- 결정: 푸시(FCM)가 전달되지 않고 알림톡·사용자가 켠 문자로도 전달되지 않았으면 문자로 대신 보낸다. 대상은 `ANOMALY_DETECTED`(보호자)·`ANOMALY_DETECTED_SELF`(본인)이며 재촉·동수 안내는 제외.
- 구현: `NotificationDispatcher.dispatchForcedPushPlusSettings` → 공용 `smsFallback`(SOS와 같은 상한·환불·RATE_LIMITED 기록). 문구는 `NotificationContent.smsFallbackText`.
- 상한: `SmsFallbackLimiter` 키 (종류, 수신자)라 SOS와 따로 센다. 한도 값은 같은 `notification.sms-fallback.max-per-hour`.
- 정책 상세: `.claude/rules/domain-security-policy.md` "이상감지 알림 - 푸시가 안 가면 문자로 대신 보낸다".
- 테스트: `NotificationDispatcherTest`(토큰 없음→문자, 푸시 성공→없음, 켠 문자로 전달→중복 없음, 켠 문자 실패→재시도 없음, 상한 초과→기록, 본인, 재촉 제외), `AnomalyNotificationListenerTest`(문구).
