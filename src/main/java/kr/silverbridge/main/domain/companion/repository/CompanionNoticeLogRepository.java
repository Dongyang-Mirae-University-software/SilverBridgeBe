package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionNoticeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface CompanionNoticeLogRepository extends JpaRepository<CompanionNoticeLog, Long> {

    long countByNoticeDate(LocalDate noticeDate);

    /** 역할 변경(피보호자 → 보호자) 때 함께 지운다(말벗 기록 정리와 같은 트랜잭션). */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CompanionNoticeLog n WHERE n.wardId = :wardId")
    int deleteAllByWardId(@Param("wardId") String wardId);
}
