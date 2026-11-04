package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionPromptLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CompanionPromptLogRepository extends JpaRepository<CompanionPromptLog, Long> {

    /** 관리자 집계 - 그 날 보낸 질문 안내(응답률의 분모). */
    List<CompanionPromptLog> findByPromptDate(LocalDate promptDate);

    /** 역할 변경(피보호자 → 보호자) 때 질문 안내 기록도 지운다(2026-11-04 점검 M-1). */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CompanionPromptLog p WHERE p.wardId = :wardId")
    int deleteAllByWardId(@Param("wardId") String wardId);
}
