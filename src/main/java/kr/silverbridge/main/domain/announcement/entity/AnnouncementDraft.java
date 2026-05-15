package kr.silverbridge.main.domain.announcement.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.*;

@Entity
@Table(name = "announcement_drafts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class AnnouncementDraft extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author_id", length = 6)
    private String authorId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    public void update(String title, String content) {
        this.title = title;
        this.content = content;
    }
}
