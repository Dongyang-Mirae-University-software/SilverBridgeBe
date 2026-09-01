package kr.silverbridge.main.domain.anomaly.repository;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewReminderLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AnomalyReviewReminderLogRepository extends JpaRepository<AnomalyReviewReminderLog, Long> {

    /** 후보 상황들에 대해 이미 재촉을 보낸 (상황, 보호자) 조합. 중복 발송을 막는 선점 기록이다. */
    List<AnomalyReviewReminderLog> findByIncidentIdIn(Collection<Long> incidentIds);

    /**
     * 요약 대상 판정용 - 이 보호자에게 <b>건별 재촉을 이미 보낸</b> 상황들.
     *
     * <p>요약은 "1차 재촉 이후"에만 담는다. 이 조건이 없으면 아직 1차가 나가지 않은 상황이 요약에 먼저
     * 실려, 같은 상황으로 요약과 건별 재촉이 연달아 나간다.</p>
     */
    List<AnomalyReviewReminderLog> findByGuardianIdAndIncidentIdIn(String guardianId, Collection<Long> incidentIds);
}
