package kr.silverbridge.main.domain.inquiry.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import kr.silverbridge.main.global.enums.InquiryCategory;
import kr.silverbridge.main.global.enums.InquiryStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 고객센터 문의. 보호자가 작성하고 관리자가 답변한다.
 *
 * <p>작성자·답변자는 {@code @ManyToOne} 대신 {@code String userId}(6자리)로만 저장한다
 * (프로젝트 FK 관례 — 연관 User가 필요하면 서비스에서 배치 조회해 DTO로 조립).
 * createdAt/updatedAt은 {@link BaseTimeEntity}가 제공한다.</p>
 */
@Entity
@Table(name = "inquiries", indexes = {
        @Index(name = "idx_inquiries_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_inquiries_status_created", columnList = "status, created_at"),
        @Index(name = "idx_inquiries_category", columnList = "category")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Inquiry extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 작성자(보호자)
    @Column(name = "user_id", nullable = false, length = 6)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryCategory category;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryStatus status;

    // 관리자 답변 내용 (답변 전 null)
    @Column(columnDefinition = "TEXT")
    private String answer;

    // 답변 관리자 (답변 전 null)
    @Column(name = "answered_by", length = 6)
    private String answeredBy;

    @Column(name = "answered_at")
    private OffsetDateTime answeredAt;

    // 관리자 답변 등록 (WAITING → ANSWERED 전환 + 답변자·시각 기록)
    public void answer(String answer, String adminId) {
        this.answer = answer;
        this.answeredBy = adminId;
        this.answeredAt = OffsetDateTime.now();
        this.status = InquiryStatus.ANSWERED;
    }

    public boolean isAnswered() {
        return this.status == InquiryStatus.ANSWERED;
    }
}