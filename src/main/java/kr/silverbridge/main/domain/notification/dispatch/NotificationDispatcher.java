package kr.silverbridge.main.domain.notification.dispatch;

import kr.silverbridge.main.domain.notification.channel.NotificationChannel;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.channel.NotificationRecipient;
import kr.silverbridge.main.domain.notification.service.NotificationSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 알림 라우터. 이벤트가 만든 {@link NotificationContent}를 사용자 설정에 따라 활성 채널로만 발송한다.
 *
 * <p>핵심 동작:</p>
 * <ol>
 *   <li><b>선택 알림</b>({@link NotificationType#isMandatory()}==false) → 사용자 설정({@link NotificationSettingService})의 활성 채널로만 발송.</li>
 *   <li><b>필수 알림</b> → 사용자 설정을 무시하고 FCM 강제 발송, <b>실제 전달 실패 시</b> SMS 폴백(결과 기반, M-S2-1).</li>
 *   <li><b>채널별 실패 격리</b> → 각 채널 발송을 try/catch로 감싸 한 채널 실패가 다른 채널을 막지 않는다.</li>
 *   <li><b>미구현 채널 무시</b> → enabled여도 구현체(빈)가 없으면(KAKAO_ALIMTALK/EMAIL) 조용히 건너뛴다.</li>
 * </ol>
 *
 * <p>구현체는 {@code List<NotificationChannel>} 생성자 주입으로 자동 수집된다 — 새 채널 빈을 추가하면
 * 별도 등록 없이 라우팅 대상이 된다(전략 패턴).</p>
 */
@Slf4j
@Component
public class NotificationDispatcher {

    /** 필수 알림의 기본 강제 채널(푸시). 사용자 설정과 무관하게 항상 발송한다. */
    private static final NotificationChannelType MANDATORY_PRIMARY = NotificationChannelType.FCM;
    /** 필수 알림에서 푸시 전달이 실패했을 때만 쓰는 폴백 채널. */
    private static final NotificationChannelType MANDATORY_FALLBACK = NotificationChannelType.SMS;

    private final Map<NotificationChannelType, NotificationChannel> channels;
    private final NotificationSettingService settingService;
    private final NotificationRecipientResolver recipientResolver;

    public NotificationDispatcher(List<NotificationChannel> channelBeans,
                                  NotificationSettingService settingService,
                                  NotificationRecipientResolver recipientResolver) {
        this.channels = new EnumMap<>(NotificationChannelType.class);
        for (NotificationChannel channel : channelBeans) {
            this.channels.put(channel.getType(), channel);
        }
        this.settingService = settingService;
        this.recipientResolver = recipientResolver;
    }

    /**
     * 한 사용자에게 알림을 라우팅·발송한다.
     *
     * @param userId  수신자 ID
     * @param type    알림 종류(필수/선택 분류 포함)
     * @param content 발송할 제목/본문/부가데이터
     */
    public void dispatch(String userId, NotificationType type, NotificationContent content) {
        if (type.isMandatory()) {
            dispatchMandatory(userId, content);
            return;
        }

        Set<NotificationChannelType> targets = settingService.enabledChannels(userId);
        if (targets.isEmpty()) {
            log.debug("발송할 활성 채널 없음: userId={}, type={}", userId, type);
            return;
        }

        NotificationRecipient recipient = recipientResolver.resolve(userId);

        for (NotificationChannelType channelType : targets) {
            NotificationChannel channel = channels.get(channelType);
            if (channel == null) {
                // KAKAO_ALIMTALK / EMAIL 등 미구현 채널: 설정상 켜져 있어도 발송 수단이 없음
                log.debug("미구현 채널 건너뜀: userId={}, channel={}", userId, channelType);
                continue;
            }
            try {
                channel.send(recipient, content);
            } catch (Exception e) {
                // 한 채널 실패가 다른 채널 발송을 막지 않도록 격리. 구조 결함 진단을 위해 스택 포함 (L-S2-6)
                log.error("채널 발송 실패: userId={}, channel={}", userId, channelType, e);
            }
        }
    }

    /**
     * 필수 알림(WARD_SOS 등) — 사용자 설정을 무시하고 FCM 강제 발송, <b>전달 결과 기반</b> SMS 폴백 (M-S2-1).
     * <p>
     * 기존 "토큰 존재 여부" 기반 폴백은 토큰이 DB에 있으나 전부 만료(앱 삭제 등)인 보호자에게
     * 푸시·SMS 모두 미발송되는 갭이 있었다. 토큰 없음·전 토큰 만료·발송 예외를 모두
     * "전달 실패"로 수렴시켜 SMS 폴백한다. 전달 성공 시엔 SMS 비용을 아낀다.
     */
    private void dispatchMandatory(String userId, NotificationContent content) {
        NotificationRecipient recipient = recipientResolver.resolve(userId);

        boolean delivered = false;
        NotificationChannel primary = channels.get(MANDATORY_PRIMARY);
        if (primary != null) {
            try {
                delivered = primary.send(recipient, content);
            } catch (Exception e) {
                log.error("필수 알림 FCM 발송 실패 — SMS 폴백 진행: userId={}", userId, e);
            }
        }
        if (delivered) {
            return;
        }

        NotificationChannel fallback = channels.get(MANDATORY_FALLBACK);
        if (fallback == null) {
            log.warn("필수 알림 폴백 채널(SMS) 미구현 — 발송 불가: userId={}", userId);
            return;
        }
        try {
            boolean sent = fallback.send(recipient, content);
            log.info("필수 알림 SMS 폴백 {}: userId={}", sent ? "발송" : "건너뜀(전화번호 없음)", userId);
        } catch (Exception e) {
            log.error("필수 알림 SMS 폴백 발송 실패: userId={}", userId, e);
        }
    }
}