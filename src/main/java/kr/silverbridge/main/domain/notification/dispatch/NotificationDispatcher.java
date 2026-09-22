package kr.silverbridge.main.domain.notification.dispatch;

import kr.silverbridge.main.domain.notification.channel.ChannelFailureReason;
import kr.silverbridge.main.domain.notification.channel.ChannelResult;
import kr.silverbridge.main.domain.notification.channel.NotificationChannel;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.channel.NotificationRecipient;
import kr.silverbridge.main.domain.notification.entity.ChannelAttempt;
import kr.silverbridge.main.domain.notification.entity.NotificationLog;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
import kr.silverbridge.main.domain.notification.entity.NotificationNotSentReason;
import kr.silverbridge.main.domain.notification.service.NotificationLogService;
import kr.silverbridge.main.domain.notification.service.NotificationSettingService;
import kr.silverbridge.main.global.enums.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 알림 라우터. 이벤트가 만든 {@link NotificationContent}를 사용자 설정에 따라 활성 채널로만 발송한다.
 *
 * <p>발송 대상 채널은 {@link NotificationType#policy()}가 정한다:</p>
 * <ol>
 *   <li>{@link NotificationType.Policy#SETTINGS_ONLY} → 사용자 설정({@link NotificationSettingService})의 활성 채널로만 발송.</li>
 *   <li>{@link NotificationType.Policy#FORCED_PUSH_WITH_SMS_FALLBACK} → 설정 무시 FCM 강제 발송, <b>실제 전달 실패 시</b> SMS 폴백(결과 기반, M-S2-1).</li>
 *   <li>{@link NotificationType.Policy#FORCED_PUSH_PLUS_SETTINGS} → FCM은 항상 + 나머지 채널은 설정대로. <b>SMS 폴백 없음</b>(이상감지).</li>
 * </ol>
 *
 * <p><b>이용 제한·탈퇴 진행 계정에는 어떤 채널로도 보내지 않는다</b>(강제 FCM 포함) —
 * {@code withReceivableRecipient()} 참조. 수신자 기준이며, 정지된 사람이 <i>원인</i>인 알림은 그대로 나간다.</p>
 *
 * <p>공통: <b>채널별 실패 격리</b>(한 채널 실패가 다른 채널을 막지 않음), <b>미구현 채널 무시</b>
 * (enabled여도 구현체 빈이 없거나 설정이 꺼져 있으면 — EMAIL·알림톡 템플릿 미승인 — 조용히 건너뜀).</p>
 *
 * <p><b>발송 결과는 수신자 1명당 1행으로 {@code notification_log}에 남는다</b>(관리자 알림 이력, 2026-09-22).
 * 기록은 발송이 끝난 뒤 한 번만 하며, 기록이 실패해도 발송에는 영향이 없다({@code [NOTIFY-LOG-FAILED]} WARN).</p>
 *
 * <p>구현체는 {@code List<NotificationChannel>} 생성자 주입으로 자동 수집된다 — 새 채널 빈을 추가하면
 * 별도 등록 없이 라우팅 대상이 된다(전략 패턴).</p>
 */
@Slf4j
@Component
public class NotificationDispatcher {

    /** 강제 발송 채널(푸시). 두 강제 정책 모두 사용자 설정과 무관하게 이 채널로 발송한다. */
    private static final NotificationChannelType FORCED_PUSH = NotificationChannelType.FCM;
    /** {@code FORCED_PUSH_WITH_SMS_FALLBACK}에서 푸시 전달이 실패했을 때만 쓰는 폴백 채널. */
    private static final NotificationChannelType MANDATORY_FALLBACK = NotificationChannelType.SMS;

    private final Map<NotificationChannelType, NotificationChannel> channels;
    private final NotificationSettingService settingService;
    private final NotificationRecipientResolver recipientResolver;
    private final NotificationLogService notificationLogService;

    public NotificationDispatcher(List<NotificationChannel> channelBeans,
                                  NotificationSettingService settingService,
                                  NotificationRecipientResolver recipientResolver,
                                  NotificationLogService notificationLogService) {
        this.channels = new EnumMap<>(NotificationChannelType.class);
        for (NotificationChannel channel : channelBeans) {
            this.channels.put(channel.getType(), channel);
        }
        this.settingService = settingService;
        this.recipientResolver = recipientResolver;
        this.notificationLogService = notificationLogService;
    }

    /**
     * 한 사용자에게 알림을 라우팅·발송한다. 특정 피보호자에 관한 알림이 아닐 때 쓴다(문의 답변·연결 수락 등).
     *
     * @param userId  수신자 ID
     * @param type    알림 종류(필수/선택 분류 포함)
     * @param content 발송할 제목/본문/부가데이터
     */
    public void dispatch(String userId, NotificationType type, NotificationContent content) {
        dispatch(userId, null, type, content);
    }

    /**
     * 한 사용자에게 알림을 라우팅·발송하고 결과를 이력에 남긴다.
     *
     * <p>{@code wardId}는 <b>이력 표시 전용</b>이다(관리자 화면의 "피보호자" 칸). 발송 대상·채널 결정에는
     * 쓰지 않는다. {@code content.data()}의 wardId를 대신 읽지 않는 이유 - data는 FE 계약 값이라 라우팅·기록의
     * 근거로 쓰지 않는다(2026-07-27 알림톡 결정과 같은 원칙).</p>
     *
     * @param wardId 이 알림이 어느 피보호자에 관한 것인가. 특정할 수 없으면 null
     */
    public void dispatch(String userId, String wardId, NotificationType type, NotificationContent content) {
        Outcome outcome = switch (type.policy()) {
            case FORCED_PUSH_WITH_SMS_FALLBACK ->
                    withReceivableRecipient(userId, type, r -> dispatchMandatory(r, type, content));
            case FORCED_PUSH_PLUS_SETTINGS ->
                    withReceivableRecipient(userId, type, r -> dispatchForcedPushPlusSettings(r, type, content));
            case SETTINGS_ONLY -> dispatchBySettings(userId, type, content);
        };
        record(userId, wardId, type, content, outcome);
    }

    /**
     * 수신자를 조회하고 <b>받을 수 있는 상태인지</b> 확인한 뒤 발송한다. 못 받는 상태면 보내지 않는다.
     *
     * <p>이용 제한(정지)·탈퇴 진행 계정에는 어떤 채널로도 보내지 않는다 - <b>강제 FCM도 예외가 아니다.</b>
     * 정지는 대개 탈취가 의심돼 잠근 것이라, 알림을 계속 보내면 그 계정을 쥔 사람에게 피보호자의
     * SOS 발생·화재 감지 같은 생활 상황을 계속 통보하는 셈이 된다.</p>
     *
     * <p>차단 판정을 이 한 곳에 모아 둔다 - 정책 분기마다 따로 적어 두면 하나를 고칠 때 나머지가 어긋난다.
     * 다만 <b>정지된 사람이 원인인 알림은 막지 않는다</b>: 정지된 피보호자 집의 화재는 그 보호자들에게
     * 그대로 발송된다(수신자가 다른 사람이므로). 계정 정지는 이용 제한이지 안전망 해제가 아니다.</p>
     */
    private Outcome withReceivableRecipient(String userId, NotificationType type,
                                            Function<NotificationRecipient, Outcome> send) {
        NotificationRecipient recipient = recipientResolver.resolve(userId);
        if (recipient.canReceive()) {
            return send.apply(recipient);
        }
        log.warn("[NOTIFY-BLOCKED] 이용 제한 계정이라 발송하지 않음: userId={}, type={}, status={}",
                userId, type, recipient.status());
        return Outcome.notSent(recipient.status() == Status.RESTRICTED
                ? NotificationNotSentReason.RESTRICTED_ACCOUNT
                : NotificationNotSentReason.WITHDRAWING_ACCOUNT);
    }

    /** 사용자 설정의 활성 채널로만 발송(연결·문의 알림). 종류별 허용 채널이 좁으면 그만큼 더 줄어든다. */
    private Outcome dispatchBySettings(String userId, NotificationType type, NotificationContent content) {
        // 활성 채널 판단이 먼저다 - 보낼 채널이 하나도 없으면 수신자 조회(DB) 자체를 하지 않는다.
        Set<NotificationChannelType> targets = settingsChannels(userId, type);
        if (targets.isEmpty()) {
            log.debug("발송할 활성 채널 없음: userId={}, type={}", userId, type);
            return Outcome.notSent(NotificationNotSentReason.NO_ENABLED_CHANNEL);
        }

        return withReceivableRecipient(userId, type, recipient -> {
            List<ChannelAttempt> attempts = new ArrayList<>();
            for (NotificationChannelType channelType : targets) {
                attempt(attempts, channelType, sendQuietly(channelType, type, recipient, content));
            }
            return Outcome.of(attempts);
        });
    }

    /**
     * FCM 고정 + 나머지 채널은 사용자 설정대로(이상감지).
     *
     * <p>대상 = {@code {FCM} ∪ 사용자 활성 채널}. FCM은 사용자가 꺼도 발송하고, SMS·알림톡은 켠 경우에만 추가된다.
     * <b>푸시 전달 실패해도 SMS로 폴백하지 않는다</b>(D-2) — 문자는 사용자가 선택하는 채널이라 폴백이 그 선택을
     * 뒤집기 때문. 대신 미전달을 WARN으로 남겨 "아무에게도 안 갔는데 아무도 모르는" 침묵을 막는다.</p>
     */
    private Outcome dispatchForcedPushPlusSettings(NotificationRecipient recipient, NotificationType type,
                                                   NotificationContent content) {
        Set<NotificationChannelType> targets = EnumSet.of(FORCED_PUSH);
        targets.addAll(settingsChannels(recipient.userId(), type));

        List<ChannelAttempt> attempts = new ArrayList<>();
        boolean pushDelivered = false;
        for (NotificationChannelType channelType : targets) {
            ChannelResult result = sendQuietly(channelType, type, recipient, content);
            attempt(attempts, channelType, result);
            if (channelType == FORCED_PUSH) {
                pushDelivered = result.isDelivered();
            }
        }

        if (!pushDelivered) {
            // 토큰 없음·전 토큰 만료·발송 예외 — SMS 폴백을 하지 않는 정책이라 로그가 유일한 감지 수단이다.
            log.warn("[NOTIFY-UNDELIVERED] 푸시 미전달(SMS 폴백 안 함 - 문자는 사용자 선택): userId={}, type={}",
                    recipient.userId(), type);
        }
        return Outcome.of(attempts);
    }

    /**
     * 설정 기반 발송에 쓸 채널 = <b>사용자가 켠 채널 ∩ 그 종류가 허용하는 채널</b>.
     *
     * <p>{@link NotificationType#allowedChannels()}는 기본이 전 채널이라 대부분의 종류는 사용자 설정
     * 그대로다. 좁게 선언한 종류(재촉 = FCM만)만 여기서 걸러진다.</p>
     *
     * <p><b>강제 채널(FCM)에는 적용하지 않는다</b> — 호출부가 이 교집합을 강제 발송 경로에 끼워 넣으면
     * SOS·화재의 푸시 보장이 깨진다. 그래서 이 메서드는 설정에서 나온 집합만 다룬다.</p>
     */
    private Set<NotificationChannelType> settingsChannels(String userId, NotificationType type) {
        Set<NotificationChannelType> enabled = EnumSet.noneOf(NotificationChannelType.class);
        enabled.addAll(settingService.enabledChannels(userId));
        enabled.retainAll(type.allowedChannels());
        return enabled;
    }

    /** 채널 1건 발송. 미구현 채널은 대상 아님으로 건너뛰고, 발송 예외는 격리해 실패로 돌려준다. */
    private ChannelResult sendQuietly(NotificationChannelType channelType,
                                      NotificationType type,
                                      NotificationRecipient recipient,
                                      NotificationContent content) {
        NotificationChannel channel = channels.get(channelType);
        if (channel == null) {
            // EMAIL 등 미구현 채널: 설정상 켜져 있어도 발송 수단이 없음
            log.debug("미구현 채널 건너뜀: userId={}, channel={}", recipient.userId(), channelType);
            return ChannelResult.notApplicable();
        }
        try {
            ChannelResult result = channel.send(type, recipient, content);
            if (result == null) {
                // 계약 위반(구현체 결함) - 결과를 모르는 것을 성공으로 칠 수 없다. 폴백 판단이 흔들리지 않게 실패로 본다.
                log.error("채널이 결과 없이 반환함: userId={}, channel={}", recipient.userId(), channelType);
                return ChannelResult.failed(ChannelFailureReason.UNEXPECTED_ERROR);
            }
            return result;
        } catch (Exception e) {
            // 한 채널 실패가 다른 채널 발송을 막지 않도록 격리. 구조 결함 진단을 위해 스택 포함 (L-S2-6)
            log.error("채널 발송 실패: userId={}, channel={}", recipient.userId(), channelType, e);
            return ChannelResult.failed(ChannelFailureReason.UNEXPECTED_ERROR);
        }
    }

    /**
     * 필수 알림(WARD_SOS 등) — 사용자 설정을 무시하고 FCM 강제 발송, <b>전달 결과 기반</b> SMS 폴백 (M-S2-1).
     * <p>
     * 기존 "토큰 존재 여부" 기반 폴백은 토큰이 DB에 있으나 전부 만료(앱 삭제 등)인 보호자에게
     * 푸시·SMS 모두 미발송되는 갭이 있었다. 토큰 없음·전 토큰 만료·발송 예외를 모두
     * "전달 실패"로 수렴시켜 SMS 폴백한다. 전달 성공 시엔 SMS 비용을 아낀다.
     */
    private Outcome dispatchMandatory(NotificationRecipient recipient, NotificationType type,
                                      NotificationContent content) {
        String userId = recipient.userId();
        List<ChannelAttempt> attempts = new ArrayList<>();

        if (channels.containsKey(FORCED_PUSH)) {
            ChannelResult push = sendQuietly(FORCED_PUSH, type, recipient, content);
            attempt(attempts, FORCED_PUSH, push);
            if (push.isDelivered()) {
                return Outcome.of(attempts);
            }
            log.warn("필수 알림 FCM 미전달({}) — SMS 폴백 진행: userId={}", push.reason(), userId);
        }

        if (!channels.containsKey(MANDATORY_FALLBACK)) {
            log.warn("필수 알림 폴백 채널(SMS) 미구현 — 발송 불가: userId={}", userId);
            return Outcome.of(attempts);
        }
        ChannelResult fallback = sendQuietly(MANDATORY_FALLBACK, type, recipient, content);
        attempt(attempts, MANDATORY_FALLBACK, fallback);
        if (fallback.isDelivered()) {
            log.info("필수 알림 SMS 폴백 발송: userId={}", userId);
            return new Outcome(NotificationLogResult.SMS_FALLBACK, null, attempts);
        }
        log.warn("필수 알림 SMS 폴백 실패({}): userId={}", fallback.reason(), userId);
        return Outcome.of(attempts);
    }

    /** 시도 결과를 이력용 목록에 더한다. 대상이 아닌 채널(알림톡 템플릿 미매핑 등)은 남기지 않는다. */
    private static void attempt(List<ChannelAttempt> attempts, NotificationChannelType channel, ChannelResult result) {
        if (result.status() != ChannelResult.Status.NOT_APPLICABLE) {
            attempts.add(ChannelAttempt.of(channel, result));
        }
    }

    /**
     * 발송 결과를 이력에 남긴다. <b>발송이 끝난 뒤</b> 부르며, 실패해도 발송에는 영향이 없다.
     *
     * <p>로그에는 본문·예외 메시지를 싣지 않는다 - 본문엔 이름·장소가, DB 예외 메시지엔 입력값이 섞일 수 있다.</p>
     */
    private void record(String userId, String wardId, NotificationType type, NotificationContent content,
                        Outcome outcome) {
        try {
            notificationLogService.record(NotificationLog.builder()
                    .createdAt(OffsetDateTime.now())
                    .type(type)
                    .recipientId(userId)
                    .wardId(wardId)
                    .title(content.title())
                    .body(content.body())
                    .result(outcome.result())
                    .notSentReason(outcome.notSentReason())
                    .channelResults(outcome.attempts())
                    .build());
        } catch (RuntimeException e) {
            log.warn("[NOTIFY-LOG-FAILED] 알림 이력 기록 실패(발송에는 영향 없음): userId={}, type={}, result={}, cause={}",
                    userId, type, outcome.result(), e.getClass().getSimpleName());
        }
    }

    /** 수신자 1명에 대한 발송 결과. */
    private record Outcome(NotificationLogResult result, NotificationNotSentReason notSentReason,
                           List<ChannelAttempt> attempts) {

        static Outcome notSent(NotificationNotSentReason reason) {
            return new Outcome(NotificationLogResult.NOT_SENT, reason, List.of());
        }

        /**
         * 시도 목록으로 결과를 정한다: 하나라도 전달 → 완료 / 시도가 없음(켠 채널이 모두 대상 아님) → 보내지 않음 /
         * 나머지 → 실패.
         */
        static Outcome of(List<ChannelAttempt> attempts) {
            if (attempts.stream().anyMatch(ChannelAttempt::delivered)) {
                return new Outcome(NotificationLogResult.DELIVERED, null, attempts);
            }
            if (attempts.isEmpty()) {
                return notSent(NotificationNotSentReason.NO_ENABLED_CHANNEL);
            }
            return new Outcome(NotificationLogResult.FAILED, null, attempts);
        }
    }
}
