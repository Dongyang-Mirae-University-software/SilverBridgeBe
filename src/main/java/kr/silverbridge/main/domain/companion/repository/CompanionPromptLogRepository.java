package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionPromptLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanionPromptLogRepository extends JpaRepository<CompanionPromptLog, Long> {

    /** 역할 변경(피보호자 → 보호자) 때 질문 안내 기록도 지운다(2026-11-04 점검 M-1). */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CompanionPromptLog p WHERE p.wardId = :wardId")
    int deleteAllByWardId(@Param("wardId") String wardId);
}
