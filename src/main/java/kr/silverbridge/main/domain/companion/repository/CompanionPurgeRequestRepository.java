package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionPurgeRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanionPurgeRequestRepository extends JpaRepository<CompanionPurgeRequest, String> {

    List<CompanionPurgeRequest> findAllByOrderByRequestedAtAsc(Pageable pageable);
}
