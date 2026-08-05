package kr.silverbridge.main.domain.medication.repository;

import kr.silverbridge.main.domain.medication.entity.MedicationMissedAlertLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 미복용 요약 알림 발송 기록 조회. 행이 있으면 그 (보호자, 피보호자, 날짜) 조합은 이미 보낸 것이다.
 */
public interface MedicationMissedAlertLogRepository extends JpaRepository<MedicationMissedAlertLog, Long> {

    /** 오늘 이미 보낸 건을 한 번에 조회해 중복 발송을 거른다. */
    List<MedicationMissedAlertLog> findByDoseDateAndWardIdIn(LocalDate doseDate, Collection<String> wardIds);
}
