package kr.silverbridge.main.domain.checkin.repository;

import kr.silverbridge.main.domain.checkin.entity.CheckinSession;
import kr.silverbridge.main.domain.checkin.entity.CheckinSessionStatus;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface CheckinSessionRepository extends JpaRepository<CheckinSession, Long> {

    Optional<CheckinSession> findBySessionId(String sessionId);

    boolean existsBySessionId(String sessionId);

    Optional<CheckinSession> findByWardIdAndCheckDateAndSlotAndStatus(String wardId, LocalDate checkDate,
                                                                      CheckinSlot slot, CheckinSessionStatus status);
}
