package kr.silverbridge.main.domain.sos.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

/**
 * 피보호자 SOS 발생 이력. 피보호자가 긴급 SOS를 누를 때마다 한 행이 기록된다.
 *
 * <p>이력은 알림 발송과 무관하게 항상 남는다(SOS 처리의 진실원본). 발송된 보호자 목록은 저장하지 않으며,
 * 알림 대상은 발송 시점의 ACTIVE connections에서 도출된다. {@code wardId}는 탈퇴(hard delete) 시
 * {@code ON DELETE SET NULL}로 익명 보존된다(access_logs와 동일 정책).</p>
 *
 * <p>보호자가 처리 결과를 남기면 {@code ack*} 필드가 채워진다(V33) — 이력 행 하나당 ACK 하나이며,
 * 여러 보호자가 각각 처리하면 마지막 처리로 덮어써진다. 미처리 건은 {@code ackStatus == null}이다.
 * <b>ACK는 사후 기록일 뿐 알림 발송 조건에 개입하지 않는다</b>(WARD_SOS 필수 알림, 2026-07-23 정책).</p>
 */
@Entity
@Table(name = "sos_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class SosEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ward_id", length = 6)
    private String wardId;

    /**
     * 발생 위치 자유 문구(선택). 프론트가 보낸 값을 그대로 보관하며 서버는 위치를 추정하지 않는다.
     * {@code null}이면 위치 미상 — 표시·보관 전용이라 알림 발송에는 관여하지 않는다.
     */
    @Column(name = "location", length = 100)
    private String location;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 보호자가 남긴 처리 결과. {@code null}이면 아직 처리되지 않은 SOS다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ack_status", length = 30)
    private SosAckStatus ackStatus;

    /** 처리한 보호자 ID. 해당 보호자 탈퇴 시 {@code ON DELETE SET NULL}로 비워진다. */
    @Column(name = "ack_by", length = 6)
    private String ackBy;

    @Column(name = "ack_at")
    private OffsetDateTime ackAt;

    /** 처리 메모(선택). 화면 문구는 프론트가 조립하며, 여기엔 보호자가 입력한 값만 담는다. */
    @Column(name = "ack_note", length = 200)
    private String ackNote;

    @Builder
    public SosEvent(String wardId, String location) {
        this.wardId = wardId;
        this.location = location;
    }

    /**
     * 보호자의 처리 결과를 기록한다. 이미 처리된 건도 덮어쓴다 — "안전 확인"으로 남겼다가 실제 출동으로
     * 바뀌는 현실 흐름을 반영한다(재ACK 허용).
     *
     * @param guardianId 처리한 보호자 ID (ACTIVE 연결 검증은 서비스가 담당)
     * @param ackStatus  처리 결과
     * @param ackNote    처리 메모 (없으면 {@code null})
     */
    public void acknowledge(String guardianId, SosAckStatus ackStatus, String ackNote) {
        this.ackBy = guardianId;
        this.ackStatus = ackStatus;
        this.ackNote = ackNote;
        this.ackAt = OffsetDateTime.now();
    }

    public boolean isAcknowledged() {
        return ackStatus != null;
    }
}
