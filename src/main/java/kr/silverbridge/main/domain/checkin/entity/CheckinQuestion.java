package kr.silverbridge.main.domain.checkin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 보호자가 정한 시각별 안부 질문(피보호자 단위, 보호자끼리 공유 - 2026-11-01 결정 Q4). */
@Entity
@Table(name = "checkin_question", uniqueConstraints = {
        @UniqueConstraint(name = "uq_checkin_question_ward_slot", columnNames = {"ward_id", "slot"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckinQuestion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    @Convert(converter = CheckinSlotConverter.class)
    @Column(name = "slot", nullable = false, length = 5)
    private CheckinSlot slot;

    @Column(name = "question", nullable = false, length = 200)
    private String question;

    @Column(name = "updated_by", length = 6)
    private String updatedBy;

    private CheckinQuestion(String wardId, CheckinSlot slot, String question, String updatedBy) {
        this.wardId = wardId;
        this.slot = slot;
        this.question = question;
        this.updatedBy = updatedBy;
    }

    public static CheckinQuestion of(String wardId, CheckinSlot slot, String question, String updatedBy) {
        return new CheckinQuestion(wardId, slot, question, updatedBy);
    }

    public void change(String question, String updatedBy) {
        this.question = question;
        this.updatedBy = updatedBy;
    }
}
