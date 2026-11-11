package kr.silverbridge.main.domain.companion.listener;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.event.CompanionRiskPatternCheckEvent;
import kr.silverbridge.main.domain.companion.repository.CompanionRiskPatternNoticeRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.companion.service.CompanionMoodDays;
import kr.silverbridge.main.domain.companion.service.CompanionRiskPattern;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 안부 말벗 여러 날 위험 누적 알림(2026-11-11 사용자 결정).
 *
 * <p>턴 위험 확률이 누적 기준 이상으로 커밋된 뒤 최근 며칠({@link CompanionRiskPattern})을 다시 본다. 보호자마다 그
 * 보호자의 지금 연결 이후 대화만 센다(D-1, {@link CompanionMoodDays#startedSince}). 피보호자당 {@code windowDays}일에
 * 1번은 {@code companion_risk_pattern_notice} 조건부 UPSERT 선점이 보장한다(선점 후 발송 - 발송이 실패해도 다시 보내지
 * 않는다, 위험 알림과 같은 방식 - 점검 L-3 수용). 받을 보호자(계정 이용 중 + 조건 충족)가 없으면 선점하지 않는다.</p>
 *
 * <p>{@code COMPANION_RISK_PATTERN}은 위험 알림과 같은 강제 채널이고 긴급 풀({@code urgentNotificationExecutor})에서
 * 보낸다. 정지 계정 수신 차단은 디스패처가 한다. 문구·로그에 대답 원문·질병명·확률 값을 넣지 않는다(일수만).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanionRiskPatternListener {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String FALLBACK_WARD_NAME = "피보호자";

    private final CompanionProperties properties;
    private final CompanionSessionRepository sessionRepository;
    private final CompanionRiskPatternNoticeRepository noticeRepository;
    private final ConnectionService connectionService;
    private final NotificationDispatcher notificationDispatcher;
    private final UserRepository userRepository;

    @Async("urgentNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCheck(CompanionRiskPatternCheckEvent event) {
        CompanionProperties.RiskPattern config = properties.getRiskPattern();
        if (!config.isEnabled()) {
            return;
        }
        String wardId = event.wardId();
        LocalDate today = event.sessionDate();
        List<CompanionSession> sessions = sessionRepository.findByWardIdAndSessionDateBetween(
                wardId, CompanionRiskPattern.windowStart(today, config), today);
        // 피보호자 단위로 조건이 안 되면 보호자별로 셀 필요가 없다(보호자별 대화는 이 일부다)
        if (CompanionRiskPattern.qualifyingDays(sessions, today, config).isEmpty()) {
            return;
        }
        Map<String, Integer> targets = new LinkedHashMap<>();
        List<String> guardians = connectionService.getActiveGuardianIds(wardId);
        // 계정이 이용 중이 아닌(이용 제한·탈퇴 진행) 보호자는 디스패처가 막는다 - 대상으로 세면 받을 사람 없이 7일 선점만
        // 소진된다(이상감지 재촉 L-12와 같은 기준, 2026-11-11 영향 범위 점검 M-1)
        Set<String> usable = guardians.isEmpty() ? Set.of()
                : new HashSet<>(userRepository.findIdsByIdInAndStatus(guardians, Status.ACTIVE));
        for (String guardianId : guardians) {
            if (!usable.contains(guardianId)) {
                continue;
            }
            connectionService.findActiveConnectedAt(guardianId, wardId).ifPresent(connectedAt -> {
                OptionalInt days = CompanionRiskPattern.qualifyingDays(
                        CompanionMoodDays.startedSince(sessions, connectedAt), today, config);
                days.ifPresent(d -> targets.put(guardianId, d));
            });
        }
        if (targets.isEmpty()) {
            log.info("[COMPANION-RISK-PATTERN] 받을 보호자 없음: wardId={}", wardId);
            return;
        }
        int longest = targets.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        LocalDate cutoff = today.minusDays(Math.max(1, config.getWindowDays()));
        if (noticeRepository.claim(wardId, today, longest, OffsetDateTime.now(KST), cutoff) != 1) {
            return; // 최근 windowDays일 안에 이미 보냈다
        }
        String wardName = userRepository.findById(wardId).map(User::getName).orElse(FALLBACK_WARD_NAME);
        String title = "안부 말벗 확인 필요";
        String body = wardName + "님과의 최근 며칠 안부 대화에서 걱정되는 말이 여러 번 있었어요. 안부를 확인해 주세요.";
        String smsText = "[실버브릿지] " + body;
        int sent = 0;
        for (Map.Entry<String, Integer> target : targets.entrySet()) {
            String guardianId = target.getKey();
            try {
                notificationDispatcher.dispatch(guardianId, wardId, NotificationType.COMPANION_RISK_PATTERN,
                        NotificationContent.of(title, body,
                                Map.of("type", "COMPANION_RISK_PATTERN", "wardId", wardId,
                                        "days", String.valueOf(target.getValue())),
                                smsText));
                sent++;
            } catch (RuntimeException e) {
                // 한 보호자 실패가 나머지를 막지 않게 격리한다
                log.error("[COMPANION-RISK-PATTERN] 알림 발송 실패: guardianId={}, wardId={}, error={}",
                        guardianId, wardId, e.getClass().getSimpleName());
            }
        }
        log.info("[COMPANION-RISK-PATTERN] 누적 알림 발송: wardId={}, days={}, guardians={}", wardId, longest, sent);
    }
}
