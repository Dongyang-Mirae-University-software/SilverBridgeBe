package kr.silverbridge.main.domain.anomaly.repository;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {

    // 피보호자별 이상감지 이력 (최신순) — 2단계 이력 조회 API의 기반
    List<AnomalyEvent> findByWardIdOrderByCreatedAtDesc(String wardId);
}
