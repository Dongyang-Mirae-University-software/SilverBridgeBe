package kr.silverbridge.main.domain.announcement.repository;

import kr.silverbridge.main.domain.announcement.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
}
