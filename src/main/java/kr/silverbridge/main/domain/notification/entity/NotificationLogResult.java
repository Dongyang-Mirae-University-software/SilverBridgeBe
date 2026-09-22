package kr.silverbridge.main.domain.notification.entity;

/**
 * 수신자 1명에 대한 알림 발송 최종 결과.
 *
 * <p>{@link #NOT_SENT}는 <b>실패가 아니다</b> - 정지 계정 차단이나 사용자가 채널을 꺼 둔 것은 의도대로
 * 동작한 것이라, 실패 건수에 섞으면 실패율이 부풀어 보인다.</p>
 */
public enum NotificationLogResult {

    /** 한 채널 이상 발송 서버가 접수했다. */
    DELIVERED,
    /** 필수 알림(SOS)의 푸시가 실패해 문자로 대체 발송했고, 문자가 접수됐다. 전달된 것으로 센다. */
    SMS_FALLBACK,
    /** 시도한 채널이 모두 실패했다. */
    FAILED,
    /** 보내지 않았다. 사유는 {@link NotificationNotSentReason}. */
    NOT_SENT
}
