package kr.silverbridge.main.domain.medication.repository;

import kr.silverbridge.main.domain.medication.entity.Medication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

/**
 * 약 마스터 조회. <b>모든 조회 메서드는 {@code deletedAt IS NULL}로 삭제된 약을 제외</b>한다 —
 * soft delete라 조건을 빠뜨리면 삭제한 약이 화면에 다시 나타난다.
 */
public interface MedicationRepository extends JpaRepository<Medication, Long> {

    /** 피보호자 여러 명의 약을 한 번에 조회(보호자 목록 화면). 복용 시각 순. */
    List<Medication> findByWardIdInAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(Collection<String> wardIds);

    /** 피보호자 본인의 오늘 일정. 복용 시각 순. */
    List<Medication> findByWardIdAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(String wardId);

    /**
     * 특정 보호자가 등록한 약 전부(탈퇴 정리용). 여기서만 삭제된 약까지 포함한다 —
     * 등록자가 사라지면 soft delete된 약도 남겨둘 이유가 없다.
     */
    List<Medication> findByCreatedBy(String createdBy);

    /**
     * 복용 시각이 지난 약(알림 발송 후보). {@code from}은 유예 창 시작, {@code to}는 현재 시각이다.
     *
     * <p>실제 발송 여부는 호출자가 미복용·설정 ON·발송 기록 없음을 확인해 결정한다 —
     * 여기서는 시각 조건만 거른다.</p>
     */
    List<Medication> findByDeletedAtIsNullAndDoseTimeBetween(LocalTime from, LocalTime to);
}
