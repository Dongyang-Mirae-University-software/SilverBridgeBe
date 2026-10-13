package kr.silverbridge.main.domain.notification.channel;

/**
 * 알림 전송 채널 종류.
 *
 * <p>구현체({@link NotificationChannel})는 {@link #FCM}·{@link #SMS}·{@link #KAKAO_ALIMTALK}·{@link #EMAIL}
 * 모두 있다. 알림톡·이메일은 운영 스위치가 꺼져 있으면 빈이 만들어지지 않는다.
 * 구현체가 없는 채널은 사용자가 켜더라도 {@code NotificationDispatcher}가 조용히 건너뛴다.</p>
 *
 * <p>WebSocket 실시간 전송은 의도적으로 이 추상화에 포함하지 않는다 — 온라인 사용자 실시간 동기화는
 * 사용자 설정과 무관하게 항상 발송되어야 하므로 리스너에서 직접 처리한다.</p>
 */
public enum NotificationChannelType {
    FCM,
    SMS,
    KAKAO_ALIMTALK,
    EMAIL
}
