package kr.silverbridge.main.domain.anomaly.repository;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.global.enums.DetectedType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnomalyIncidentRepository extends JpaRepository<AnomalyIncident, Long> {

    /**
     * 상황 승계 판정용 - 같은 카메라·같은 유형의 <b>가장 최근 상황</b> 1건.
     *
     * <p>이 조회는 감지 1건마다 돈다({@code idx_anomaly_incident_merge}가 커버). 승계 여부는
     * {@code AnomalyIncidentService}가 시간 규칙으로 판단하므로 여기서는 조건을 걸지 않는다 -
     * "직전 상황이 무엇이었나"와 "그것을 이어도 되나"는 다른 질문이다.</p>
     */
    Optional<AnomalyIncident> findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(
            String wardId, String sessionId, DetectedType detectedType);
}
