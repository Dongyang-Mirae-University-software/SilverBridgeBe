package kr.silverbridge.main.domain.sos.repository;

import kr.silverbridge.main.domain.sos.entity.SosEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
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

    /**
     * 최근 집계 창 안에서 같은 피보호자가 발생시킨 SOS 건수 - 보호자 알림 문구의 "N번째" 표기용.
     *
     * <p>연타는 이력에 전부 남지만 알림은 쿨다운으로 합쳐진다({@code SosNotificationCooldown}). 그래서
     * 두 번째 이후 알림이 첫 번째와 문구가 같으면 보호자가 "새 상황"과 "같은 상황이 계속됨"을 구분할 수 없다.
     * 생략된 연타를 이 카운트로 되살려 문구에 담는다.</p>
     *
     * <p>{@code idx_sos_events_ward_created (ward_id, created_at DESC)}를 그대로 타므로 별도 인덱스가 필요 없다.
     * 발송 직전에 한 번만 호출한다 - 쿨다운에 막힌 연타는 이 쿼리에 도달하지 않는다.</p>
     *
     * @param wardId SOS를 발생시킨 피보호자 ID
     * @param from   집계 창의 시작 시각(이 시각 이후 발생분만 센다). 달력상 "오늘"이 아니라 흐르는 창이라
     *               타임존과 무관하다 - KST 변환이 필요 없다
     */
    long countByWardIdAndCreatedAtGreaterThanEqual(String wardId, OffsetDateTime from);
}
