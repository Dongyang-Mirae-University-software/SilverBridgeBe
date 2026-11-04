package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionPurgeRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CompanionPurgeRequestRepository extends JpaRepository<CompanionPurgeRequest, String> {

    /**
     * 재시도 순서 - 한 번도 시도하지 않은 것, 그다음 마지막 시도가 오래된 것부터(2026-11-04 점검 L-2).
     * 요청 시각 순으로 돌면 계속 실패하는 1건이 매번 맨 앞에 서서, 첫 실패 중단 규칙 때문에 뒤를 모두 막는다.
     */
    @Query("SELECT p FROM CompanionPurgeRequest p ORDER BY p.lastAttemptAt ASC NULLS FIRST, p.requestedAt ASC")
    List<CompanionPurgeRequest> findRetryOrder(Pageable pageable);
}
