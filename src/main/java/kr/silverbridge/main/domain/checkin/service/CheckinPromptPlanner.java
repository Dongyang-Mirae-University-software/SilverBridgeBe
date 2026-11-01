package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.domain.checkin.entity.CheckinSetting;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.repository.CheckinNotifyLogRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinResultRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinSessionRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinSettingRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.channel.WardNotificationText;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 안부 시각 알림·미응답 확인(2026-11-01, 2차). 1분마다 돈다. 말벗이 꺼진 서버(gosky)에서는 아무것도 하지 않는다.
 *
 * <p><b>피보호자 안부 알림</b>: 지금 열린 시각(09·12·15·18·21시 KST)에, "안부 알림 받기"가 켜져 있고 ACTIVE 보호자가 있으며
 * 아직 결과가 없는 이용 중(ACTIVE) 피보호자에게 {@code CHECKIN_PROMPT}. 피보호자마다 0~4분 분산, 응답 창(1시간) 안에서만.
 * <b>선점 후 발송</b>({@code checkin_prompt_log} UNIQUE) - 순서를 뒤집지 말 것(1분 주기라 기록이 없으면 창 내내 중복 발송).
 * 발송 실패는 재시도하지 않는다(한 번 빠지는 쪽이 두 번 가는 쪽보다 낫다 - 복약 규칙과 같다).</p>
 *
 * <p><b>미응답</b>: 알림을 받았는데 응답 창이 닫힐 때까지 결과도, 말을 나눈 대화도 없으면 보호자에게 {@code CHECKIN_MISSED}
 * (피보호자당 하루 한 번, 끈 보호자 제외). 창이 닫힌 뒤 {@code missedNotifyDeadline} 안에만 본다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckinPromptPlanner {

    static final String PROMPT_BODY = "안부 인사 드려요. 앱에서 잠깐 이야기 나눠요.";

    private final CheckinProperties properties;
    private final CheckinClock clock;
    private final CheckinSettingRepository settingRepository;
    private final CheckinResultRepository resultRepository;
    private final CheckinSessionRepository sessionRepository;
    private final CheckinNotifyLogRepository logRepository;
    private final ConnectionService connectionService;
    private final UserRepository userRepository;
    private final NotificationDispatcher dispatcher;
    private final CheckinGuardianAlertService alertService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void run() {
        if (!properties.isActive()) {
            return;
        }
        try {
            runOnce(clock.now());
        } catch (RuntimeException e) {
            log.error("[CHECKIN-PLANNER] 실행 실패: error={}", e.getClass().getSimpleName());
        }
    }

    void runOnce(OffsetDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDateTime local = now.toLocalDateTime();
        CheckinSlot.current(now.toLocalTime())
                .filter(slot -> local.isBefore(today.atTime(slot.time()).plus(properties.getResponseWindow())))
                .ifPresent(slot -> sendPrompts(today, slot, local));
        for (CheckinSlot slot : CheckinSlot.values()) {
            LocalDateTime closeAt = today.atTime(slot.time()).plus(properties.getResponseWindow());
            if (!local.isBefore(closeAt) && local.isBefore(closeAt.plus(properties.getMissedNotifyDeadline()))) {
                checkMissed(today, slot);
            }
        }
    }

    private void sendPrompts(LocalDate today, CheckinSlot slot, LocalDateTime now) {
        long minutesIn = Duration.between(today.atTime(slot.time()), now).toMinutes();
        int sent = 0;
        for (CheckinSetting setting : settingRepository.findByPromptEnabledTrue()) {
            String wardId = setting.getWardId();
            try {
                if (minutesIn < spreadOffset(wardId, today, slot) || !eligible(wardId, today, slot)) {
                    continue;
                }
                if (logRepository.claimPrompt(wardId, today, slot.value()) == 0) {
                    continue;   // 이미 보냈다
                }
                dispatcher.dispatch(wardId, wardId, NotificationType.CHECKIN_PROMPT, NotificationContent.of(
                        WardNotificationText.TITLE, PROMPT_BODY,
                        Map.of("type", "CHECKIN_PROMPT", "slot", slot.value(), "checkDate", today.toString())));
                sent++;
            } catch (RuntimeException e) {
                log.warn("[CHECKIN-PLANNER] 안부 알림 실패: wardId={}, slot={}, error={}",
                        wardId, slot.value(), e.getClass().getSimpleName());
            }
        }
        if (sent > 0) {
            log.info("[CHECKIN-PLANNER] 안부 알림 발송: slot={}, sent={}", slot.value(), sent);
        }
    }

    /** 이용 중인 피보호자이고, ACTIVE 보호자가 있고, 이번 시각 결과가 아직 없다. */
    private boolean eligible(String wardId, LocalDate today, CheckinSlot slot) {
        boolean activeWard = userRepository.findById(wardId)
                .map(user -> user.getStatus() == Status.ACTIVE && user.getRole() == Role.WARD)
                .orElse(false);
        return activeWard
                && !connectionService.getActiveGuardianIds(wardId).isEmpty()
                && !resultRepository.existsByWardIdAndCheckDateAndSlot(wardId, today, slot);
    }

    private void checkMissed(LocalDate today, CheckinSlot slot) {
        for (String wardId : logRepository.promptedWards(today, slot.value())) {
            try {
                if (resultRepository.existsByWardIdAndCheckDateAndSlot(wardId, today, slot) || talked(wardId, today, slot)) {
                    continue;
                }
                alertService.sendMissed(wardId, today, slot);
            } catch (RuntimeException e) {
                log.warn("[CHECKIN-PLANNER] 미응답 확인 실패: wardId={}, slot={}, error={}",
                        wardId, slot.value(), e.getClass().getSimpleName());
            }
        }
    }

    /** 그 시각에 말을 나눈 대화가 있으면(정리만 안 했어도) 미응답이 아니다. */
    private boolean talked(String wardId, LocalDate today, CheckinSlot slot) {
        return sessionRepository.findByWardIdAndCheckDate(wardId, today).stream()
                .anyMatch(s -> s.getSlot() == slot && s.getTurnCount() > 0);
    }

    /** 피보호자·날짜·시각마다 고정된 0~(spread-1)분. 같은 분에 몰리지 않게 하고, 재시작해도 같은 값이다. */
    int spreadOffset(String wardId, LocalDate date, CheckinSlot slot) {
        int spread = Math.max(1, properties.getPromptSpreadMinutes());
        return Math.floorMod((wardId + "|" + date + "|" + slot.value()).hashCode(), spread);
    }
}
