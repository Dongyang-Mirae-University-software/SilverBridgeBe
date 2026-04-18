package kr.silverbridge.main.domain.ai.repository;

import kr.silverbridge.main.domain.ai.entity.CharacterExpressionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CharacterExpressionRecordRepository extends JpaRepository<CharacterExpressionRecord, Long> {

    // 피보호자 최신 표정 조회 (Redis 미스 시 폴백용)
    Optional<CharacterExpressionRecord> findTopByWardIdOrderByCreatedAtDesc(String wardId);
}
