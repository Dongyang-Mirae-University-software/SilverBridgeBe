package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationReminderLog;
import kr.silverbridge.main.domain.medication.repository.MedicationIntakeRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationReminderLogRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
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
import java.util.stream.Collectors;

/**
 * 보낼 알림을 골라 <b>발송 기록을 먼저 선점</b>하는 컴포넌트. 실제 발송은
 * {@link MedicationReminderService}가 이 트랜잭션이 커밋된 뒤에 한다.
 *
 * <p><b>왜 선점하고 보내는가</b>: 스케줄러는 1분마다 돌기 때문에 기록 없이 보내면 유예 창 내내 같은 알림이
 * 반복된다. 발송 후 기록하는 순서라면 발송 직후 앱이 죽었을 때 다음 주기에 또 보낸다. 그래서 기록을 먼저
 * 커밋하고 보낸다 — 발송이 실패하면 그 회차는 유실되지만, 알림이 두 번 가는 쪽이 더 나쁘고 재알림이
 * 두 번째 기회가 된다.</p>
 *
 * <p><b>UNIQUE는 최종 방어선</b>: {@code (medication_id, dose_date, attempt)} 제약이 있어 사전 조회가
 * 놓친 중복은 저장 시점에 막힌다. 그 경우 이 주기 전체가 롤백되지만, 중복을 만든 쪽이 이미 커밋했으므로
 * 다음 주기의 사전 조회에서 걸러져 스스로 회복된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationReminderPlanner {

    private final MedicationRepository medicationRepository;
    private final MedicationIntakeRepository intakeRepository;
    private final MedicationReminderLogRepository reminderLogRepository;
    private final MedicationSettingService settingService;
    private final MedicationProperties properties;

    /**
     * 복용 시각이 도래했는데 아직 체크되지 않은 약의 <b>최초 알림</b>을 선점한다.
     *
     * <p>대상 조건 — 삭제되지 않음 && 복용 시각이 유예 창 안 && 오늘 미체크 && 알림 설정 ON && 미발송.</p>
     */
    @Transactional
    public List<MedicationReminderTarget> claimFirstReminders() {
        LocalDate today = MedicationClock.today();
        OffsetDateTime now = MedicationClock.now();

        List<Medication> due = medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(
                graceWindowStart(now.toLocalTime(), properties.getGraceMinutes()), now.toLocalTime());
        if (due.isEmpty()) {
            return List.of();
        }

        List<Long> medicationIds = due.stream().map(Medication::getId).toList();
        Set<Long> taken = takenMedicationIds(medicationIds, today);
        Set<Long> alreadySent = sentMedicationIds(medicationIds, today, MedicationReminderLog.ATTEMPT_FIRST);
        Map<String, MedicationPreference> preferences = settingService.findPreferences(
                due.stream().map(Medication::getWardId).collect(Collectors.toSet()));

        List<Medication> targets = due.stream()
                .filter(medication -> !taken.contains(medication.getId()))
                .filter(medication -> !alreadySent.contains(medication.getId()))
                .filter(medication -> preference(preferences, medication.getWardId()).alarmEnabled())
                .toList();

        return claim(targets, today, now, MedicationReminderLog.ATTEMPT_FIRST);
    }

    /**
     * 최초 알림 후에도 체크되지 않은 약의 <b>재알림</b>을 선점한다.
     *
     * <p>대상 조건 — 최초 발송이 {@code [now-마감, now-지연]} 구간 && 재알림 미발송 && 여전히 미체크
     * && 약이 살아 있음 && 알림·재알림 설정 모두 ON.</p>
     */
    @Transactional
    public List<MedicationReminderTarget> claimRetryReminders() {
        LocalDate today = MedicationClock.today();
        OffsetDateTime now = MedicationClock.now();
        OffsetDateTime sentUntil = now.minusMinutes(properties.getRetryDelayMinutes());
        OffsetDateTime sentFrom = now.minusMinutes(properties.getRetryDeadlineMinutes());
        if (!sentFrom.isBefore(sentUntil)) {
            // 마감이 지연보다 짧게 설정된 경우 — 재알림 구간이 성립하지 않는다.
            return List.of();
        }

        List<MedicationReminderLog> firstSent =
                reminderLogRepository.findRetryCandidates(today, sentFrom, sentUntil);
        if (firstSent.isEmpty()) {
            return List.of();
        }

        List<Long> medicationIds = firstSent.stream().map(MedicationReminderLog::getMedicationId).toList();
        Set<Long> taken = takenMedicationIds(medicationIds, today);
        List<Medication> alive = medicationRepository.findAllById(medicationIds).stream()
                .filter(medication -> !medication.isDeleted())
                .toList();
        Map<String, MedicationPreference> preferences = settingService.findPreferences(
                alive.stream().map(Medication::getWardId).collect(Collectors.toSet()));

        List<Medication> targets = alive.stream()
                .filter(medication -> !taken.contains(medication.getId()))
                .filter(medication -> {
                    MedicationPreference preference = preference(preferences, medication.getWardId());
                    return preference.alarmEnabled() && preference.remindAgainEnabled();
                })
                .toList();

        return claim(targets, today, now, MedicationReminderLog.ATTEMPT_RETRY);
    }

    /** 발송 기록을 남기고(선점) 발송 단계로 넘길 값을 만든다. */
    private List<MedicationReminderTarget> claim(List<Medication> targets, LocalDate doseDate,
                                                 OffsetDateTime now, int attempt) {
        if (targets.isEmpty()) {
            return List.of();
        }
        List<MedicationReminderLog> logs = new ArrayList<>(targets.size());
        List<MedicationReminderTarget> claimed = new ArrayList<>(targets.size());
        for (Medication medication : targets) {
            logs.add(MedicationReminderLog.of(medication.getId(), doseDate, attempt, now));
            claimed.add(new MedicationReminderTarget(
                    medication.getId(),
                    medication.getWardId(),
                    medication.getName(),
                    medication.getTimeSlot(),
                    medication.getDoseTime(),
                    medication.getDoseAmount(),
                    doseDate,
                    attempt));
        }
        reminderLogRepository.saveAll(logs);
        return claimed;
    }

    /**
     * 유예 창의 시작 시각. 자정을 넘겨 되감지 않는다 — 하루 경계를 넘으면 {@code dose_date}가 달라져
     * "어제 약을 오늘 날짜로" 보내는 셈이 되므로, 00:00에서 잘라 그 건은 건너뛴다(불변 규칙 ⑥).
     *
     * <p><b>시각을 인자로 받는 static</b>이다 — 이 경계 규칙이 현재 시각에 의존하면 자정 분기가
     * "테스트가 00:00~00:30에 돌 때만" 검증되어 회귀를 놓친다. 시각과 분리해 두면 경계값을
     * 리터럴로 직접 검증할 수 있다.</p>
     */
    static LocalTime graceWindowStart(LocalTime now, long graceMinutes) {
        long minutesFromMidnight = now.toSecondOfDay() / 60L;
        return minutesFromMidnight <= graceMinutes ? LocalTime.MIN : now.minusMinutes(graceMinutes);
    }

    private Set<Long> takenMedicationIds(List<Long> medicationIds, LocalDate doseDate) {
        return intakeRepository.findByMedicationIdInAndDoseDate(medicationIds, doseDate).stream()
                .map(MedicationIntake::getMedicationId)
                .collect(Collectors.toSet());
    }

    private Set<Long> sentMedicationIds(List<Long> medicationIds, LocalDate doseDate, int attempt) {
        return reminderLogRepository.findByMedicationIdInAndDoseDate(medicationIds, doseDate).stream()
                .filter(log -> log.getAttempt() == attempt)
                .map(MedicationReminderLog::getMedicationId)
                .collect(Collectors.toSet());
    }

    private static MedicationPreference preference(Map<String, MedicationPreference> preferences, String wardId) {
        return preferences.getOrDefault(wardId, MedicationPreference.DEFAULT);
    }
}
