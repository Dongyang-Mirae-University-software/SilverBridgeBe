package kr.silverbridge.main.domain.checkin.repository;

import kr.silverbridge.main.domain.checkin.entity.CheckinQuestion;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CheckinQuestionRepository extends JpaRepository<CheckinQuestion, Long> {

    List<CheckinQuestion> findByWardId(String wardId);

    Optional<CheckinQuestion> findByWardIdAndSlot(String wardId, CheckinSlot slot);
}
