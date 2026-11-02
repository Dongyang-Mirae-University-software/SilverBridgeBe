package kr.silverbridge.main.domain.checkin.repository;

import kr.silverbridge.main.domain.checkin.entity.CheckinResult;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface CheckinResultRepository extends JpaRepository<CheckinResult, Long> {

    Optional<CheckinResult> findBySessionPk(Long sessionPk);

    boolean existsByWardIdAndCheckDateAndSlot(String wardId, LocalDate checkDate, CheckinSlot slot);
}
