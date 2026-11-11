package kr.silverbridge.main.domain.companion.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 안부 대화 턴 1개 (V71, 2026-11-11). 기분은 항상, 글 두 칸은 피보호자가 대화 내용 저장에 동의했을 때만 담는다.
 * 글은 30일 뒤 비운다({@code CompanionTranscriptRetentionScheduler}). 글은 화면 표시용 문자열 그대로이며 로그·알림·감사 로그에
 * 넣지 않는다 - 이 엔티티에 toString을 만들지 말 것.
 */
@Entity
@Table(name = "companion_turn")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanionTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "turn_no", nullable = false)
    private int turnNo;

    /** null = 판단할 수 없음. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CompanionMood mood;

    /** 이 대답 뒤에 말벗이 한 말(기기에 나간 문장 그대로). 동의 없음·30일 경과면 null. */
    @Column(name = "bot_text", columnDefinition = "TEXT")
    private String botText;

    /** 어르신 대답 - 글이면 보낸 글, 음성이면 AI가 받아 적은 글. 동의 없음·받아 적지 못함·30일 경과면 null. */
    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    private CompanionTurn(Long sessionId, int turnNo, CompanionMood mood, String botText, String answerText,
                          OffsetDateTime createdAt) {
        this.sessionId = sessionId;
        this.turnNo = turnNo;
        this.mood = mood;
        this.botText = botText;
        this.answerText = answerText;
        this.createdAt = createdAt;
    }

    public static CompanionTurn of(Long sessionId, int turnNo, CompanionMood mood, String botText, String answerText,
                                   OffsetDateTime createdAt) {
        return new CompanionTurn(sessionId, turnNo, mood, botText, answerText, createdAt);
    }

    public boolean hasText() {
        return botText != null || answerText != null;
    }
}
