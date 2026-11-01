package kr.silverbridge.main.domain.checkin.repository;

import kr.silverbridge.main.domain.checkin.entity.CheckinResult;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CheckinResultRepository extends JpaRepository<CheckinResult, Long> {

    Optional<CheckinResult> findBySessionPk(Long sessionPk);

    boolean existsByWardIdAndCheckDateAndSlot(String wardId, LocalDate checkDate, CheckinSlot slot);

    /** 기간 결과(양끝 포함) - 평소 대비 기준값·보호자 조회. */
    List<CheckinResult> findByWardIdAndCheckDateBetweenOrderByCheckDateAscSlotAsc(String wardId, LocalDate from,
                                                                                 LocalDate to);
}
