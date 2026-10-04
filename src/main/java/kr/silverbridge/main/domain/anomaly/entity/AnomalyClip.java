package kr.silverbridge.main.domain.anomaly.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 이상감지 5초 영상 클립의 메타(파일은 디스크, 2026-10-04).
 *
 * <p>{@code fileName}은 서버가 만든 UUID 파일 이름이다 - 경로 전체나 사용자 입력을 저장하지 않는다. 실제 위치는
 * {@code AnomalyClipStorage}가 저장 루트와 합쳐 정하고, 루트 밖으로 나가지 않는지 검사한다.</p>
 *
 * <p>공개 상태는 상황 판정을 따라간다 - 오탐 확정이면 {@link AnomalyClipStatus#HIDDEN}, 번복되면 다시
 * {@link AnomalyClipStatus#VISIBLE}. 상태 전환은 판정과 같은 트랜잭션(상황 행 쓰기 잠금 안)에서 벌크 UPDATE로 한다.</p>
 */
@Entity
@Table(name = "anomaly_clip", indexes = {
        @Index(name = "idx_anomaly_clip_incident", columnList = "incident_id, created_at DESC"),
        @Index(name = "idx_anomaly_clip_ward", columnList = "ward_id, session_id"),
        @Index(name = "idx_anomaly_clip_hidden", columnList = "status, hidden_at"),
        @Index(name = "idx_anomaly_clip_created_at", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnomalyClip extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "file_name", nullable = false, length = 64, unique = true)
    private String fileName;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** AI {@code X-Clip-Duration-Ms}. 헤더가 없으면 null. */
    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "frame_count")
    private Integer frameCount;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    /** 클립 첫 프레임 시각(AI {@code X-Clip-Started-At}). 없으면 null. */
    @Column(name = "clip_started_at")
    private OffsetDateTime clipStartedAt;

    /** AI에 요청한 감지 시각(AI {@code analyzedAt}, 없으면 요청 시각). */
    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnomalyClipStatus status;

    /** 비공개 전환 시각 - 24시간 유예의 기준점. VISIBLE이면 null. */
    @Column(name = "hidden_at")
    private OffsetDateTime hiddenAt;

    @Builder
    private AnomalyClip(Long incidentId, String wardId, String sessionId, String fileName, long sizeBytes,
                        Integer durationMs, Integer frameCount, Integer width, Integer height,
                        OffsetDateTime clipStartedAt, OffsetDateTime detectedAt,
                        AnomalyClipStatus status, OffsetDateTime hiddenAt) {
        this.incidentId = incidentId;
        this.wardId = wardId;
        this.sessionId = sessionId;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
        this.frameCount = frameCount;
        this.width = width;
        this.height = height;
        this.clipStartedAt = clipStartedAt;
        this.detectedAt = detectedAt;
        this.status = status;
        this.hiddenAt = hiddenAt;
    }

    public boolean isVisible() {
        return status == AnomalyClipStatus.VISIBLE;
    }
}
