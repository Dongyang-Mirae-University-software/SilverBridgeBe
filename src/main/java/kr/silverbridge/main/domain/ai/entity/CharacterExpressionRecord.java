package kr.silverbridge.main.domain.ai.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

@Getter
@Entity
@Table(name = "character_expressions")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterExpressionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    @Column(nullable = false, length = 50)
    private String expression;

    @Column
    private Double confidence;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    public CharacterExpressionRecord(String wardId, String expression, Double confidence) {
        this.wardId = wardId;
        this.expression = expression;
        this.confidence = confidence;
    }
}
