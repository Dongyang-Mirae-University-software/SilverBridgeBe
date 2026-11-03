package kr.silverbridge.main.domain.companion.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 안부 질문 알림 발송 기록. <b>행의 존재 = 그 시간대 알림을 이미 보냈다</b>(선점 후 발송 - 복약 알림과 같은 방식).
 * {@code (ward_id, prompt_date, slot)} UNIQUE가 재기동·중복 실행을 막는다. 발송이 실패해도 다시 보내지 않는다.
 */
@Entity
@Table(name = "companion_prompt_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanionPromptLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    @Column(name = "prompt_date", nullable = false)
    private LocalDate promptDate;

    @Convert(converter = CompanionSlotConverter.class)
    @Column(nullable = false, length = 5)
    private CompanionSlot slot;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    private CompanionPromptLog(String wardId, LocalDate promptDate, CompanionSlot slot, OffsetDateTime sentAt) {
        this.wardId = wardId;
        this.promptDate = promptDate;
        this.slot = slot;
        this.sentAt = sentAt;
    }

    public static CompanionPromptLog of(String wardId, LocalDate promptDate, CompanionSlot slot, OffsetDateTime sentAt) {
        return new CompanionPromptLog(wardId, promptDate, slot, sentAt);
    }
}
