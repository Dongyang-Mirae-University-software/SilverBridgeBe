package kr.silverbridge.main.domain.checkin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 안부 대화 한 번. 턴 수·시간 제한은 BE가 센다(AI도 8턴 상한이 있지만 BE 기준이 먼저다).
 * 받아쓴 글자·AI 답은 저장하지 않는다 - 턴마다 남기는 것은 개수와 위험 표시뿐이다.
 */
@Entity
@Table(name = "checkin_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckinSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64, updatable = false)
    private String sessionId;

    @Column(name = "ward_id", nullable = false, length = 6, updatable = false)
    private String wardId;

    @Column(name = "check_date", nullable = false, updatable = false)
    private LocalDate checkDate;

    @Convert(converter = CheckinSlotConverter.class)
    @Column(name = "slot", nullable = false, length = 5, updatable = false)
    private CheckinSlot slot;

    @Column(name = "question", nullable = false, length = 200, updatable = false)
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CheckinSessionStatus status;

    @Column(name = "turn_count", nullable = false)
    private int turnCount;

    @Column(name = "ai_done", nullable = false)
    private boolean aiDone;

    @Column(name = "risk_emergency", nullable = false)
    private boolean riskEmergency;

    @Column(name = "risk_self_harm", nullable = false)
    private boolean riskSelfHarm;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 20)
    private CheckinEndReason endReason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private CheckinSession(String sessionId, String wardId, LocalDate checkDate, CheckinSlot slot, String question,
                           OffsetDateTime startedAt) {
        this.sessionId = sessionId;
        this.wardId = wardId;
        this.checkDate = checkDate;
        this.slot = slot;
        this.question = question;
        this.status = CheckinSessionStatus.IN_PROGRESS;
        this.startedAt = startedAt;
    }

    public static CheckinSession start(String sessionId, String wardId, LocalDate checkDate, CheckinSlot slot,
                                       String question, OffsetDateTime startedAt) {
        return new CheckinSession(sessionId, wardId, checkDate, slot, question, startedAt);
    }

    public boolean isInProgress() {
        return status == CheckinSessionStatus.IN_PROGRESS;
    }

    /** AI가 받아 준 턴 하나를 센다. */
    public void recordTurn(boolean done, boolean emergency, boolean selfHarm) {
        this.turnCount++;
        this.aiDone = this.aiDone || done;
        this.riskEmergency = this.riskEmergency || emergency;
        this.riskSelfHarm = this.riskSelfHarm || selfHarm;
    }

    public void finish(CheckinEndReason reason, boolean emergency, boolean selfHarm, OffsetDateTime at) {
        this.status = CheckinSessionStatus.FINISHED;
        this.endReason = reason;
        this.riskEmergency = this.riskEmergency || emergency;
        this.riskSelfHarm = this.riskSelfHarm || selfHarm;
        this.finishedAt = at;
    }

    public void expire(OffsetDateTime at) {
        this.status = CheckinSessionStatus.EXPIRED;
        this.finishedAt = at;
    }
}
