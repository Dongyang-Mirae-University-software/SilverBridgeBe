package kr.silverbridge.main.domain.notification.channel;

/**
 * 알림 전송 채널 전략(strategy). 채널 종류별 구현체를 Spring 빈으로 등록하면
 * {@code NotificationDispatcher}가 {@link #getType()} 기준으로 자동 수집·라우팅한다.
 *
 * <p>새 채널 추가 = 이 인터페이스를 구현한 {@code @Component} 하나를 추가하는 것으로 끝난다
 * (디스패처/설정 코드 수정 불필요). 2단계 카카오 알림톡, 3단계 이메일이 이 방식으로 확장된다.</p>
 *
 * <p>구현체의 {@link #send}는 발송 실패를 예외로 던질 수 있다 — 디스패처가 채널별로 try/catch 하여
 * 한 채널 실패가 다른 채널 발송을 막지 않도록 격리한다.</p>
 */
public interface NotificationChannel {

    /** 이 구현체가 담당하는 채널 종류. */
    NotificationChannelType getType();

    /**
     * 수신자에게 알림을 발송한다. 발송에 필요한 식별자가 없으면(예: SMS인데 전화번호 null)
     * 구현체가 발송을 건너뛰고 {@code false}를 반환한다.
     *
     * @return 실제 전달이 이루어졌으면 {@code true}. 필수 알림의 결과 기반 SMS 폴백 판단에
     *         사용한다(M-S2-1: 토큰이 있어도 전부 만료면 false → 디스패처가 폴백).
     *         선택 알림 경로에서는 반환값을 사용하지 않는다.
     */
    boolean send(NotificationRecipient recipient, NotificationContent content);
}
