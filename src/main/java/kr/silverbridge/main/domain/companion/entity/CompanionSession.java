package kr.silverbridge.main.domain.companion.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 안부 대화 1회(V65). {@code (ward_id, session_date, slot)} UNIQUE라 한 시간대에 대화는 하나뿐이다.
 *
 * <p><b>원문 필드가 없다</b> - 대답 글·받아 적은 글·AI 응답 문장은 기기로 넘기기만 하고 저장하지 않는다(결정 B).
 * 남기는 것은 질문 번호·횟수·기분 코드·위험 여부뿐이다.</p>
 *
 * <p>위험 알림 1회 보장은 이 엔티티가 아니라 {@code risk_notified_at} 조건부 UPDATE(저장소)가 맡는다 - 턴 요청과
 * 마감 요청이 동시에 와도 한쪽만 이긴다.</p>
 */
@Entity
@Table(name = "companion_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanionSession {

    public static final String ALERT_URGENT = "URGENT";
    public static final String ALERT_INFO = "INFO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Convert(converter = CompanionSlotConverter.class)
    @Column(nullable = false, length = 5)
    private CompanionSlot slot;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "dialogue_id", length = 64)
    private String dialogueId;

    @Column(name = "max_turns")
    private Integer maxTurns;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompanionSessionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 20)
    private CompanionEndReason endReason;

    /** null = 판단할 수 없음. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CompanionMood mood;

    @Column(name = "turn_count", nullable = false)
    private int turnCount;

    @Column(name = "answered_count")
    private Integer answeredCount;

    @Column(name = "no_response_count")
    private Integer noResponseCount;

    @Column(name = "risk_flag", nullable = false)
    private boolean riskFlag;

    @Column(name = "alert_level", length = 10)
    private String alertLevel;

    @Column(name = "risk_notified_at")
    private OffsetDateTime riskNotifiedAt;

    /**
     * 대화 중 가장 높았던 턴 위험 확률(AI v9 척도, 2026-11-11). null = 확률을 받은 턴이 없음. 여러 날 누적 알림
     * ({@code CompanionRiskPattern})만 쓴다 - 대답 원문이 아니라 숫자 하나다. 로그·알림 문구에 싣지 않는다.
     */
    @Column(name = "max_risk_prob", precision = 5, scale = 4)
    private BigDecimal maxRiskProb;

    @Column(name = "prompt_version", length = 100)
    private String promptVersion;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "last_activity_at", nullable = false)
    private OffsetDateTime lastActivityAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    /** 테스트 계정의 테스트 대화(2026-11-10). 집계에서 빼지 않는다 - 나중에 걸러 볼 수 있게 표시만 한다. */
    @Column(name = "is_test", nullable = false)
    private boolean test;

    @Version
    private long version;

    private CompanionSession(String wardId, LocalDate sessionDate, CompanionSlot slot, Long questionId,
                             OffsetDateTime now) {
        this.wardId = wardId;
        this.sessionDate = sessionDate;
        this.slot = slot;
        this.questionId = questionId;
        this.status = CompanionSessionStatus.OPEN;
        this.startedAt = now;
        this.lastActivityAt = now;
    }

    /** AI에 대화를 열기 전 자리를 선점한다({@code dialogueId}는 비어 있다). */
    public static CompanionSession claim(String wardId, LocalDate sessionDate, CompanionSlot slot, Long questionId,
                                         OffsetDateTime now) {
        return new CompanionSession(wardId, sessionDate, slot, questionId, now);
    }

    /** 테스트 대화 자리. 시간대당 1회 UNIQUE(일반 대화 전용 부분 인덱스)에 걸리지 않는다. */
    public static CompanionSession claimTest(String wardId, LocalDate sessionDate, CompanionSlot slot, Long questionId,
                                             OffsetDateTime now) {
        CompanionSession session = new CompanionSession(wardId, sessionDate, slot, questionId, now);
        session.test = true;
        return session;
    }

    public void attachDialogue(String dialogueId, Integer maxTurns, OffsetDateTime now) {
        this.dialogueId = dialogueId;
        this.maxTurns = maxTurns;
        this.lastActivityAt = now;
    }

    /** 턴 기록. {@code riskProbability}는 null이면 무시하고, 있으면 지금까지의 최댓값만 남긴다. */
    public void recordTurn(int turnNo, boolean riskSeen, BigDecimal riskProbability, OffsetDateTime now) {
        this.turnCount = Math.max(this.turnCount, turnNo);
        if (riskSeen) {
            this.riskFlag = true;
        }
        if (riskProbability != null && (maxRiskProb == null || riskProbability.compareTo(maxRiskProb) > 0)) {
            this.maxRiskProb = riskProbability;
        }
        this.lastActivityAt = now;
    }

    public void finish(CompanionEndReason endReason, CompanionMood mood, int turnCount, Integer answeredCount,
                       Integer noResponseCount, boolean riskFlag, boolean urgent, String promptVersion,
                       OffsetDateTime now) {
        this.status = CompanionSessionStatus.FINISHED;
        this.endReason = endReason;
        this.mood = mood;
        this.turnCount = Math.max(this.turnCount, turnCount);
        this.answeredCount = answeredCount;
        this.noResponseCount = noResponseCount;
        this.riskFlag = this.riskFlag || riskFlag;
        this.alertLevel = (urgent || this.riskFlag) ? ALERT_URGENT : ALERT_INFO;
        this.promptVersion = promptVersion;
        this.lastActivityAt = now;
        this.endedAt = now;
    }

    public void markLost(OffsetDateTime now) {
        this.status = CompanionSessionStatus.LOST;
        this.lastActivityAt = now;
        this.endedAt = now;
    }

    public boolean isOpen() {
        return status == CompanionSessionStatus.OPEN;
    }

    public boolean isDialogueAttached() {
        return dialogueId != null;
    }
}
