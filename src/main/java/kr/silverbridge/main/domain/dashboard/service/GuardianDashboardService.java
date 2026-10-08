package kr.silverbridge.main.domain.dashboard.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.dto.GuardianAnomalyHistorySummary;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalyService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.dashboard.dto.GuardianDashboardResponse;
import kr.silverbridge.main.domain.dashboard.dto.GuardianDashboardResponse.AnomalyDetection;
import kr.silverbridge.main.domain.dashboard.dto.GuardianDashboardResponse.Medication;
import kr.silverbridge.main.domain.dashboard.dto.GuardianDashboardResponse.PendingActions;
import kr.silverbridge.main.domain.dashboard.dto.GuardianDashboardResponse.Sos;
import kr.silverbridge.main.domain.dashboard.dto.GuardianDashboardResponse.UncheckedMedication;
import kr.silverbridge.main.domain.dashboard.dto.GuardianDashboardResponse.WardChip;
import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.dto.WardMedicationSummary;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationService;
import kr.silverbridge.main.domain.medication.service.MedicationClock;
import kr.silverbridge.main.domain.sos.service.GuardianSosService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 보호자 대시보드 통합 조회. 이상감지·SOS·복약 서비스의 조회 메서드를 <b>호출만</b> 한다(각 도메인의 인가·집계 규칙을
 * 다시 구현하지 않는다). 사용자(보호자) ID는 호출부가 토큰에서 넘긴 값만 쓴다.
 *
 * <p><b>인가</b>: {@code wardId}를 지정하면 {@code isActiveConnection}, 생략하면 {@code getActiveWardIds}
 * ({@code getMyWards}는 PENDING이 섞여 금지). 위반은 403 + {@code [IDOR-ATTEMPT]}. 하위 서비스는 같은 기준으로 한 번 더
 * 인가하므로 인가가 두 겹이다.</p>
 *
 * <p><b>칸별 실패 격리</b>: 한 칸이 실패해도 나머지는 내려간다. 실패한 칸은 0이 아니라 {@code null}이다(모르는 값을 0으로
 * 채우지 않는다). 이 서비스에 {@code @Transactional}을 걸지 않는다 - 하위 readOnly 트랜잭션이 예외로 rollback-only가 되면
 * 바깥 트랜잭션이 커밋 때 UnexpectedRollbackException을 던져 칸별 격리가 깨진다. 하위 서비스가 각자 읽기 전용 트랜잭션을 연다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianDashboardService {

    static final String SECTION_ANOMALY = "anomalyDetection";
    static final String SECTION_SOS = "sos";
    static final String SECTION_MEDICATION = "medication";

    private final ConnectionService connectionService;
    private final GuardianAnomalyService anomalyService;
    private final GuardianSosService sosService;
    private final GuardianMedicationService medicationService;
    private final UserRepository userRepository;

    /**
     * @param wardId {@code null}·공백이면 ACTIVE 연결된 피보호자 전원
     * @throws CustomException {@code DASHBOARD_NOT_AUTHORIZED} - wardId를 지정했으나 ACTIVE 연결이 아닐 때
     */
    public GuardianDashboardResponse getDashboard(String guardianId, String wardId) {
        List<String> wardIds = resolveVisibleWardIds(guardianId, wardId);
        if (wardIds.isEmpty()) {
            return new GuardianDashboardResponse(new PendingActions(0L, 0L, 0L), List.of(), null, null, null, List.of());
        }
        String scope = StringUtils.hasText(wardId) ? wardId : null;
        List<String> unavailable = new ArrayList<>();

        // 이상감지: 피보호자별 건수(칩 배지용) + 최신 확인 필요 1건
        Map<String, Long> anomalyByWard = attempt(SECTION_ANOMALY, unavailable, () -> {
            Map<String, Long> counts = new HashMap<>();
            for (String id : wardIds) {
                GuardianAnomalyHistorySummary summary = anomalyService.getHistorySummary(guardianId, id);
                counts.put(id, summary.needsReviewCount());
            }
            return counts;
        });
        AnomalyIncidentItem latestAnomaly = anomalyByWard == null ? null
                : attempt(SECTION_ANOMALY, unavailable, () -> anomalyService.getLatestNeedsReview(guardianId, scope));
        boolean anomalyOk = anomalyByWard != null && !unavailable.contains(SECTION_ANOMALY);

        Sos sos = attempt(SECTION_SOS, unavailable, () -> {
            GuardianSosService.RecentSos recent = sosService.getRecent(guardianId, scope, monthStart(MedicationClock.today()));
            return new Sos(recent.count(), recent.latest());
        });

        // 복약: 서비스가 ACTIVE 피보호자 전원을 돌려주므로 인가된 범위로 좁힌다
        Map<String, List<UncheckedMedication>> uncheckedByWard = attempt(SECTION_MEDICATION, unavailable, () -> {
            LocalTime now = MedicationClock.toKst(MedicationClock.now()).toLocalTime();
            Map<String, List<UncheckedMedication>> result = new HashMap<>();
            for (WardMedicationSummary summary : medicationService.getWardMedications(guardianId)) {
                if (!wardIds.contains(summary.wardId())) {
                    continue;
                }
                result.put(summary.wardId(), uncheckedOf(summary, now));
            }
            return result;
        });
        boolean medicationOk = uncheckedByWard != null;

        AnomalyDetection anomalyDetection = anomalyOk ? new AnomalyDetection(sum(anomalyByWard), latestAnomaly) : null;
        Medication medication = medicationOk ? toMedication(uncheckedByWard) : null;

        Long anomalyTotal = anomalyOk ? anomalyDetection.needsReviewCount() : null;
        Long medicationTotal = medicationOk ? medication.uncheckedCount() : null;
        PendingActions pending = new PendingActions(
                anomalyOk && medicationOk ? anomalyTotal + medicationTotal : null, anomalyTotal, medicationTotal);

        return new GuardianDashboardResponse(
                pending,
                chips(wardIds, anomalyOk ? anomalyByWard : null, medicationOk ? uncheckedByWard : null),
                anomalyDetection, sos, medication,
                unavailable.stream().distinct().toList());
    }

    /** 이번 달 1일 00:00(KST). KST는 일광절약이 없어 고정 오프셋으로 충분하다. */
    static OffsetDateTime monthStart(LocalDate todayKst) {
        return todayKst.withDayOfMonth(1).atStartOfDay().atOffset(ZoneOffset.ofHours(9));
    }

    /** 복용 시각이 지났는데 체크되지 않은 약, 복용 시각 순. 복용 시각 정각은 "지난 것"으로 센다. */
    static List<UncheckedMedication> uncheckedOf(WardMedicationSummary summary, LocalTime nowKst) {
        return summary.medications().stream()
                .filter(item -> !item.taken() && !item.doseTime().isAfter(nowKst))
                .sorted(Comparator.comparing(MedicationItem::doseTime))
                .map(item -> new UncheckedMedication(summary.wardId(), summary.wardName(),
                        item.medicationId(), item.name(), item.timeSlot(), item.doseTime()))
                .toList();
    }

    private static Medication toMedication(Map<String, List<UncheckedMedication>> byWard) {
        List<UncheckedMedication> all = byWard.values().stream().flatMap(List::stream).toList();
        Optional<UncheckedMedication> first = all.stream().min(Comparator.comparing(UncheckedMedication::doseTime));
        return new Medication(all.size(), first.orElse(null));
    }

    private List<WardChip> chips(List<String> wardIds, Map<String, Long> anomalyByWard,
                                 Map<String, List<UncheckedMedication>> uncheckedByWard) {
        // 이름은 보조 정보라 조회가 실패해도 칩은 내려간다(이름 null)
        Map<String, String> names = Optional.ofNullable(attempt("wardNames", new ArrayList<>(),
                () -> userRepository.findAllById(wardIds).stream()
                        .filter(user -> StringUtils.hasText(user.getName()))
                        .collect(Collectors.toMap(User::getId, User::getName)))).orElse(Map.of());
        return wardIds.stream()
                .map(id -> new WardChip(id, names.get(id),
                        anomalyByWard == null || uncheckedByWard == null ? null
                                : anomalyByWard.getOrDefault(id, 0L) + uncheckedByWard.getOrDefault(id, List.of()).size()))
                .toList();
    }

    private static long sum(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    /** 칸 하나를 실행하고, 실패하면 null + 칸 이름 기록. 로그에는 칸 이름과 예외 클래스명만 남긴다. */
    private <T> T attempt(String section, List<String> unavailable, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            log.warn("[DASHBOARD-SECTION-FAILED] section={}, error={}", section, e.getClass().getSimpleName());
            unavailable.add(section);
            return null;
        }
    }

    private List<String> resolveVisibleWardIds(String guardianId, String wardId) {
        if (!StringUtils.hasText(wardId)) {
            return connectionService.getActiveWardIds(guardianId);
        }
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 대시보드 조회 시도: guardianId={}, wardId={}", guardianId, wardId);
            throw new CustomException(ErrorCode.DASHBOARD_NOT_AUTHORIZED);
        }
        return List.of(wardId);
    }
}
