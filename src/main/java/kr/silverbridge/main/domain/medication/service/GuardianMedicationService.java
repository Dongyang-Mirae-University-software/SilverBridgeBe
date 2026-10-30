package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.dto.MedicationBatchCreateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationCreateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationItem;
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
import kr.silverbridge.main.global.validation.TextSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final GuardianMedicationSettingService guardianSettingService;
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
        Map<String, GuardianMissedAlertSetting> missedAlerts =
                guardianSettingService.findSettingsOfGuardian(guardianId, wardIds);
        GuardianMissedAlertSetting defaultMissedAlert = guardianSettingService.defaultSetting();

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
                            preferences.getOrDefault(wardId, MedicationPreference.DEFAULT),
                            missedAlerts.getOrDefault(wardId, defaultMissedAlert),
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

        // 시각을 주면 시간대는 그 시각으로 정한다(일괄 등록과 같은 규칙) - "점심 01:40"처럼 어긋나면
        // 화면 시간대 칸 순서와 알림 묶음 문구가 틀어진다(2026-10-30 gosky). 시각이 없으면 시간대 기본 시각이라 늘 맞는다
        LocalTime doseTime = request.resolveDoseTime();
        MedicationTimeSlot timeSlot = request.doseTime() != null ? MedicationTimeSlot.fromDoseTime(doseTime) : request.timeSlot();
        Medication medication = medicationRepository.save(newMedication(wardId, guardianId, request.name(),
                timeSlot, doseTime, request.resolveDoseAmount(), request.memo()));

        log.info("복약 등록: medicationId={}, wardId={}, guardianId={}", medication.getId(), wardId, guardianId);
        // 방금 등록한 약은 오늘 아직 복용 전이다.
        return MedicationItem.of(medication, null);
    }

    /**
     * 약 여러 개 × 복용 시각 여러 개를 한 번에 등록한다(2026-10-22, "약 추가" 시안). <b>한 트랜잭션</b>이라 하나라도
     * 실패하면 아무것도 남지 않는다. 약 한 건의 생성 규칙은 단건 등록과 같은 {@link #newMedication}이고, 시간대는
     * {@link MedicationTimeSlot#fromDoseTime}이 정한다. 이미 등록된 같은 이름의 약은 단건 등록처럼 막지 않는다.
     *
     * @return 복용 시각 순, 같은 시각 안에서는 요청한 약 순서
     * @throws CustomException {@code MEDICATION_NOT_AUTHORIZED} ACTIVE 연결이 아닌 피보호자 /
     *                         {@code MEDICATION_BATCH_DUPLICATE_NAME}·{@code _TIME} 같은 요청 안의 중복
     */
    @Transactional
    public List<MedicationItem> createBatch(String guardianId, String wardId, MedicationBatchCreateRequest request) {
        requireActiveConnection(guardianId, wardId, "약 일괄 등록");
        requireDistinct(request);

        List<LocalTime> times = request.times().stream()
                .map(MedicationBatchCreateRequest.Time::resolveDoseTime)
                .sorted()
                .toList();
        List<Medication> medications = new ArrayList<>();
        for (LocalTime doseTime : times) {
            for (MedicationBatchCreateRequest.Drug drug : request.drugs()) {
                medications.add(newMedication(wardId, guardianId, drug.name(),
                        MedicationTimeSlot.fromDoseTime(doseTime), doseTime, drug.resolveDoseAmount(), request.memo()));
            }
        }
        List<Medication> saved = medicationRepository.saveAll(medications);

        log.info("복약 일괄 등록: wardId={}, guardianId={}, drugs={}, times={}, created={}",
                wardId, guardianId, request.drugs().size(), times.size(), saved.size());
        return saved.stream().map(medication -> MedicationItem.of(medication, null)).toList();
    }

    /** 같은 요청 안의 같은 이름(정리 후, 대소문자 무시)·같은 시각은 오입력으로 보고 전체를 거절한다. */
    private static void requireDistinct(MedicationBatchCreateRequest request) {
        Set<String> names = new HashSet<>();
        for (MedicationBatchCreateRequest.Drug drug : request.drugs()) {
            if (!names.add(TextSanitizer.sanitize(drug.name()).toLowerCase(Locale.ROOT))) {
                throw new CustomException(ErrorCode.MEDICATION_BATCH_DUPLICATE_NAME);
            }
        }
        Set<LocalTime> times = new HashSet<>();
        for (MedicationBatchCreateRequest.Time time : request.times()) {
            if (!times.add(time.resolveDoseTime())) {
                throw new CustomException(ErrorCode.MEDICATION_BATCH_DUPLICATE_TIME);
            }
        }
    }

    /**
     * 약 한 건 생성 규칙 - 단건·일괄 등록이 함께 쓴다. 수정과 같은 규칙으로 정리해 저장한다(MED-G11) - 이름은 앞뒤 공백·
     * 보이지 않는 문자 제거, 메모는 비면 null(빈 문자열·공백 메모가 "1정 · "처럼 보이지 않게).
     */
    private static Medication newMedication(String wardId, String guardianId, String name, MedicationTimeSlot timeSlot,
                                            LocalTime doseTime, BigDecimal doseAmount, String memo) {
        return Medication.builder()
                .wardId(wardId)
                .createdBy(guardianId)
                .name(TextSanitizer.sanitize(name))
                .timeSlot(timeSlot)
                .doseTime(doseTime)
                .doseAmount(doseAmount)
                .memo(normalizeMemo(memo))
                .build();
    }

    /**
     * 약 정보를 부분 수정한다. 전달하지 않은 항목({@code null})은 기존값을 유지한다.
     *
     * <p><b>복용 시각이 실제로 바뀌고 새 시각이 아직 오지 않았으면 그 약의 당일 발송 기록을 지운다</b> — 그래야
     * 새 시각 기준으로 다시 판정되어 알림이 나간다(08:00을 20:00으로 고쳤는데 오늘 저녁에 안 울리는 문제 방지).
     * 새 시각이 이미 지났으면 지우지 않는다 — 08:00 알림을 받은 뒤 08:10에 08:05로 고치면 기록이 지워져 같은
     * 회차가 다시 나갔다(MED-G12, 고칠 때마다 반복). 복용 체크는 건드리지 않으므로 이미 드신 약은 여전히 울리지 않는다.</p>
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
        // 시간대나 시각을 보냈으면 시간대는 최종 시각으로 다시 정한다 - 둘이 어긋나면 시각이 기준이다(400으로 막지 않는다,
        // 예전 화면 호환). 이름·복용량·메모만 고치면 시간대를 건드리지 않는다(2026-10-30, 어긋난 옛 약은 이렇게 바로잡힌다)
        if (request.timeSlot() != null || request.doseTime() != null) {
            timeSlot = MedicationTimeSlot.fromDoseTime(doseTime);
        }

        medication.update(
                resolveName(request, medication),
                timeSlot,
                doseTime,
                request.doseAmount() != null ? request.doseAmount() : medication.getDoseAmount(),
                resolveMemo(request, medication));

        LocalDate today = MedicationClock.today();
        if (shouldResetTodayReminders(previousDoseTime, doseTime, MedicationClock.now().toLocalTime())) {
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

        softDelete(guardianId, medication);
    }

    /**
     * 한 피보호자의 약 여러 개를 한 번에 삭제한다(2026-10-23) - "아침 약 모두 삭제"·"약 전부 삭제".
     *
     * <p><b>한 트랜잭션 - 전부 성공 또는 전부 실패.</b> 인가는 단건과 같은 ACTIVE 연결 확인이고, 목록의 약은 모두 경로의
     * 피보호자 약이어야 한다(아니면 403 + {@code [IDOR-ATTEMPT]}). 없는 약은 404다. <b>이미 삭제된 약은 건너뛴다</b> - 다른
     * 보호자가 방금 지운 약 때문에 나머지 삭제가 실패하면 보호자가 다시 눌러야 하는데, 지우려던 상태는 이미 이뤄졌다.
     * 지난 복용 이력은 단건과 같이 남는다(soft delete).</p>
     *
     * @return 이번에 삭제한 약 수(이미 삭제돼 건너뛴 약 제외)
     */
    @Transactional
    public int deleteBatch(String guardianId, String wardId, List<Long> medicationIds) {
        requireActiveConnection(guardianId, wardId, "약 일괄 삭제");
        if (new HashSet<>(medicationIds).size() != medicationIds.size()) {
            throw new CustomException(ErrorCode.MEDICATION_DELETE_BATCH_DUPLICATE);
        }

        int deleted = 0;
        for (Long medicationId : medicationIds.stream().sorted().toList()) {
            Medication medication = medicationRepository.findById(medicationId)
                    .orElseThrow(() -> new CustomException(ErrorCode.MEDICATION_NOT_FOUND));
            if (!medication.getWardId().equals(wardId)) {
                log.warn("[IDOR-ATTEMPT] 다른 피보호자의 약을 일괄 삭제 시도: guardianId={}, wardId={}, medicationId={}",
                        guardianId, wardId, medicationId);
                throw new CustomException(ErrorCode.MEDICATION_NOT_AUTHORIZED);
            }
            if (medication.isDeleted()) {
                continue;
            }
            softDelete(guardianId, medication);
            deleted++;
        }
        log.info("복약 일괄 삭제: wardId={}, guardianId={}, 요청={}, 삭제={}",
                wardId, guardianId, medicationIds.size(), deleted);
        return deleted;
    }

    private void softDelete(String guardianId, Medication medication) {
        medication.delete(MedicationClock.now());
        log.info("복약 삭제: medicationId={}, wardId={}, guardianId={}",
                medication.getId(), medication.getWardId(), guardianId);
    }

    /**
     * 시각 변경 시 당일 발송 기록을 지울지. <b>시각이 바뀌었고 새 시각이 지금(KST)보다 뒤</b>일 때만 지운다(MED-G12).
     *
     * <p>새 시각이 지금이거나 이미 지났으면 지우지 않는다 — 지우면 유예 창 안의 과거 시각이 "오늘 아직 안 보낸"
     * 약이 되어 이미 받은 알림이 다시 나간다(선점 후 발송 불변 규칙 ④의 "같은 회차 1회"가 깨진다). 대가로
     * 지난 시각으로 옮긴 약은 오늘 다시 울리지 않는데, 이미 그날 알림을 한 번 받았으므로 의도된 동작이다.</p>
     *
     * <p>현재 시각을 인자로 받는 static이다 — 경계값을 리터럴로 검증할 수 있게
     * ({@link MedicationReminderPlanner#graceWindowStart}와 같은 이유).</p>
     */
    static boolean shouldResetTodayReminders(LocalTime previousDoseTime, LocalTime newDoseTime, LocalTime now) {
        return !newDoseTime.equals(previousDoseTime) && newDoseTime.isAfter(now);
    }

    /**
     * 이름은 정리(앞뒤 공백·보이지 않는 문자 제거) 후 비면 기존값을 유지한다(빈 이름으로 덮어써 목록을 못
     * 알아보게 만들지 않는다). 요청 단계의 {@code @VisibleText}가 먼저 막으므로 여기는 마지막 방어선이다.
     */
    private static String resolveName(MedicationUpdateRequest request, Medication medication) {
        String name = TextSanitizer.sanitize(request.name());
        if (!TextSanitizer.hasVisibleChar(name)) {
            return medication.getName();
        }
        return name;
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
            // 초 아래는 버린다(2026-10-22 점검 L-3, 등록과 같은 규칙)
            return request.doseTime().truncatedTo(ChronoUnit.SECONDS);
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
        return normalizeMemo(request.memo());
    }

    /** 메모 정리 — 보이지 않는 문자·앞뒤 공백을 지우고, 남는 글자가 없으면 메모 없음({@code null})이다(등록·수정 공통). */
    private static String normalizeMemo(String memo) {
        String sanitized = TextSanitizer.sanitize(memo);
        return TextSanitizer.hasVisibleChar(sanitized) ? sanitized : null;
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
        if (!MedicationWardIds.isWellFormed(wardId)) {
            log.warn("[IDOR-ATTEMPT] 형식이 맞지 않는 피보호자 ID로 복약 접근 시도: guardianId={}, wardIdLength={}, action={}",
                    guardianId, MedicationWardIds.lengthOf(wardId), action);
            throw new CustomException(ErrorCode.MEDICATION_NOT_AUTHORIZED);
        }
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
