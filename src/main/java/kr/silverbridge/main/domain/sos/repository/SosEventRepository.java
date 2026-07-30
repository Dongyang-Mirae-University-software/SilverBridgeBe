package kr.silverbridge.main.domain.sos.repository;

import kr.silverbridge.main.domain.sos.entity.SosEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface SosEventRepository extends JpaRepository<SosEvent, Long> {

    /**
     * 보호자 화면용 SOS 이력 조회 — 여러 피보호자의 이력을 발생 최신순으로 합쳐서 페이징한다.
     *
     * <p>정렬을 메서드 이름에 고정했으므로 호출부는 {@code Sort} 없는 {@code PageRequest}를 넘긴다
     * (둘 다 주면 정렬이 이중으로 적용된다). {@code idx_sos_events_ward_created (ward_id, created_at DESC)}를
     * 활용한다.</p>
     *
     * @param wardIds 조회 대상 피보호자 ID들 — <b>인가된(ACTIVE 연결) 목록만</b> 넘겨야 한다(IDOR 방지)
     */
    Page<SosEvent> findByWardIdInOrderByCreatedAtDesc(Collection<String> wardIds, Pageable pageable);
}
