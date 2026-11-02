package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.repository.CheckinNotifyLogRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.channel.WardNotificationText;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
import kr.silverbridge.main.domain.notification.service.GuardianNotificationPreferenceService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 안부 말벗 보호자 알림(2026-11-01, 2차). 받는 사람은 그 피보호자의 <b>ACTIVE 보호자</b>(PENDING 제외).
 *
 * <p>보호자마다 <b>선점 후 발송</b>({@code checkin_guardian_alert_log} UNIQUE) - 위험·확인 필요는 시각당 한 번, 미응답은 하루 한 번.
 * 문구는 단정하지 않는다(서버는 키워드·평소 대비 판정까지만 안다). 대화 내용은 싣지 않는다 - 보호자는 조회 화면에서 요약·근거만 본다.
 * 발송 실패는 보호자별로 삼킨다(한 사람의 실패가 다른 보호자 알림을 막지 않는다).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinGuardianAlertService {

    static final String KIND_RISK = "RISK";
    static final String KIND_ATTENTION = "ATTENTION";
    static final String KIND_MISSED = "MISSED";
    private static final String FALLBACK_WARD_NAME = "보호 대상자";
    static final String RISK_SMS_WITHOUT_NAME = "[실버브릿지] 안부 대화 중 도움이 필요할 수 있는 말씀이 있었어요. 연락해 주세요.";

    private final ConnectionService connectionService;
    private final CheckinNotifyLogRepository logRepository;
    private final NotificationDispatcher dispatcher;
    private final UserRepository userRepository;
    private final GuardianNotificationPreferenceService preferenceService;

    /** 위험 신호 - 보호자 알림 종류 설정으로 끌 수 없다(필수). */
    public int sendRisk(String wardId, LocalDate date, CheckinSlot slot) {
        String name = wardName(wardId);
        NotificationContent content = NotificationContent.of(
                "[긴급] %s님 안부 대화".formatted(name),
                "안부 대화 중 도움이 필요할 수 있는 말씀이 있었어요.\n바로 연락해서 확인해 주세요.",
                data("CHECKIN_RISK", wardId, name, date, slot),
                riskSmsText(name));
        return send(KIND_RISK, NotificationType.CHECKIN_RISK, wardId, date, slot,
                connectionService.getActiveGuardianIds(wardId), content);
    }

    public int sendAttention(String wardId, LocalDate date, CheckinSlot slot) {
        String name = wardName(wardId);
        NotificationContent content = NotificationContent.of(
                "안부 확인 · %s님".formatted(name),
                "오늘 %s 안부 대화가 평소와 조금 달랐어요.\n시간 되실 때 연락해 보세요.".formatted(hourLabel(slot)),
                data("CHECKIN_ATTENTION", wardId, name, date, slot));
        return send(KIND_ATTENTION, NotificationType.CHECKIN_ATTENTION, wardId, date, slot,
                connectionService.getActiveGuardianIds(wardId), content);
    }

    /** 미응답 - 끈 보호자 제외, 그날 이미 받은 보호자 제외(하루 한 번). */
    public int sendMissed(String wardId, LocalDate date, CheckinSlot slot) {
        List<String> guardians = connectionService.getActiveGuardianIds(wardId);
        if (guardians.isEmpty()) {
            return 0;
        }
        Set<String> disabled;
        try {
            disabled = preferenceService.checkinMissedDisabledGuardians();
        } catch (RuntimeException e) {
            // 설정을 모르면 보내지 않는다(끌 수 있는 안내성 알림 - 꺼 둔 사람에게 가는 쪽이 더 나쁘다)
            log.warn("[CHECKIN-ALERT] 미응답 알림 수신 설정 조회 실패 - 이번 회차 생략: wardId={}, error={}",
                    wardId, e.getClass().getSimpleName());
            return 0;
        }
        List<String> receivers = guardians.stream()
                .filter(id -> !disabled.contains(id))
                .filter(id -> !logRepository.alertedOnDate(id, wardId, date, KIND_MISSED))
                .toList();
        if (receivers.isEmpty()) {
            return 0;
        }
        String name = wardName(wardId);
        NotificationContent content = NotificationContent.of(
                "안부 확인 · %s님".formatted(name),
                "오늘 %s 안부 인사에 아직 답이 없으셨어요.\n괜찮으신지 연락해 보세요.".formatted(hourLabel(slot)),
                data("CHECKIN_MISSED", wardId, name, date, slot));
        return send(KIND_MISSED, NotificationType.CHECKIN_MISSED, wardId, date, slot, receivers, content);
    }

    private int send(String kind, NotificationType type, String wardId, LocalDate date, CheckinSlot slot,
                     List<String> guardians, NotificationContent content) {
        int sent = 0;
        for (String guardianId : guardians) {
            try {
                if (logRepository.claimGuardianAlert(guardianId, wardId, date, slot.value(), kind) == 0) {
                    continue;   // 이미 보냈다
                }
                // FAILED(설정·수신자 조회 실패 포함)는 세지 않는다 - 예외였던 #425 이전과 같은 집계(2026-11-02 점검 L-3)
                if (dispatcher.dispatch(guardianId, wardId, type, content) != NotificationLogResult.FAILED) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("[CHECKIN-ALERT-FAILED] 보호자 알림 실패: kind={}, wardId={}, guardianId={}, error={}",
                        kind, wardId, guardianId, e.getClass().getSimpleName());
            }
        }
        log.info("[CHECKIN-ALERT] 보호자 알림: kind={}, wardId={}, slot={}, sent={}", kind, wardId, slot.value(), sent);
        return sent;
    }

    private String wardName(String wardId) {
        try {
            return userRepository.findById(wardId)
                    .map(user -> StringUtils.hasText(user.getName()) ? user.getName() : FALLBACK_WARD_NAME)
                    .orElse(FALLBACK_WARD_NAME);
        } catch (RuntimeException e) {
            return FALLBACK_WARD_NAME;
        }
    }

    /**
     * 푸시가 전달되지 않았을 때의 대체 문자 - <b>단문 한도(EUC-KR 90바이트) 안</b>(2026-11-01 점검 M-1). 이상감지 대체 문자와 같은
     * 기준이다(장문 과금·분할 방지). 이름이 길어 넘치면 이름을 뺀 문구로 보낸다.
     */
    static String riskSmsText(String wardName) {
        String withName = "[실버브릿지] %s님 안부 대화에 도움이 필요할 수 있어요. 연락해 주세요.".formatted(wardName);
        // 대체 문자는 제목 없이 이 문자열 그대로 나간다(디스패처가 title=null로 보냄) - 접두어를 다시 붙이는 fitsSingleSms가 아니라 그대로 잰다
        return WardNotificationText.eucKrBytes(withName) <= WardNotificationText.SINGLE_SMS_MAX_BYTES
                ? withName : RISK_SMS_WITHOUT_NAME;
    }

    static String hourLabel(CheckinSlot slot) {
        int hour = slot.time().getHour();
        return hour < 12 ? "오전 %d시".formatted(hour) : hour == 12 ? "낮 12시" : "오후 %d시".formatted(hour - 12);
    }

    private static Map<String, String> data(String type, String wardId, String wardName, LocalDate date,
                                            CheckinSlot slot) {
        return Map.of("type", type, "wardId", wardId, "wardName", wardName,
                "checkDate", date.toString(), "slot", slot.value());
    }
}
