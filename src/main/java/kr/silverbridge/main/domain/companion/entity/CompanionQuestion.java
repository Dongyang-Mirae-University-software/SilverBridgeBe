package kr.silverbridge.main.domain.companion.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시간대별 고정 질문(V65). 같은 문장에는 같은 번호를 쓴다 - 문장을 고칠 때는 행을 바꾸지 말고 {@code active=false}로
 * 내리고 새 행을 넣는다(지난 대화의 {@code question_id}가 다른 문장을 가리키지 않게).
 */
@Entity
@Table(name = "companion_question")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanionQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Convert(converter = CompanionSlotConverter.class)
    @Column(nullable = false, length = 5)
    private CompanionSlot slot;

    @Column(nullable = false, length = 200)
    private String text;

    @Column(nullable = false)
    private boolean active;
}
