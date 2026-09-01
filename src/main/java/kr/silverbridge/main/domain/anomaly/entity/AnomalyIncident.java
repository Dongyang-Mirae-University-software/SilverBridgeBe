package kr.silverbridge.main.domain.anomaly.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import kr.silverbridge.main.global.enums.DetectedType;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 이상감지 "상황" - 연속된 감지 이력({@link AnomalyEvent})을 하나로 묶은 판정·통계 단위.
 *
 * <p>같은 카메라에서 화재가 3분 이어지면 이력은 쿨다운(1분) 간격으로 3건이 쌓인다. 보호자에게 같은 불을
 * 세 번 판정하게 할 수는 없으므로, 같은 {@code (wardId, sessionId, detectedType)}의 연속 감지를 한 상황으로
 * 묶는다. 묶는 규칙 자체는 {@code AnomalyIncidentService}에 있다(설정값으로 바뀌는 정책이라 엔티티에 두지 않는다).</p>
 *
 * <p><b>모든 이상감지 통계의 단위는 이 상황</b>이다 - 이력 행 수가 아니다. 보호자 화면과 관리자 대시보드가
 * 같은 숫자를 보게 하려면 기준이 하나여야 한다.</p>
 *
 * <p>{@code resolvedBy}가 채워지면 <b>관리자 확정</b>이며 이후 보호자 응답으로 상태가 재계산되지 않는다.
 * 이 규칙이 없으면 관리자가 확인해 정정한 결과가 뒤늦은 보호자 응답 하나로 조용히 뒤집힌다.</p>
 */
@Entity
@Table(name = "anomaly_incident", indexes = {
        @Index(name = "idx_anomaly_incident_ward_started", columnList = "ward_id, started_at DESC"),
        @Index(name = "idx_anomaly_incident_status_started", columnList = "review_status, started_at DESC"),
        @Index(name = "idx_anomaly_incident_merge", columnList = "ward_id, session_id, detected_type, last_detected_at DESC")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnomalyIncident extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "detected_type", nullable = false, length = 20)
    private DetectedType detectedType;

    /** 첫 감지 수신 시각. 날짜별 통계는 이 값을 기준으로 센다. */
    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    /** 마지막 감지 수신 시각. 다음 감지를 이 상황으로 승계할지 판정하는 기준이다. */
    @Column(name = "last_detected_at", nullable = false)
    private OffsetDateTime lastDetectedAt;

    /** 이 상황에 묶인 감지 횟수. "몇 번 잡혔는지"가 보호자 판단에 쓰인다. */
    @Column(name = "event_count", nullable = false)
    private int eventCount;

    /** 묶인 감지 중 최고 신뢰도. 평균이 아닌 최고값인 이유는 가장 위험해 보였던 순간이 판단 근거이기 때문. */
    @Column(name = "max_confidence", nullable = false)
    private double maxConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private AnomalyReviewStatus reviewStatus;

    /** 정정한 관리자. 채워져 있으면 관리자 확정 상태다. */
    @Column(name = "resolved_by", length = 6)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "review_note", length = 200)
    private String reviewNote;

    @Builder
    private AnomalyIncident(String wardId, String sessionId, DetectedType detectedType,
                            OffsetDateTime detectedAt, double confidence) {
        this.wardId = wardId;
        this.sessionId = sessionId;
        this.detectedType = detectedType;
        this.startedAt = detectedAt;
        this.lastDetectedAt = detectedAt;
        this.eventCount = 1;
        this.maxConfidence = confidence;
        this.reviewStatus = AnomalyReviewStatus.PENDING;
    }

    /**
     * 보호자 응답 집계 결과를 반영한다.
     *
     * <p><b>관리자가 확정한 건은 바뀌지 않는다</b> - 이 규칙이 없으면 관리자가 확인해 정정한 결과가
     * 뒤늦은 보호자 응답 하나로 조용히 뒤집힌다. 호출부(서비스)도 확정 건은 응답 자체를 거부하지만,
     * 상태 변경의 최종 방어선을 엔티티에 둔다.</p>
     */
    public void applyReviewStatus(AnomalyReviewStatus status) {
        if (isAdminResolved()) {
            return;
        }
        this.reviewStatus = status;
    }

    /** 관리자가 확인을 마친 건인지. 채워져 있으면 보호자 응답으로 상태가 재계산되지 않는다. */
    public boolean isAdminResolved() {
        return this.resolvedBy != null;
    }

    /**
     * 같은 상황으로 판정된 감지를 하나 더 반영한다.
     *
     * <p>판정 상태는 건드리지 않는다 - 보호자가 "오탐"이라고 답한 뒤 같은 상황에서 감지가 한 번 더 잡혀도
     * 그 답을 지우지 않는다. 상태를 되돌리려면 보호자가 응답을 번복해야 한다.</p>
     */
    public void addDetection(OffsetDateTime detectedAt, double confidence) {
        this.lastDetectedAt = detectedAt;
        this.eventCount++;
        if (confidence > this.maxConfidence) {
            this.maxConfidence = confidence;
        }
    }
}
