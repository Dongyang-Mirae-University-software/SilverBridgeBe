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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대화 정리 결과(시각마다 하나). AI가 준 값을 허용 목록으로 거른 것만 담는다 - 대화 원문은 없다.
 * 생활 신호는 영문 코드(모르면 null)이고 허용값은 V65 CHECK와 같다.
 */
@Entity
@Table(name = "checkin_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckinResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private Long sessionPk;

    @Column(name = "ward_id", nullable = false, length = 6, updatable = false)
    private String wardId;

    @Column(name = "check_date", nullable = false, updatable = false)
    private LocalDate checkDate;

    @Convert(converter = CheckinSlotConverter.class)
    @Column(name = "slot", nullable = false, length = 5, updatable = false)
    private CheckinSlot slot;

    @Enumerated(EnumType.STRING)
    @Column(name = "emotion", nullable = false, length = 20)
    private CheckinEmotion emotion;

    @Enumerated(EnumType.STRING)
    @Column(name = "self_report", length = 20)
    private CheckinEmotion selfReport;

    @Column(name = "sleep", length = 10)
    private String sleep;

    @Column(name = "meal", length = 10)
    private String meal;

    @Column(name = "pain", length = 10)
    private String pain;

    @Column(name = "mood", length = 10)
    private String mood;

    @Column(name = "lonely", length = 10)
    private String lonely;

    @Column(name = "risk_emergency", nullable = false)
    private boolean riskEmergency;

    @Column(name = "risk_self_harm", nullable = false)
    private boolean riskSelfHarm;

    @Column(name = "summary", length = 500)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    private List<Evidence> evidence;

    @Column(name = "turns", nullable = false)
    private int turns;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", nullable = false, length = 20)
    private CheckinEndReason endReason;

    @Column(name = "avg_answer_sec")
    private Double avgAnswerSec;

    @Column(name = "avg_speech_sec")
    private Double avgSpeechSec;

    @Column(name = "avg_pause_ratio")
    private Double avgPauseRatio;

    @Column(name = "avg_chars_per_sec")
    private Double avgCharsPerSec;

    @Column(name = "avg_loudness_db")
    private Double avgLoudnessDb;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private CheckinResult(Long sessionPk, String wardId, LocalDate checkDate, CheckinSlot slot, CheckinEmotion emotion,
                          CheckinEmotion selfReport, String sleep, String meal, String pain, String mood, String lonely,
                          boolean riskEmergency, boolean riskSelfHarm, String summary, List<Evidence> evidence,
                          int turns, CheckinEndReason endReason, Double avgAnswerSec, Double avgSpeechSec,
                          Double avgPauseRatio, Double avgCharsPerSec, Double avgLoudnessDb, OffsetDateTime createdAt) {
        this.sessionPk = sessionPk;
        this.wardId = wardId;
        this.checkDate = checkDate;
        this.slot = slot;
        this.emotion = emotion;
        this.selfReport = selfReport;
        this.sleep = sleep;
        this.meal = meal;
        this.pain = pain;
        this.mood = mood;
        this.lonely = lonely;
        this.riskEmergency = riskEmergency;
        this.riskSelfHarm = riskSelfHarm;
        this.summary = summary;
        this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
        this.turns = turns;
        this.endReason = endReason;
        this.avgAnswerSec = avgAnswerSec;
        this.avgSpeechSec = avgSpeechSec;
        this.avgPauseRatio = avgPauseRatio;
        this.avgCharsPerSec = avgCharsPerSec;
        this.avgLoudnessDb = avgLoudnessDb;
        this.createdAt = createdAt;
    }

    /** 근거 = 어느 신호를 몇 번째 턴에서 들었는지(원문 없음). */
    public record Evidence(String signal, int turnNo) {
    }
}
