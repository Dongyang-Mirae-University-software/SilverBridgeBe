package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewReminderLog;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewSummaryLog;
import kr.silverbridge.main.domain.anomaly.entity.GuardianAnomalySetting;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentFeedbackRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyReviewReminderLogRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyReviewSummaryLogRepository;
import kr.silverbridge.main.domain.anomaly.repository.GuardianAnomalySettingRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 판정 미응답 재촉 대상을 고르고 <b>발송 기록을 먼저 선점</b>하는 컴포넌트.
 * 실제 발송은 {@link AnomalyReviewReminderService}가 이 트랜잭션 커밋 뒤에 한다(복약과 같은 순서).
 *
 * <p><b>선점 후 발송</b>: 기록을 먼저 커밋하고 보낸다. 순서를 뒤집으면 발송 직후 앱이 죽었을 때
 * 다음 주기에 또 보내고, 스케줄러가 5분마다 돌기 때문에 마감(3일) 내내 같은 재촉이 반복된다.</p>
 *
 * <p><b>재촉하지 않는 경우</b>는 다섯이다 - ① 이미 응답한 보호자 ② 누군가 응답해 상황이 PENDING을
 * 벗어난 경우(응답 API는 계속 열려 있어 나중에 다른 보호자가 눌러 CONFLICTED가 되는 경로는 살아 있다)
 * ③ 연결이 ACTIVE가 아닌 보호자 ④ 수신 설정을 끈 보호자 ⑤ 마감(상황 시작 + 3일)을 지난 상황.</p>
 *
 * <p><b>야간 억제</b>는 건너뛰기가 아니라 미루기다 - 조건이 그대로 남아 아침 첫 주기에 다시 잡힌다.
 * 화재 알림 본체는 억제 대상이 아니다(그건 밤에도 즉시 나가야 한다).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyReviewReminderPlanner {

    /** 이름을 못 찾은 피보호자의 표시용 폴백(문구에 null이 들어가지 않게). */
    static final String FALLBACK_WARD_NAME = "피보호자";

    private final AnomalyIncidentRepository incidentRepository;
    private final AnomalyIncidentFeedbackRepository feedbackRepository;
    private final AnomalyReviewReminderLogRepository reminderLogRepository;
    private final AnomalyReviewSummaryLogRepository summaryLogRepository;
    private final GuardianAnomalySettingRepository settingRepository;
    private final ConnectionService connectionService;
    private final CameraService cameraService;
    private final UserRepository userRepository;
    private final AnomalyProperties properties;

    /**
     * 건별 재촉(1차)을 선점한다. 상황이 닫히고 유예가 지난 뒤 딱 한 번이다.
     *
     * @return 보낼 대상. 야간이거나 후보가 없으면 빈 목록
     */
    @Transactional
    public List<AnomalyReviewReminderTarget> claimReminders() {
        OffsetDateTime now = AnomalyReviewClock.now();
        if (isQuietHours(now)) {
            return List.of();
        }

        AnomalyProperties.ReviewReminder config = properties.getReviewReminder();
        // 상황이 "닫힌" 시점 = 마지막 감지 + 묶음 간격. 거기서 유예까지 지나야 물어본다 -
        // 대응 중인 사람에게 판정을 묻지 않기 위해서다.
        OffsetDateTime dueBefore = now.minusMinutes(properties.getIncidentMergeMinutes() + config.getDelayMinutes());
        OffsetDateTime deadlineFrom = now.minusDays(config.getDeadlineDays());

        List<AnomalyIncident> candidates = incidentRepository
                .findByReviewStatusAndLastDetectedAtLessThanEqualAndStartedAtGreaterThanEqual(
                        AnomalyReviewStatus.PENDING, dueBefore, deadlineFrom);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> incidentIds = candidates.stream().map(AnomalyIncident::getId).toList();
        Set<String> answered = answeredKeys(incidentIds);
        Set<String> alreadyReminded = reminderLogRepository.findByIncidentIdIn(incidentIds).stream()
                .map(log -> key(log.getIncidentId(), log.getGuardianId()))
                .collect(Collectors.toSet());
        Map<String, List<String>> guardiansByWard = activeGuardiansByWard(candidates);

        List<AnomalyReviewReminderLog> logs = new ArrayList<>();
        List<Claim> claims = new ArrayList<>();
        for (AnomalyIncident incident : candidates) {
            for (String guardianId : guardiansByWard.getOrDefault(incident.getWardId(), List.of())) {
                if (answered.contains(key(incident.getId(), guardianId))
                        || alreadyReminded.contains(key(incident.getId(), guardianId))) {
                    continue;
                }
                claims.add(new Claim(guardianId, incident));
            }
        }
        if (claims.isEmpty()) {
            return List.of();
        }

        // 설정 조회는 실제 후보가 추려진 뒤에 한 번만 한다.
        Set<String> disabled = disabledGuardians(claims.stream().map(Claim::guardianId).collect(Collectors.toSet()));
        claims.removeIf(claim -> disabled.contains(claim.guardianId()));
        if (claims.isEmpty()) {
            return List.of();
        }

        for (Claim claim : claims) {
            logs.add(AnomalyReviewReminderLog.builder()
                    .incidentId(claim.incident().getId())
                    .guardianId(claim.guardianId())
                    .sentAt(now)
                    .build());
        }
        reminderLogRepository.saveAll(logs);

        Map<String, String> wardNames = wardNames(claims.stream()
                .map(claim -> claim.incident().getWardId()).collect(Collectors.toCollection(LinkedHashSet::new)));
        Map<String, String> cameraLabels = cameraService.findLabelsBySessionIds(claims.stream()
                .map(claim -> claim.incident().getSessionId()).collect(Collectors.toSet()));

        return claims.stream()
                .map(claim -> new AnomalyReviewReminderTarget(
                        claim.guardianId(),
                        claim.incident().getId(),
                        claim.incident().getWardId(),
                        wardNames.getOrDefault(claim.incident().getWardId(), FALLBACK_WARD_NAME),
                        cameraLabels.get(claim.incident().getSessionId()),
                        claim.incident().getDetectedType(),
                        claim.incident().getStartedAt()))
                .toList();
    }

    /**
     * 하루 1회 미응답 요약을 선점한다.
     *
     * <p>담는 것은 <b>이미 건별 재촉을 보낸</b> 상황뿐이다. 이 조건이 없으면 1차가 아직 안 나간 상황이
     * 요약에 먼저 실려, 같은 상황으로 요약과 건별 재촉이 연달아 도착한다.</p>
     */
    @Transactional
    public List<AnomalyReviewSummaryTarget> claimSummaries() {
        OffsetDateTime now = AnomalyReviewClock.now();
        if (isQuietHours(now)) {
            return List.of();
        }
        AnomalyProperties.ReviewReminder config = properties.getReviewReminder();
        if (AnomalyReviewClock.toTime(now).isBefore(config.getSummaryTime())) {
            return List.of();
        }

        List<AnomalyIncident> candidates = incidentRepository.findByReviewStatusAndStartedAtGreaterThanEqual(
                AnomalyReviewStatus.PENDING, now.minusDays(config.getDeadlineDays()));
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<Long, AnomalyIncident> byId = candidates.stream()
                .collect(Collectors.toMap(AnomalyIncident::getId, incident -> incident));
        List<Long> incidentIds = List.copyOf(byId.keySet());
        Set<String> answered = answeredKeys(incidentIds);
        Map<String, List<String>> guardiansByWard = activeGuardiansByWard(candidates);

        // 보호자별 "재촉했는데 아직 답이 없는" 상황 수. 연결이 끊긴 보호자는 세지 않는다.
        Map<String, Integer> pendingCounts = new LinkedHashMap<>();
        for (AnomalyReviewReminderLog reminder : reminderLogRepository.findByIncidentIdIn(incidentIds)) {
            AnomalyIncident incident = byId.get(reminder.getIncidentId());
            if (incident == null || answered.contains(key(incident.getId(), reminder.getGuardianId()))) {
                continue;
            }
            if (!guardiansByWard.getOrDefault(incident.getWardId(), List.of()).contains(reminder.getGuardianId())) {
                continue;
            }
            pendingCounts.merge(reminder.getGuardianId(), 1, Integer::sum);
        }
        if (pendingCounts.isEmpty()) {
            return List.of();
        }

        LocalDate today = AnomalyReviewClock.toDate(now);
        Set<String> alreadySent = summaryLogRepository
                .findBySummaryDateAndGuardianIdIn(today, pendingCounts.keySet()).stream()
                .map(AnomalyReviewSummaryLog::getGuardianId)
                .collect(Collectors.toSet());
        Set<String> disabled = disabledGuardians(pendingCounts.keySet());

        List<AnomalyReviewSummaryLog> logs = new ArrayList<>();
        List<AnomalyReviewSummaryTarget> targets = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : pendingCounts.entrySet()) {
            String guardianId = entry.getKey();
            if (alreadySent.contains(guardianId) || disabled.contains(guardianId)) {
                continue;
            }
            logs.add(AnomalyReviewSummaryLog.builder()
                    .guardianId(guardianId)
                    .summaryDate(today)
                    .pendingCount(entry.getValue())
                    .sentAt(now)
                    .build());
            targets.add(new AnomalyReviewSummaryTarget(guardianId, today, entry.getValue()));
        }
        if (targets.isEmpty()) {
            return List.of();
        }
        summaryLogRepository.saveAll(logs);
        return targets;
    }

    private boolean isQuietHours(OffsetDateTime now) {
        AnomalyProperties.ReviewReminder config = properties.getReviewReminder();
        return AnomalyReviewClock.isQuietHours(
                AnomalyReviewClock.toTime(now), config.getQuietStart(), config.getQuietEnd());
    }

    /** 이미 응답한 (상황, 보호자) 조합. */
    private Set<String> answeredKeys(List<Long> incidentIds) {
        return feedbackRepository.findByIncidentIdIn(incidentIds).stream()
                .map(feedback -> key(feedback.getIncidentId(), feedback.getGuardianId()))
                .collect(Collectors.toSet());
    }

    /**
     * 후보 상황들의 피보호자별 ACTIVE 보호자.
     *
     * <p>연결이 해제되면 그 보호자는 이력 자체를 볼 수 없으므로 재촉도 하지 않는다
     * ("ACTIVE 연결이 유일한 열람 근거").</p>
     */
    private Map<String, List<String>> activeGuardiansByWard(List<AnomalyIncident> incidents) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String wardId : incidents.stream().map(AnomalyIncident::getWardId).collect(Collectors.toCollection(LinkedHashSet::new))) {
            result.put(wardId, connectionService.getActiveGuardianIds(wardId));
        }
        return result;
    }

    /** 수신 설정을 <b>명시적으로 끈</b> 보호자. 행이 없으면 기본값 ON이라 여기 포함되지 않는다. */
    private Set<String> disabledGuardians(Set<String> guardianIds) {
        return settingRepository.findByGuardianIdIn(guardianIds).stream()
                .filter(setting -> !setting.isReviewReminderEnabled())
                .map(GuardianAnomalySetting::getGuardianId)
                .collect(Collectors.toSet());
    }

    /** ⚠️ 빈 목록에 {@code Map.of()}를 쓰면 뒤의 {@code getOrDefault(null)}에서 NPE가 난다. */
    private Map<String, String> wardNames(Set<String> wardIds) {
        List<User> wards = userRepository.findAllById(wardIds);
        if (wards.isEmpty()) {
            return Collections.emptyMap();
        }
        return wards.stream()
                .filter(user -> user.getName() != null && !user.getName().isBlank())
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    private static String key(Long incidentId, String guardianId) {
        return incidentId + "|" + guardianId;
    }

    /** 선점이 확정된 한 건(문구 재료 조회 전 중간 상태). */
    private record Claim(String guardianId, AnomalyIncident incident) {
    }

}
