package kr.silverbridge.main.domain.notification.channel;

import java.util.Objects;

/**
 * 채널 1건 발송 결과.
 *
 * <ul>
 *   <li>{@link Status#DELIVERED} - 발송 서버가 접수했다(기기 표시·통신사 도달까지는 모른다).</li>
 *   <li>{@link Status#FAILED} - 시도했으나 실패. {@code reason}이 반드시 있다.</li>
 *   <li>{@link Status#NOT_APPLICABLE} - 이 알림은 원래 이 채널 대상이 아니다(알림톡 템플릿 미매핑·미구현 채널).
 *       실패가 아니므로 이력 화면에 표시하지 않는다.</li>
 * </ul>
 */
public record ChannelResult(Status status, ChannelFailureReason reason) {

    public enum Status {
        DELIVERED,
        FAILED,
        NOT_APPLICABLE
    }

    private static final ChannelResult DELIVERED = new ChannelResult(Status.DELIVERED, null);
    private static final ChannelResult NOT_APPLICABLE = new ChannelResult(Status.NOT_APPLICABLE, null);

    public ChannelResult {
        Objects.requireNonNull(status, "status");
        if ((status == Status.FAILED) != (reason != null)) {
            throw new IllegalArgumentException("실패일 때만 사유가 있어야 한다: status=" + status + ", reason=" + reason);
        }
    }

    public static ChannelResult delivered() {
        return DELIVERED;
    }

    public static ChannelResult failed(ChannelFailureReason reason) {
        return new ChannelResult(Status.FAILED, Objects.requireNonNull(reason, "reason"));
    }

    public static ChannelResult notApplicable() {
        return NOT_APPLICABLE;
    }

    public boolean isDelivered() {
        return status == Status.DELIVERED;
    }
}
