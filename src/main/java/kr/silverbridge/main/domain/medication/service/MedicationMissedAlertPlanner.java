package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationMissedAlertLog;
import kr.silverbridge.main.domain.medication.repository.GuardianMedicationSettingRepository;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 미복용 요약 알림 대상을 고르고 <b>발송 기록을 먼저 선점</b>하는 컴포넌트.
 * 실제 발송은 {@link MedicationMissedAlertService}가 이 트랜잭션 커밋 뒤에 한다(2차와 동일한 순서).
 *
 * <p><b>집계 상한 = 그 보호자의 발송 시각</b>이다. 발송 시각까지 복용 시각이 지난 약만 센다 -
 * 취침 전 22:00 약은 21:00 요약에서 아직 먹을 때가 아니므로 빠진다. 포함하면 매일 거짓 알림이 나간다.
 * 그래서 문구의 분모는 "오늘 전체"가 아니라 "지정 시각까지 예정된" 수다.</p>
 *
 * <p><b>보호자마다 시각이 다르다</b>(2026-08-27). 같은 피보호자라도 19:00을 고른 보호자와 21:00을
 * 고른 보호자는 분모가 다를 수 있어, 집계를 피보호자 단위로 미리 확정하지 못한다. 그래서
 * "지금까지 지난 약"을 한 번 상위집합으로 읽고 보호자별 상한으로 걸러 센다 - 발송 창 안에 있는
 * 보호자의 시각은 언제나 현재 시각 이하라 상위집합이 각자의 집계 대상을 모두 포함한다.</p>
 *
 * <p><b>수신자</b>는 그 피보호자의 ACTIVE 보호자 중 수신 설정이 켜진 사람이다. 피보호자 본인에게는
 * 보내지 않는다 - 본인은 이미 복용 시각 알림과 재알림(2차)을 받았다.</p>
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
    private final GuardianMedicationSettingRepository guardianSettingRepository;
    private final GuardianMedicationSettingService guardianSettingService;
    private final ConnectionService connectionService;
    private final UserRepository userRepository;
    private final MedicationProperties properties;

    /**
     * 오늘 체크되지 않은 약이 있는 피보호자의 보호자에게 보낼 요약을 선점한다.
     *
     * <p>어느 보호자도 발송 창 안에 없거나 미체크가 없으면 빈 목록이다.</p>
     */
    @Transactional
    public List<MedicationMissedAlertTarget> claimMissedAlerts() {
        OffsetDateTime now = MedicationClock.now();
        LocalTime nowTime = now.toLocalTime();
        if (!isAnyGuardianDue(nowTime)) {
            return List.of();
        }

        LocalDate today = MedicationClock.today();

        // 상위집합 - 지금까지 복용 시각이 지난 약 전부. 보호자별 상한으로 다시 거른다.
        List<Medication> due = medicationRepository.findByDeletedAtIsNullAndDoseTimeLessThanEqual(nowTime);
        if (due.isEmpty()) {
            return List.of();
        }

        Set<Long> taken = intakeRepository
                .findByMedicationIdInAndDoseDate(due.stream().map(Medication::getId).toList(), today).stream()
                .map(MedicationIntake::getMedicationId)
                .collect(Collectors.toSet());

        // 미체크가 하나도 없는 피보호자는 어떤 상한을 잡아도 대상이 아니므로 여기서 걸러 낸다
        // (보호자 조회·설정 조회를 아끼는 것이 목적이라 상한과 무관한 이 단계에서 판단할 수 있다).
        Map<String, List<Medication>> byWard = due.stream()
                .collect(Collectors.groupingBy(Medication::getWardId, LinkedHashMap::new, Collectors.toList()));
        byWard.values().removeIf(medications -> medications.stream().allMatch(m -> taken.contains(m.getId())));
        if (byWard.isEmpty()) {
            return List.of();
        }

        Set<String> alreadySent = missedAlertLogRepository
                .findByDoseDateAndWardIdIn(today, List.copyOf(byWard.keySet())).stream()
                .map(log -> sentKey(log.getGuardianId(), log.getWardId()))
                .collect(Collectors.toSet());

        GuardianMissedAlertSetting defaultSetting = guardianSettingService.defaultSetting();
        List<MedicationMissedAlertLog> logs = new ArrayList<>();
        List<Claim> claims = new ArrayList<>();
        for (Map.Entry<String, List<Medication>> entry : byWard.entrySet()) {
            String wardId = entry.getKey();
            List<String> guardianIds = connectionService.getActiveGuardianIds(wardId);
            if (guardianIds.isEmpty()) {
                continue;
            }
            // 이미 보낸 보호자를 설정 조회 "전에" 걸러 낸다 - 미체크 약은 그대로 남아 이 피보호자가 발송 창
            // 내내 목록에 있으므로, 필터가 뒤에 있으면 매 분 설정 조회가 헛돈다.
            // ※ getActiveGuardianIds는 남겨 둔다 - "로그가 있으면 이 피보호자는 끝났다"고 단정하면
            //   발송 시각 이후 새로 연결된 보호자가 그날 요약을 못 받는다(정확성 > 조회 1건).
            List<String> pending = guardianIds.stream()
                    .filter(guardianId -> !alreadySent.contains(sentKey(guardianId, wardId)))
                    .toList();
            if (pending.isEmpty()) {
                continue;
            }
            Map<String, GuardianMissedAlertSetting> settings = guardianSettingService.findSettings(pending);

            for (String guardianId : pending) {
                // 저장된 행이 없는 보호자는 기본값(ON · 전역 기본 시각)으로 받는다.
                GuardianMissedAlertSetting setting = settings.getOrDefault(guardianId, defaultSetting);
                if (!setting.enabled() || !isWithinSendWindow(nowTime, setting.alertTime())) {
                    continue;
                }
                Counts counts = count(entry.getValue(), taken, setting.alertTime());
                if (counts.missed() == 0) {
                    continue;
                }
                logs.add(MedicationMissedAlertLog.of(
                        guardianId, wardId, today, counts.missed(), counts.total(), now));
                claims.add(new Claim(guardianId, wardId, setting.alertTime(), counts));
            }
        }

        if (claims.isEmpty()) {
            return List.of();
        }
        missedAlertLogRepository.saveAll(logs);

        // 이름은 실제로 발송할 피보호자만 조회한다.
        // ⚠️ 빈 목록에 Map.of()를 반환하면 뒤의 getOrDefault(null 키)에서 NPE가 나므로 emptyMap을 쓴다.
        Map<String, String> wardNames = wardNames(claims);
        return claims.stream()
                .map(claim -> new MedicationMissedAlertTarget(
                        claim.guardianId(), claim.wardId(),
                        wardNames.getOrDefault(claim.wardId(), FALLBACK_WARD_NAME),
                        today, claim.alertTime(), claim.counts().missed(), claim.counts().total()))
                .toList();
    }

    /** 상한까지 예정된 약의 (전체, 미체크) 수. */
    private Counts count(List<Medication> medications, Set<Long> taken, LocalTime cutoff) {
        int total = 0;
        int missed = 0;
        for (Medication medication : medications) {
            if (medication.getDoseTime().isAfter(cutoff)) {
                continue;
            }
            total++;
            if (!taken.contains(medication.getId())) {
                missed++;
            }
        }
        return new Counts(total, missed);
    }

    private Map<String, String> wardNames(List<Claim> claims) {
        Set<String> wardIds = claims.stream().map(Claim::wardId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<User> wards = userRepository.findAllById(wardIds);
        if (wards.isEmpty()) {
            return Collections.emptyMap();
        }
        return wards.stream().collect(Collectors.toMap(User::getId, User::getName));
    }

    /**
     * 지금 발송 창 안에 있을 수 있는 보호자가 하나라도 있는지.
     *
     * <p>보호자마다 시각이 달라 "판정 시각 전"을 하나의 값으로 판단할 수 없다. 대신 설정 테이블의
     * 최이른·최늦은 시각과 기본값으로 전체 구간을 잡아, 그 밖이면 <b>복약 테이블을 훑지 않고</b> 끝낸다.
     * 이 게이트가 없으면 스케줄러가 하루 종일 매 분 복약 전체를 조회한다.</p>
     */
    private boolean isAnyGuardianDue(LocalTime now) {
        LocalTime defaultAlertTime = properties.getMissedAlert().getAlertTime();
        LocalTime earliest = min(defaultAlertTime, guardianSettingRepository.findEarliestAlertTime().orElse(null));
        if (now.isBefore(earliest)) {
            return false;
        }
        LocalTime latest = max(defaultAlertTime, guardianSettingRepository.findLatestAlertTime().orElse(null));
        return !isAfterSendWindow(now, latest);
    }

    /** 발송 창 {@code [alertTime, alertTime+마감]} 안인지. */
    private boolean isWithinSendWindow(LocalTime now, LocalTime alertTime) {
        return !now.isBefore(alertTime) && !isAfterSendWindow(now, alertTime);
    }

    /**
     * 발송 마감을 지났는지. 마감이 자정을 넘기면 그날 끝에서 끊는다 -
     * 날짜가 바뀌면 요약 대상(오늘 복용분) 자체가 달라지기 때문이다.
     */
    private boolean isAfterSendWindow(LocalTime now, LocalTime alertTime) {
        LocalTime end = alertTime.plusMinutes(properties.getMissedAlert().getDeadlineMinutes());
        if (!end.isAfter(alertTime)) {
            return false;   // 자정을 넘겼다 → 그날 안에서는 마감 전
        }
        return now.isAfter(end);
    }

    private static LocalTime min(LocalTime base, LocalTime candidate) {
        return candidate != null && candidate.isBefore(base) ? candidate : base;
    }

    private static LocalTime max(LocalTime base, LocalTime candidate) {
        return candidate != null && candidate.isAfter(base) ? candidate : base;
    }

    private static String sentKey(String guardianId, String wardId) {
        return guardianId + "|" + wardId;
    }

    /** 선점이 확정된 한 건(이름 조회 전 중간 상태). */
    private record Claim(String guardianId, String wardId, LocalTime alertTime, Counts counts) {}

    private record Counts(int total, int missed) {}
}
