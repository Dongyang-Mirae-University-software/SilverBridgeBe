package kr.silverbridge.main.domain.notification.channel;

/**
 * 알림 전송 채널 종류.
 *
 * <p>1단계(현재)에서는 {@link #FCM}, {@link #SMS}만 구현체({@link NotificationChannel})가 존재하며,
 * {@link #KAKAO_ALIMTALK}, {@link #EMAIL}은 enum 값만 정의해 둔다(2·3단계에서 구현체 추가).
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
