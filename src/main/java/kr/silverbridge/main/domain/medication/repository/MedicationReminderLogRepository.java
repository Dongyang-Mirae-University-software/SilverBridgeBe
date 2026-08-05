package kr.silverbridge.main.domain.medication.repository;

import kr.silverbridge.main.domain.medication.entity.MedicationReminderLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 복약 알림 발송 기록 조회. 행이 있으면 그 회차는 이미 보낸 것이다.
 */
public interface MedicationReminderLogRepository extends JpaRepository<MedicationReminderLog, Long> {

    /** 여러 약의 특정 날짜 발송 기록을 한 번에 조회(약별 개별 조회로 인한 N+1 회피). */
    List<MedicationReminderLog> findByMedicationIdInAndDoseDate(Collection<Long> medicationIds, LocalDate doseDate);

    /**
     * 재알림 대상이 될 <b>최초 발송 기록</b>을 찾는다.
     *
     * <p>조건 = 오늘 발송된 {@code attempt=1} 중 발송 시각이 {@code [from, to]} 구간이고
     * ({@code to} = now - 재알림 지연, {@code from} = now - 재알림 마감) 아직 재알림이 나가지 않은 것.
     * 마감 하한을 두는 이유는, 서버가 오래 내려갔다 올라왔을 때 밤중에 아침 약 재알림이 튀어나오는 걸
     * 막기 위해서다(최초 발송의 유예 창과 같은 취지).</p>
     */
    @Query("""
            select l from MedicationReminderLog l
            where l.doseDate = :doseDate
              and l.attempt = 1
              and l.sentAt between :from and :to
              and not exists (
                  select 1 from MedicationReminderLog r
                  where r.medicationId = l.medicationId
                    and r.doseDate = l.doseDate
                    and r.attempt = 2)
            """)
    List<MedicationReminderLog> findRetryCandidates(@Param("doseDate") LocalDate doseDate,
                                                    @Param("from") OffsetDateTime from,
                                                    @Param("to") OffsetDateTime to);
}
