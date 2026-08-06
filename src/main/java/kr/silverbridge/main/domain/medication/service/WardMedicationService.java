package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.dto.TodayMedicationResponse;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.event.MedicationIntakeChangedEvent;
import kr.silverbridge.main.domain.medication.repository.MedicationIntakeRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 피보호자용 복약 서비스 — 오늘의 일정 조회와 복용 체크/해제.
 *
 * <p><b>체크는 피보호자만</b> 할 수 있다. 이 경로가 유일한 체크 수단이며, 그 결과가 보호자 화면에 보인다.
 * 반대로 <b>약 등록·삭제는 여기 없다</b> — 보호자 전용({@link GuardianMedicationService})이다.</p>
 *
 * <p><b>인가</b>: 약의 {@code wardId}가 요청자 본인이어야 한다. 남의 약을 체크하려는 시도는 404 위장 대신
 * 403으로 막고 {@code [IDOR-ATTEMPT]} WARN을 남긴다(2026-07-14 정책).</p>
 *
 * <p><b>멱등</b>: 이미 체크된 약을 다시 체크하거나 체크되지 않은 약을 해제해도 상태가 그대로이고 예외도
 * 아니다 — 더블 탭·재시도로 실패 화면이 뜨면 어르신이 당황한다. 다만 이때는 이벤트를 발행하지 않아
 * 보호자에게 같은 알림이 반복되지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WardMedicationService {

    private final MedicationRepository medicationRepository;
    private final MedicationIntakeRepository intakeRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 오늘의 복약 일정과 완료 카운트를 반환한다(복용 시각 순). */
    @Transactional(readOnly = true)
    public TodayMedicationResponse getToday(String wardId) {
        LocalDate today = MedicationClock.today();
        List<Medication> medications =
                medicationRepository.findByWardIdAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(wardId);

        Map<Long, MedicationIntake> intakes = medications.isEmpty()
                ? Map.of()
                : intakeRepository
                .findByMedicationIdInAndDoseDate(medications.stream().map(Medication::getId).toList(), today)
                .stream()
                .collect(Collectors.toMap(MedicationIntake::getMedicationId, Function.identity()));

        List<MedicationItem> items = medications.stream()
                .map(medication -> MedicationItem.of(medication, intakes.get(medication.getId())))
                .toList();

        return TodayMedicationResponse.of(today, items);
    }

    /**
     * 오늘 복용했다고 체크한다. 이미 체크되어 있으면 기존 기록을 그대로 반환한다.
     *
     * @throws CustomException {@code MEDICATION_NOT_FOUND} 없거나 삭제된 약 /
     *                         {@code MEDICATION_NOT_OWNED} 본인 약이 아님
     */
    @Transactional
    public MedicationItem markTaken(String wardId, Long medicationId) {
        Medication medication = findOwnMedication(wardId, medicationId);
        LocalDate today = MedicationClock.today();

        Optional<MedicationIntake> existing = intakeRepository.findByMedicationIdAndDoseDate(medicationId, today);
        if (existing.isPresent()) {
            // 중복 체크 — 상태가 이미 목표와 같으므로 이벤트 없이 현재 상태를 돌려준다.
            return MedicationItem.of(medication, existing.get());
        }

        OffsetDateTime takenAt = MedicationClock.now();
        MedicationIntake intake = intakeRepository.save(MedicationIntake.of(medicationId, today, takenAt));

        eventPublisher.publishEvent(new MedicationIntakeChangedEvent(
                medicationId, wardId, medication.getName(), today, true, takenAt));
        log.info("복약 체크: medicationId={}, wardId={}, doseDate={}", medicationId, wardId, today);

        return MedicationItem.of(medication, intake);
    }

    /**
     * 오늘 복용 체크를 해제한다(오터치 정정). 체크되어 있지 않으면 아무 일도 하지 않는다.
     *
     * <p>해제는 <b>오늘 것만</b> 가능하다 — 지난 날짜의 기록은 이 경로로 건드릴 수 없다.</p>
     */
    @Transactional
    public MedicationItem unmarkTaken(String wardId, Long medicationId) {
        Medication medication = findOwnMedication(wardId, medicationId);
        LocalDate today = MedicationClock.today();

        Optional<MedicationIntake> existing = intakeRepository.findByMedicationIdAndDoseDate(medicationId, today);
        if (existing.isEmpty()) {
            return MedicationItem.of(medication, null);
        }

        intakeRepository.delete(existing.get());

        eventPublisher.publishEvent(new MedicationIntakeChangedEvent(
                medicationId, wardId, medication.getName(), today, false, null));
        log.info("복약 체크 해제: medicationId={}, wardId={}, doseDate={}", medicationId, wardId, today);

        return MedicationItem.of(medication, null);
    }

    /** 본인의 삭제되지 않은 약을 찾는다. */
    private Medication findOwnMedication(String wardId, Long medicationId) {
        Medication medication = medicationRepository.findById(medicationId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new CustomException(ErrorCode.MEDICATION_NOT_FOUND));

        if (!medication.getWardId().equals(wardId)) {
            log.warn("[IDOR-ATTEMPT] 타인 복약 체크 시도: wardId={}, medicationId={}", wardId, medicationId);
            // 보호자용 MEDICATION_NOT_AUTHORIZED("연결된 피보호자의…")를 쓰지 않는다 — 피보호자에게는
            // "연결된 피보호자"라는 말이 성립하지 않아 왜 막혔는지 알 수 없다(Swagger 문구와도 어긋났다).
            throw new CustomException(ErrorCode.MEDICATION_NOT_OWNED);
        }
        return medication;
    }
}
