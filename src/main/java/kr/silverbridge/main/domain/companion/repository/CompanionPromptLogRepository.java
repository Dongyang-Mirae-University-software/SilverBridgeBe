package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionPromptLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanionPromptLogRepository extends JpaRepository<CompanionPromptLog, Long> {
}
