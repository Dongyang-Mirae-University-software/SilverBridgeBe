package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionPromptLog;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionPromptLogRepository;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * 안부 질문 시간 안내(09·12·15·18·21시 KST, 2026-11-04) → 피보호자 본인에게 FCM.
 *
 * <p>PR-B(피보호자 화면·알림 설정) 전까지는 {@code companion.prompt-ward-ids} 허용 목록의 계정에만 보낸다 - 목록이 비면
 * 아무에게도 보내지 않는다. ACTIVE 피보호자만 대상이다(정지 계정은 디스패처도 막는다).</p>
 *
 * <p><b>선점 후 발송</b>: {@code (ward_id, prompt_date, slot)} UNIQUE 기록을 먼저 남기고 보낸다 - 재기동·중복 실행에도
 * 같은 시간대 알림은 한 번뿐이다. 발송이 실패해도 다시 보내지 않는다(다음 시간대가 있다).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanionPromptScheduler {

    private final CompanionProperties properties;
    private final CompanionPromptLogRepository promptLogRepository;
    private final UserRepository userRepository;
    private final NotificationDispatcher notificationDispatcher;

    @Scheduled(cron = "0 0 9,12,15,18,21 * * *", zone = "Asia/Seoul")
    public void sendPrompts() {
        if (!properties.isUsable() || properties.getPromptWardIds().isEmpty()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(CompanionService.KST);
        Optional<CompanionSlot> slot = Arrays.stream(CompanionSlot.values())
                .filter(s -> s.contains(now.toLocalTime(), properties.getWindowMinutes()))
                .findFirst();
        if (slot.isEmpty()) {
            return;
        }
        for (String wardId : properties.getPromptWardIds()) {
            if (wardId == null || wardId.isBlank()) {
                continue;
            }
            try {
                sendOne(wardId.strip(), slot.get(), now);
            } catch (RuntimeException e) {
                log.error("[COMPANION-PROMPT] 안내 발송 실패: wardId={}, error={}", wardId, e.getClass().getSimpleName());
            }
        }
    }

    private void sendOne(String wardId, CompanionSlot slot, ZonedDateTime now) {
        boolean eligible = userRepository.findById(wardId)
                .filter(user -> user.getRole() == Role.WARD && user.getStatus() == Status.ACTIVE)
                .isPresent();
        if (!eligible) {
            log.info("[COMPANION-PROMPT] 대상 아님(피보호자 아님·정지·없음): wardId={}", wardId);
            return;
        }
        try {
            promptLogRepository.saveAndFlush(
                    CompanionPromptLog.of(wardId, now.toLocalDate(), slot, now.toOffsetDateTime()));
        } catch (DataIntegrityViolationException e) {
            return; // 이미 보냈다
        }
        int hour = slot.start().getHour();
        String body = (hour < 12 ? "오전 " + hour : "오후 " + (hour == 12 ? 12 : hour - 12))
                + "시 안부 인사 시간이에요. 오늘 이야기를 들려주세요.";
        notificationDispatcher.dispatch(wardId, wardId, NotificationType.COMPANION_PROMPT,
                NotificationContent.of("안부 말벗", body,
                        Map.of("type", "COMPANION_PROMPT", "slot", slot.code())));
    }
}
