package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionQuestion;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanionQuestionRepository extends JpaRepository<CompanionQuestion, Long> {

    /** id 순으로 고정해야 날짜로 고르는 질문이 실행마다 같다. */
    List<CompanionQuestion> findBySlotAndActiveTrueOrderByIdAsc(CompanionSlot slot);
}
