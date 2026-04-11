package kr.silverbridge.main.domain.game.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.enums.GameType;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

@Entity
@Table(name = "game_results", indexes = {
        @Index(name = "idx_game_results_user_played", columnList = "user_id, played_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class GameResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_type", nullable = false, length = 30)
    private GameType gameType;

    @Column(nullable = false)
    private int difficulty;

    @Column(name = "is_cleared", nullable = false)
    private boolean isCleared;

    @Column
    private Integer score;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @CreatedDate
    @Column(name = "played_at", nullable = false, updatable = false)
    private OffsetDateTime playedAt;
}
