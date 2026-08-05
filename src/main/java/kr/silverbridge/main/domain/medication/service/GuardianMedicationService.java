package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.dto.MedicationCreateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.dto.MedicationSettingResponse;
import kr.silverbridge.main.domain.medication.dto.MedicationUpdateRequest;
import kr.silverbridge.main.domain.medication.dto.WardMedicationSummary;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.repository.MedicationIntakeRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationReminderLogRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 보호자용 복약 관리 서비스 — 피보호자별 현황 조회, 약 등록·삭제, 알림 토글.
 *
 * <p><b>인가 원칙</b>: 보호자는 <b>요청 시점에 ACTIVE 연결</b>인 피보호자의 복약 정보만 보고 관리할 수 있다.
 * 연결이 해제·거절되면 과거 복약 정보도 즉시 보이지 않는다(연결 종료 후 개인정보 잔존 방지 — SOS 이력과
 * 동일한 판단). 인가 목록은 {@code getActiveWardIds}·{@code isActiveConnection}만 쓴다 —
 * {@code getMyWards}는 PENDING이 섞여 있어 수락 전 피보호자의 정보가 노출된다.</p>
 *
 * <p><b>복용 체크는 이 서비스에 없다</b>. 체크·해제는 피보호자 전용 경로({@link WardMedicationService})에만
 * 존재하며, 보호자 화면의 체크 표시는 읽기 전용이다 — "피보호자가 체크해야 보호자에게 보인다"는 요구를
 * 엔드포인트 구조로 보장한다. 편의 목적으로도 보호자용 체크 API를 추가하지 말 것.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianMedicationService {

    private final MedicationRepository medicationRepository;
    private final MedicationIntakeRepository intakeRepository;
    private final MedicationReminderLogRepository reminderLogRepository;
    private final MedicationSettingService settingService;
    private final ConnectionService connectionService;
    private final UserRepository userRepository;

    /**
     * ACTIVE 연결된 피보호자 전원의 오늘 복약 현황을 반환한다(화면의 피보호자 카드 목록).
     * 연결된 피보호자가 없으면 빈 목록이다.
     */
    @Transactional(readOnly = true)
    public List<WardMedicationSummary> getWardMedications(String guardianId) {
        List<String> wardIds = connectionService.getActiveWardIds(guardianId);
        if (wardIds.isEmpty()) {
            return List.of();
        }

        LocalDate today = MedicationClock.today();
        List<Medication> medications =
                medicationRepository.findByWardIdInAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(wardIds);

        Map<Long, MedicationIntake> intakes = findTodayIntakes(medications, today);
        Map<String, User> wards = userRepository.findAllById(wardIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, MedicationPreference> preferences = settingService.findPreferences(wardIds);

        // 약이 하나도 없는 피보호자도 카드는 보여야 하므로 groupingBy 결과가 아니라 wardIds를 기준으로 조립한다.
        Map<String, List<Medication>> byWard = medications.stream()
                .collect(Collectors.groupingBy(Medication::getWardId, LinkedHashMap::new, Collectors.toList()));

        return wardIds.stream()
                .map(wardId -> {
                    List<MedicationItem> items = byWard.getOrDefault(wardId, List.of()).stream()
                            .map(medication -> MedicationItem.of(medication, intakes.get(medication.getId())))
                            .toList();
                    User ward = wards.get(wardId);
                    return WardMedicationSummary.of(
                            wardId,
                            ward != null ? ward.getName() : null,
                            calculateAge(ward, today),
                            preferences.getOrDefault(wardId, MedicationPreference.DEFAULT).alarmEnabled(),
                            today,
                            items);
                })
                .toList();
    }

    /**
     * 피보호자에게 약을 등록한다.
     *
     * @throws CustomException {@code MEDICATION_NOT_AUTHORIZED} ACTIVE 연결이 아닌 피보호자
     */
    @Transactional
    public MedicationItem create(String guardianId, String wardId, MedicationCreateRequest request) {
        requireActiveConnection(guardianId, wardId, "약 등록");

        Medication medication = medicationRepository.save(Medication.builder()
                .wardId(wardId)
                .createdBy(guardianId)
                .name(request.name())
                .timeSlot(request.timeSlot())
                .doseTime(request.resolveDoseTime())
                .doseAmount(request.resolveDoseAmount())
                .memo(request.memo())
                .build());

        log.info("복약 등록: medicationId={}, wardId={}, guardianId={}", medication.getId(), wardId, guardianId);
        // 방금 등록한 약은 오늘 아직 복용 전이다.
        return MedicationItem.of(medication, null);
    }

    /**
     * 약 정보를 부분 수정한다. 전달하지 않은 항목({@code null})은 기존값을 유지한다.
     *
     * <p><b>복용 시각이 실제로 바뀌면 그 약의 당일 발송 기록을 지운다</b> — 그래야 새 시각 기준으로 다시
     * 판정되어 알림이 나간다(08:00을 20:00으로 고쳤는데 오늘 저녁에 안 울리는 문제 방지). 복용 체크는
     * 건드리지 않으므로 이미 드신 약은 여전히 울리지 않는다.</p>
     *
     * @throws CustomException {@code MEDICATION_NOT_FOUND} 없거나 이미 삭제된 약 /
     *                         {@code MEDICATION_NOT_AUTHORIZED} ACTIVE 연결이 아닌 피보호자의 약
     */
    @Transactional
    public MedicationItem update(String guardianId, Long medicationId, MedicationUpdateRequest request) {
        Medication medication = findActiveMedication(medicationId);
        requireActiveConnection(guardianId, medication.getWardId(), "약 수정");

        LocalTime previousDoseTime = medication.getDoseTime();
        MedicationTimeSlot timeSlot = request.timeSlot() != null ? request.timeSlot() : medication.getTimeSlot();
        LocalTime doseTime = resolveDoseTime(request, medication, timeSlot);

        medication.update(
                resolveName(request, medication),
                timeSlot,
                doseTime,
                request.doseAmount() != null ? request.doseAmount() : medication.getDoseAmount(),
                resolveMemo(request, medication));

        LocalDate today = MedicationClock.today();
        if (!doseTime.equals(previousDoseTime)) {
            reminderLogRepository.deleteByMedicationIdAndDoseDate(medicationId, today);
            log.info("복약 시각 변경으로 당일 발송 기록 초기화: medicationId={}, {} → {}",
                    medicationId, previousDoseTime, doseTime);
        }

        log.info("복약 수정: medicationId={}, wardId={}, guardianId={}",
                medicationId, medication.getWardId(), guardianId);

        // 응답은 오늘 복용 체크 상태를 그대로 반영한다(수정이 체크를 지우지 않는다).
        return MedicationItem.of(medication,
                intakeRepository.findByMedicationIdAndDoseDate(medicationId, today).orElse(null));
    }

    /**
     * 약을 삭제한다(soft delete — 지난 복용 이력은 남는다).
     *
     * @throws CustomException {@code MEDICATION_NOT_FOUND} 없거나 이미 삭제된 약 /
     *                         {@code MEDICATION_NOT_AUTHORIZED} ACTIVE 연결이 아닌 피보호자의 약
     */
    @Transactional
    public void delete(String guardianId, Long medicationId) {
        Medication medication = findActiveMedication(medicationId);
        requireActiveConnection(guardianId, medication.getWardId(), "약 삭제");

        medication.delete(MedicationClock.now());
        log.info("복약 삭제: medicationId={}, wardId={}, guardianId={}",
                medicationId, medication.getWardId(), guardianId);
    }

    /** 피보호자의 복약 알림 설정을 조회한다. */
    @Transactional(readOnly = true)
    public MedicationSettingResponse getSetting(String guardianId, String wardId) {
        requireActiveConnection(guardianId, wardId, "복약 알림 설정 조회");
        return MedicationSettingResponse.of(wardId, settingService.getPreference(wardId));
    }

    /**
     * 피보호자의 복약 알림 설정을 변경한다. 설정은 피보호자 계정에 붙으므로 다른 보호자 화면에도 동일하게 보인다.
     * 전달하지 않은 항목({@code null})은 기존값을 유지한다.
     */
    @Transactional
    public MedicationSettingResponse updateSetting(String guardianId, String wardId,
                                                   Boolean alarmEnabled, Boolean remindAgainEnabled) {
        requireActiveConnection(guardianId, wardId, "복약 알림 설정 변경");

        MedicationPreference applied = settingService.updatePreference(wardId, alarmEnabled, remindAgainEnabled);
        log.info("복약 알림 설정 변경: wardId={}, alarmEnabled={}, remindAgainEnabled={}, guardianId={}",
                wardId, applied.alarmEnabled(), applied.remindAgainEnabled(), guardianId);
        return MedicationSettingResponse.of(wardId, applied);
    }

    /** 이름은 공백만 보낸 경우 기존값을 유지한다(빈 이름으로 덮어써 목록을 못 알아보게 만들지 않는다). */
    private static String resolveName(MedicationUpdateRequest request, Medication medication) {
        if (request.name() == null || request.name().isBlank()) {
            return medication.getName();
        }
        return request.name().trim();
    }

    /**
     * 복용 시각을 결정한다.
     *
     * <p>시각을 명시하면 그 값을 쓰고, <b>시간대만 바꿨으면 새 시간대의 기본 시각</b>으로 갱신한다 —
     * 그러지 않으면 "저녁 08:00" 같은 상태가 남는다. 둘 다 없으면 기존 시각 그대로다.</p>
     */
    private static LocalTime resolveDoseTime(MedicationUpdateRequest request, Medication medication,
                                             MedicationTimeSlot timeSlot) {
        if (request.doseTime() != null) {
            return request.doseTime();
        }
        if (request.timeSlot() != null && request.timeSlot() != medication.getTimeSlot()) {
            return timeSlot.defaultTime();
        }
        return medication.getDoseTime();
    }

    /** 메모는 {@code null}이면 미변경, <b>빈 문자열이면 삭제</b>다(null로는 지울 수 없으므로). */
    private static String resolveMemo(MedicationUpdateRequest request, Medication medication) {
        if (request.memo() == null) {
            return medication.getMemo();
        }
        return request.memo().isBlank() ? null : request.memo().trim();
    }

    /** 삭제되지 않은 약을 찾는다. 삭제된 약은 존재하지 않는 것으로 취급한다. */
    private Medication findActiveMedication(Long medicationId) {
        return medicationRepository.findById(medicationId)
                .filter(medication -> !medication.isDeleted())
                .orElseThrow(() -> new CustomException(ErrorCode.MEDICATION_NOT_FOUND));
    }

    /**
     * ACTIVE 연결이 아니면 403으로 막고 흔적을 남긴다. 404로 위장하지 않는다(2026-07-14 정책) —
     * 드러나는 건 "그 대상이 존재한다"까지이고 이름·약 내용은 응답에 싣지 않는다.
     */
    private void requireActiveConnection(String guardianId, String wardId, String action) {
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 복약 접근 시도: guardianId={}, wardId={}, action={}",
                    guardianId, wardId, action);
            throw new CustomException(ErrorCode.MEDICATION_NOT_AUTHORIZED);
        }
    }

    /** 오늘 복용 체크를 약 ID로 인덱싱한다(약별 개별 조회로 인한 N+1 회피). */
    private Map<Long, MedicationIntake> findTodayIntakes(List<Medication> medications, LocalDate today) {
        if (medications.isEmpty()) {
            return Map.of();
        }
        List<Long> medicationIds = medications.stream().map(Medication::getId).toList();
        return intakeRepository.findByMedicationIdInAndDoseDate(medicationIds, today).stream()
                .collect(Collectors.toMap(MedicationIntake::getMedicationId, Function.identity()));
    }

    /** 만 나이. 생년월일이 없는 계정은 null(프론트가 나이 표기를 생략한다). */
    private Integer calculateAge(User ward, LocalDate today) {
        if (ward == null || ward.getBirthDate() == null) {
            return null;
        }
        return Period.between(ward.getBirthDate(), today).getYears();
    }
}
