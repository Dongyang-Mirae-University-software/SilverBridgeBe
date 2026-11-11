package kr.silverbridge.main.domain.companion.listener;

import kr.silverbridge.main.domain.companion.entity.CompanionNoticeLog;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.event.CompanionMoodRecordedEvent;
import kr.silverbridge.main.domain.companion.repository.CompanionNoticeLogRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.companion.service.CompanionMoodDays;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.service.GuardianNotificationPreferenceService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 안부 말벗 주의 알림(2026-11-04, PR-B, 결정 I) - 걱정·우울이 3일 이상 이어지면 ACTIVE 보호자에게 하루 1번.
 *
 * <p>대화가 걱정·우울로 끝나 커밋된 뒤 연속 일수({@link CompanionMoodDays})를 다시 계산한다. 하루 1번은
 * {@code companion_notice_log} UNIQUE 선점이 보장한다(선점 후 발송 - 발송이 실패해도 그 날 다시 보내지 않는다).
 * 알림 종류 설정에서 주의 알림을 끈 보호자는 뺀다(게이트는 여기서 건다 - 디스패처는 이 설정을 모른다).
 * 받을 보호자가 없으면 선점하지 않는다(관리자 "알림 보냄" 수에 넣지 않는다).</p>
 *
 * <p>연속 일수는 <b>보호자마다</b> 그 보호자의 지금 연결 이후 대화로 센다(D-1, 2026-11-09 PR-B 점검 M-1) - 화면 주의 칸과
 * 같은 숫자다. 연결 전 기분 일수를 새로 연결한 보호자에게 알리지 않는다. 선점은 피보호자·날짜 1번, 기록 일수는 보낸 일수 중 최댓값.</p>
 *
 * <p>문구에 병명·대답 원문을 넣지 않는다 - "걱정스럽거나 가라앉은 기분이 N일째"와 연락 권유뿐이다. 일반 알림 풀
 * ({@code notificationExecutor})에서 보낸다(위험 신호가 아니라 관찰 안내).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanionNoticeListener {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String FALLBACK_WARD_NAME = "피보호자";

    private final CompanionSessionRepository sessionRepository;
    private final CompanionNoticeLogRepository noticeLogRepository;
    private final ConnectionService connectionService;
    private final GuardianNotificationPreferenceService preferenceService;
    private final NotificationDispatcher notificationDispatcher;
    private final UserRepository userRepository;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMoodRecorded(CompanionMoodRecordedEvent event) {
        List<CompanionSession> finished = sessionRepository.findFinishedSince(event.wardId(),
                CompanionSessionStatus.FINISHED, event.sessionDate().minusDays(CompanionMoodDays.LOOKBACK_DAYS));
        // 피보호자 단위로도 3일이 안 되면 보호자별로 셀 필요가 없다(보호자별 일수는 이보다 클 수 없다)
        Optional<CompanionMoodDays.Streak> wardStreak = CompanionMoodDays.streak(
                CompanionMoodDays.dailyMoods(finished), event.sessionDate());
        if (wardStreak.isEmpty() || !wardStreak.get().reachesNotice()) {
            return;
        }
        Set<String> disabled = preferenceService.companionNoticeDisabledGuardians();
        Map<String, CompanionMoodDays.Streak> targets = new LinkedHashMap<>();
        List<String> guardians = connectionService.getActiveGuardianIds(event.wardId());
        // 계정이 이용 중이 아닌(이용 제한·탈퇴 진행) 보호자는 디스패처가 막는다 - 대상으로 세면 받을 사람 없이 선점만 된다
        // (이상감지 재촉 L-12와 같은 기준, 2026-11-11 영향 범위 점검 M-1)
        Set<String> usable = guardians.isEmpty() ? Set.of()
                : new HashSet<>(userRepository.findIdsByIdInAndStatus(guardians, Status.ACTIVE));
        for (String guardianId : guardians) {
            if (!usable.contains(guardianId) || disabled.contains(guardianId)) {
                continue;
            }
            connectionService.findActiveConnectedAt(guardianId, event.wardId())
                    .flatMap(connectedAt -> CompanionMoodDays.streak(CompanionMoodDays.dailyMoods(
                            CompanionMoodDays.startedSince(finished, connectedAt)), event.sessionDate()))
                    .filter(CompanionMoodDays.Streak::reachesNotice)
                    .ifPresent(streak -> targets.put(guardianId, streak));
        }
        if (targets.isEmpty()) {
            log.info("[COMPANION-NOTICE] 받을 보호자 없음: wardId={}, days={}", event.wardId(), wardStreak.get().days());
            return;
        }
        CompanionMoodDays.Streak longest = targets.values().stream()
                .max(Comparator.comparingInt(CompanionMoodDays.Streak::days)).orElseThrow();
        try {
            noticeLogRepository.saveAndFlush(CompanionNoticeLog.of(event.wardId(), event.sessionDate(),
                    longest.mood(), longest.days(), OffsetDateTime.now(KST)));
        } catch (DataIntegrityViolationException e) {
            return; // 오늘 이미 보냈다
        }
        String wardName = userRepository.findById(event.wardId()).map(User::getName).orElse(FALLBACK_WARD_NAME);
        int sent = 0;
        for (Map.Entry<String, CompanionMoodDays.Streak> target : targets.entrySet()) {
            String guardianId = target.getKey();
            int days = target.getValue().days();
            String body = wardName + "님 안부 대화에서 걱정스럽거나 가라앉은 기분이 " + days
                    + "일째 이어지고 있어요. 안부 전화를 드려 보세요.";
            try {
                notificationDispatcher.dispatch(guardianId, event.wardId(), NotificationType.COMPANION_NOTICE,
                        NotificationContent.of("안부 말벗 · 기분 변화", body,
                                Map.of("type", "COMPANION_NOTICE", "wardId", event.wardId(),
                                        "days", String.valueOf(days))));
                sent++;
            } catch (RuntimeException e) {
                log.error("[COMPANION-NOTICE] 알림 발송 실패: guardianId={}, wardId={}, error={}",
                        guardianId, event.wardId(), e.getClass().getSimpleName());
            }
        }
        log.info("[COMPANION-NOTICE] 주의 알림 발송: wardId={}, days={}, guardians={}", event.wardId(), longest.days(), sent);
    }
}
