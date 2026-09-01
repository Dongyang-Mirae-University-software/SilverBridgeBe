package kr.silverbridge.main.domain.anomaly.repository;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncidentFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AnomalyIncidentFeedbackRepository extends JpaRepository<AnomalyIncidentFeedback, Long> {

    Optional<AnomalyIncidentFeedback> findByIncidentIdAndGuardianId(Long incidentId, String guardianId);

    /** 상태 재계산용 - 그 상황의 응답 전체. 다수결이 아니라 "전원 일치인가"를 보므로 전부 필요하다. */
    List<AnomalyIncidentFeedback> findByIncidentId(Long incidentId);

    /** 목록 조회에서 "내가 낸 응답"을 한 번에 붙이기 위한 조회(건별 조회로 인한 N+1 회피). */
    List<AnomalyIncidentFeedback> findByGuardianIdAndIncidentIdIn(String guardianId, Collection<Long> incidentIds);

    /** 재촉 대상 판정용 - 후보 상황들에 달린 응답 전체. 이미 답한 보호자는 재촉하지 않는다. */
    List<AnomalyIncidentFeedback> findByIncidentIdIn(Collection<Long> incidentIds);
}
