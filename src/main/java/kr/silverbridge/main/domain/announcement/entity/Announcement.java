package kr.silverbridge.main.domain.announcement.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "announcements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Announcement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author_id", length = 36)
    private String authorId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_published", nullable = false)
    private boolean isPublished;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    public void update(String title, String content) {
        this.title = title;
        this.content = content;
    }

    // 발행 토글 (미발행 → 발행, 발행 → 취소)
    public void togglePublish() {
        this.isPublished = !this.isPublished;
        this.publishedAt = this.isPublished ? OffsetDateTime.now() : null;
    }
}
