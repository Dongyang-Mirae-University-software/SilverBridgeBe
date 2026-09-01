package kr.silverbridge.main.domain.anomaly.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.enums.DetectedType;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

/**
 * 이상감지 이력. AI 서버가 보낸 분석 신호 중 "위험"으로 판정된 건만 남는다.
 *
 * <p>{@code sessionId}는 감지 시점의 {@code cameras.session_id}를 비정규화해 보관한다 — 카메라가 삭제·재발급돼도
 * 과거 이력이 어느 세션에서 났는지 판독할 수 있어야 하기 때문. 소유자({@code wardId})는 users FK(CASCADE)라
 * 회원 탈퇴(hard delete) 시 이력도 함께 정리된다.</p>
 *
 * <p>{@code detectedAt}(AI {@code analyzedAt}, naive UTC)은 <b>nullable</b>이다 — AI 캐시 미스 fallback
 * 페이로드에는 이 필드가 없다. NULL이면 "AI 분석 시각 불명"이고, 수신 시각은 {@code createdAt}에 남는다.
 * 둘을 섞어 채우지 않는 이유는 이력에서 "AI가 알려준 시각"과 "우리가 받은 시각"을 구분하기 위함.</p>
 *
 * <p>{@code danger}는 판정 근거 기록용이다 — CONFIDENCE 폴백 모드로 적재된 건은 {@code false}로 남아,
 * 나중에 "이 이력이 AI 판정이었나, 백엔드 임계 폴백이었나"를 사후 구분할 수 있다.</p>
 */
@Entity
@Table(name = "anomaly_event", indexes = {
        @Index(name = "idx_anomaly_events_ward_created", columnList = "ward_id, created_at DESC"),
        @Index(name = "idx_anomaly_event_incident", columnList = "incident_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Builder
@AllArgsConstructor
public class AnomalyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 카메라 소유 피보호자 (cameras.session_id → ward_id 매핑 결과)
    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "detected_type", nullable = false, length = 20)
    private DetectedType detectedType;

    @Column(nullable = false)
    private double confidence;

    // AI가 보낸 danger 값 그대로 (판정 근거 기록 — CONFIDENCE 폴백으로 적재된 건은 false)
    @Column(nullable = false)
    private boolean danger;

    // AI analyzedAt. fallback 페이로드엔 없을 수 있어 NULL 허용 (수신 시각은 createdAt)
    @Column(name = "detected_at")
    private OffsetDateTime detectedAt;

    /**
     * 이 이력이 속한 상황({@link AnomalyIncident}). 판정·통계는 상황 단위라 이력 하나하나가 아니라
     * 이 값으로 묶인다.
     *
     * <p>NULL은 <b>상황 묶음 도입(V44) 이전 이력</b>이라는 뜻이다 - 사후에 채울 근거가 없어 백필하지 않았고,
     * 판정 대상도 아니다. 연관관계 대신 ID만 두는 이유는 적재 경로가 알림 문구 하나 때문에 상황 전체를
     * 로딩할 필요가 없기 때문이다.</p>
     */
    @Column(name = "incident_id")
    private Long incidentId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
