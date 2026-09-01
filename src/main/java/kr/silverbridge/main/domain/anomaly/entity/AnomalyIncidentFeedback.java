package kr.silverbridge.main.domain.anomaly.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상황 하나에 대한 보호자 한 명의 판단. <b>1인 1표</b>이며({@code UNIQUE(incident_id, guardian_id)})
 * 번복은 새 행이 아니라 이 행의 갱신이다.
 *
 * <p>번복 이력을 남기지 않는 이유: 필요한 것은 "지금 이 보호자가 무엇이라고 보는가"뿐이고, 판단이 바뀐
 * 과정은 판정에도 통계에도 쓰이지 않는다. 관리자에게 필요한 정보는 "누구와 누구의 답이 갈렸는가"다.</p>
 *
 * <p>판정 주체는 <b>보호자뿐</b>이다 - 피보호자 본인·관리자의 1차 판정은 존재하지 않는다. 관리자는
 * 이 응답들을 보고 {@code anomaly_incident}의 상태를 정정할 뿐 여기에 행을 만들지 않는다.</p>
 */
@Entity
@Table(name = "anomaly_incident_feedback",
        uniqueConstraints = @UniqueConstraint(name = "uq_anomaly_feedback",
                columnNames = {"incident_id", "guardian_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnomalyIncidentFeedback extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "guardian_id", nullable = false, length = 6)
    private String guardianId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnomalyVerdict verdict;

    @Builder
    private AnomalyIncidentFeedback(Long incidentId, String guardianId, AnomalyVerdict verdict) {
        this.incidentId = incidentId;
        this.guardianId = guardianId;
        this.verdict = verdict;
    }

    /** 응답 번복. 같은 값으로 다시 눌러도 문제가 없도록 멱등하게 둔다. */
    public void changeVerdict(AnomalyVerdict verdict) {
        this.verdict = verdict;
    }
}
