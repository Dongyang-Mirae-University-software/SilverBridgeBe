package kr.silverbridge.main.domain.medication.repository;

import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 날짜별 복용 체크 조회. 행이 있으면 복용, 없으면 미복용이다.
 */
public interface MedicationIntakeRepository extends JpaRepository<MedicationIntake, Long> {

    /** 여러 약의 특정 날짜 체크를 한 번에 조회(건별 조회로 인한 N+1 회피). */
    List<MedicationIntake> findByMedicationIdInAndDoseDate(Collection<Long> medicationIds, LocalDate doseDate);

    Optional<MedicationIntake> findByMedicationIdAndDoseDate(Long medicationId, LocalDate doseDate);
}
