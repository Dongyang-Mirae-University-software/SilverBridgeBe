package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionTranscriptConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CompanionTranscriptConsentRepository extends JpaRepository<CompanionTranscriptConsent, String> {

    /**
     * 턴 저장 트랜잭션에서 동의 행을 공유 잠금으로 읽는다. 철회(행 DELETE)와 부딪히면 철회가 이 트랜잭션 커밋을 기다린 뒤
     * 글 비우기를 하므로, 철회 직전에 들어온 턴의 글도 남지 않는다. 철회가 먼저면 여기서 행이 없어 글을 저장하지 않는다.
     */
    @Query(value = "SELECT 1 FROM companion_transcript_consent WHERE ward_id = :wardId FOR SHARE", nativeQuery = true)
    List<Integer> lockIfConsented(@Param("wardId") String wardId);

    /** 동의 - 이미 동의했으면 처음 동의 시각을 유지한다(동시 요청에도 PK 위반 없음). */
    @Modifying
    @Query(value = """
            INSERT INTO companion_transcript_consent (ward_id, consented_at) VALUES (:wardId, :now)
            ON CONFLICT (ward_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("wardId") String wardId, @Param("now") java.time.OffsetDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CompanionTranscriptConsent c WHERE c.wardId = :wardId")
    int deleteByWardId(@Param("wardId") String wardId);
}
