package kr.silverbridge.main.domain.notification.dispatch;

import kr.silverbridge.main.domain.notification.channel.NotificationChannel;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.channel.NotificationRecipient;
import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.domain.notification.service.NotificationSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 알림 라우터. 이벤트가 만든 {@link NotificationContent}를 사용자 설정에 따라 활성 채널로만 발송한다.
 *
 * <p>핵심 동작:</p>
 * <ol>
 *   <li><b>선택 알림</b>({@link NotificationType#isMandatory()}==false) → 사용자 설정({@link NotificationSettingService})의 활성 채널로만 발송.</li>
 *   <li><b>필수 알림</b> → 사용자 설정을 무시하고 {@link #MANDATORY_CHANNELS}로 강제 발송.</li>
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
    /** 필수 알림에서 푸시가 닿지 않을 때(FCM 토큰 없음)만 쓰는 폴백 채널. */
    private static final NotificationChannelType MANDATORY_FALLBACK = NotificationChannelType.SMS;

    private final Map<NotificationChannelType, NotificationChannel> channels;
    private final NotificationSettingService settingService;
    private final NotificationRecipientResolver recipientResolver;
    private final FcmService fcmService;

    public NotificationDispatcher(List<NotificationChannel> channelBeans,
                                  NotificationSettingService settingService,
                                  NotificationRecipientResolver recipientResolver,
                                  FcmService fcmService) {
        this.channels = new EnumMap<>(NotificationChannelType.class);
        for (NotificationChannel channel : channelBeans) {
            this.channels.put(channel.getType(), channel);
        }
        this.settingService = settingService;
        this.recipientResolver = recipientResolver;
        this.fcmService = fcmService;
    }

    /**
     * 한 사용자에게 알림을 라우팅·발송한다.
     *
     * @param userId  수신자 ID
     * @param type    알림 종류(필수/선택 분류 포함)
     * @param content 발송할 제목/본문/부가데이터
     */
    public void dispatch(String userId, NotificationType type, NotificationContent content) {
        Set<NotificationChannelType> targets = type.isMandatory()
                ? mandatoryTargets(userId)
                : settingService.enabledChannels(userId);

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
                // 한 채널 실패가 다른 채널 발송을 막지 않도록 격리
                log.error("채널 발송 실패: userId={}, channel={}, error={}", userId, channelType, e.getMessage());
            }
        }
    }

    /**
     * 필수 알림(WARD_SOS 등)의 발송 채널을 결정한다. 항상 FCM으로 강제 발송하되, <b>FCM 토큰이 없으면</b>
     * (앱 미설치·로그아웃·토큰 만료) 푸시가 닿지 않으므로 SMS로 폴백한다. 토큰이 있으면 SMS 비용을 아끼고
     * 푸시만 보낸다(SMS는 "FCM이 닿지 않는 보호자"만 대상으로 하는 폴백).
     */
    private Set<NotificationChannelType> mandatoryTargets(String userId) {
        EnumSet<NotificationChannelType> targets = EnumSet.of(MANDATORY_PRIMARY);
        if (!fcmService.hasToken(userId)) {
            targets.add(MANDATORY_FALLBACK);
        }
        return targets;
    }
}