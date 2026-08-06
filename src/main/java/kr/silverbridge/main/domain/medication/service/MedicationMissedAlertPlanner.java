package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationMissedAlertLog;
import kr.silverbridge.main.domain.medication.repository.MedicationIntakeRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationMissedAlertLogRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 미복용 요약 알림 대상을 고르고 <b>발송 기록을 먼저 선점</b>하는 컴포넌트.
 * 실제 발송은 {@link MedicationMissedAlertService}가 이 트랜잭션 커밋 뒤에 한다(2차와 동일한 순서).
 *
 * <p><b>집계 범위</b>: 판정 시각({@code alertTime}, 기본 21:00)<b>까지 복용 시각이 지난 약만</b> 센다.
 * 취침 전 22:00 약은 아직 먹을 때가 아니므로 제외한다 — 포함하면 매일 거짓 알림이 나간다.
 * 그래서 문구의 분모는 "오늘 전체"가 아니라 "저녁까지 예정된" 수다.</p>
 *
 * <p><b>수신자</b>는 그 피보호자의 ACTIVE 보호자 중 수신 설정이 켜진 사람이다. 피보호자 본인에게는
 * 보내지 않는다 — 본인은 이미 복용 시각 알림과 재알림(2차)을 받았다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationMissedAlertPlanner {

    /** 이름을 못 찾은 피보호자의 표시용 폴백(문구에 null이 들어가지 않게). */
    private static final String FALLBACK_WARD_NAME = "피보호자";

    private final MedicationRepository medicationRepository;
    private final MedicationIntakeRepository intakeRepository;
    private final MedicationMissedAlertLogRepository missedAlertLogRepository;
    private final GuardianMedicationSettingService guardianSettingService;
    private final ConnectionService connectionService;
    private final UserRepository userRepository;
    private final MedicationProperties properties;

    /**
     * 오늘 체크되지 않은 약이 있는 피보호자의 보호자에게 보낼 요약을 선점한다.
     *
     * <p>발송 창을 벗어났거나(판정 시각 전 / 마감 후) 미체크가 없으면 빈 목록이다.</p>
     */
    @Transactional
    public List<MedicationMissedAlertTarget> claimMissedAlerts() {
        OffsetDateTime now = MedicationClock.now();
        if (!isWithinSendWindow(now.toLocalTime())) {
            return List.of();
        }

        LocalDate today = MedicationClock.today();
        LocalTime alertTime = properties.getMissedAlert().getAlertTime();

        List<Medication> due = medicationRepository.findByDeletedAtIsNullAndDoseTimeLessThanEqual(alertTime);
        if (due.isEmpty()) {
            return List.of();
        }

        Set<Long> taken = intakeRepository
                .findByMedicationIdInAndDoseDate(due.stream().map(Medication::getId).toList(), today).stream()
                .map(MedicationIntake::getMedicationId)
                .collect(Collectors.toSet());

        // 피보호자별 (예정 수, 미체크 수) 집계 — 미체크가 하나도 없는 피보호자는 대상에서 빠진다.
        Map<String, int[]> countsByWard = new java.util.LinkedHashMap<>();
        for (Medication medication : due) {
            int[] counts = countsByWard.computeIfAbsent(medication.getWardId(), key -> new int[2]);
            counts[0]++;
            if (!taken.contains(medication.getId())) {
                counts[1]++;
            }
        }
        List<String> wardIds = countsByWard.entrySet().stream()
                .filter(entry -> entry.getValue()[1] > 0)
                .map(Map.Entry::getKey)
                .toList();
        if (wardIds.isEmpty()) {
            return List.of();
        }

        Set<String> alreadySent = missedAlertLogRepository.findByDoseDateAndWardIdIn(today, wardIds).stream()
                .map(log -> sentKey(log.getGuardianId(), log.getWardId()))
                .collect(Collectors.toSet());
        Map<String, String> wardNames = userRepository.findAllById(wardIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        List<MedicationMissedAlertLog> logs = new ArrayList<>();
        List<MedicationMissedAlertTarget> claimed = new ArrayList<>();
        for (String wardId : wardIds) {
            List<String> guardianIds = connectionService.getActiveGuardianIds(wardId);
            if (guardianIds.isEmpty()) {
                continue;
            }
            // 이미 보낸 보호자를 설정 조회 "전에" 걸러 낸다 — 미체크 약은 그대로 남아 이 피보호자가 발송 창
            // (기본 120분) 내내 목록에 있으므로, 필터가 뒤에 있으면 매 분 설정 조회가 헛돌았다.
            // ※ getActiveGuardianIds는 남겨 둔다 — "로그가 있으면 이 피보호자는 끝났다"고 단정하면
            //   21시 이후 새로 연결된 보호자가 그날 요약을 못 받는다(정확성 > 조회 1건).
            List<String> pending = guardianIds.stream()
                    .filter(guardianId -> !alreadySent.contains(sentKey(guardianId, wardId)))
                    .toList();
            if (pending.isEmpty()) {
                continue;
            }
            Map<String, Boolean> enabled = guardianSettingService.findMissedAlertEnabled(pending);
            int[] counts = countsByWard.get(wardId);

            for (String guardianId : pending) {
                if (!enabled.getOrDefault(guardianId, GuardianMedicationSettingService.defaultMissedAlertEnabled())) {
                    continue;
                }
                logs.add(MedicationMissedAlertLog.of(guardianId, wardId, today, counts[1], counts[0], now));
                claimed.add(new MedicationMissedAlertTarget(
                        guardianId, wardId,
                        wardNames.getOrDefault(wardId, FALLBACK_WARD_NAME),
                        today, counts[1], counts[0]));
            }
        }

        if (!logs.isEmpty()) {
            missedAlertLogRepository.saveAll(logs);
        }
        return claimed;
    }

    /**
     * 발송 창 안인지. {@code [alertTime, alertTime+마감]}이며, 마감이 자정을 넘기면 그날 끝에서 끊는다 —
     * 날짜가 바뀌면 요약 대상(오늘 복용분) 자체가 달라지기 때문이다.
     */
    private boolean isWithinSendWindow(LocalTime now) {
        LocalTime alertTime = properties.getMissedAlert().getAlertTime();
        if (now.isBefore(alertTime)) {
            return false;
        }
        LocalTime end = alertTime.plusMinutes(properties.getMissedAlert().getDeadlineMinutes());
        boolean wrapped = !end.isAfter(alertTime);
        return wrapped || !now.isAfter(end);
    }

    private static String sentKey(String guardianId, String wardId) {
        return guardianId + "|" + wardId;
    }
}
