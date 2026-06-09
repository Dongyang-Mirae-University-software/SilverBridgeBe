package kr.silverbridge.main.domain.sos.repository;

import kr.silverbridge.main.domain.sos.entity.SosEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SosEventRepository extends JpaRepository<SosEvent, Long> {
}
