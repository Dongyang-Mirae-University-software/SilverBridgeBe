package kr.silverbridge.main.domain.announcement.repository;

import kr.silverbridge.main.domain.announcement.entity.AnnouncementDraft;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementDraftRepository extends JpaRepository<AnnouncementDraft, Long> {
}