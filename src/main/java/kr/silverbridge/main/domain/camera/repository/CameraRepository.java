package kr.silverbridge.main.domain.camera.repository;

import kr.silverbridge.main.domain.camera.entity.Camera;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CameraRepository extends JpaRepository<Camera, Long> {

    // 피보호자 본인 카메라 목록 (방별, 최신순)
    List<Camera> findByWardIdOrderByCreatedAtDesc(String wardId);

    // 재등록 멱등 — 같은 피보호자·같은 기기의 기존 카메라
    Optional<Camera> findByWardIdAndDeviceId(String wardId, String deviceId);

    // 한 방에는 카메라 1대 - 같은 피보호자의 같은 방 이름 카메라(DB uq_camera_ward_label와 같은 기준)
    Optional<Camera> findByWardIdAndLabel(String wardId, String label);

    // AI 이상감지 신호(sessionId) → 소유 피보호자 매핑
    Optional<Camera> findBySessionId(String sessionId);

    // 이상감지 이력 화면의 위치 표시 — sessionId 묶음으로 한 번에 조회(건별 조회로 인한 N+1 회피)
    List<Camera> findBySessionIdIn(Collection<String> sessionIds);

    // 이상감지 이력의 "삭제된 카메라" 필터 - 피보호자들의 지금 등록된 카메라 sessionId (사용 중지 포함)
    @Query("select c.sessionId from Camera c where c.wardId in :wardIds")
    List<String> findSessionIdsByWardIdIn(@Param("wardIds") Collection<String> wardIds);

    // 보호자 allowlist — 연결된 피보호자들의 활성 카메라 일괄 조회 (N+1 없이 단일 IN 쿼리)
    List<Camera> findByWardIdInAndIsActiveTrue(Collection<String> wardIds);

    boolean existsBySessionId(String sessionId);   // SessionID 발급기 중복 검사

    // 회원의 카메라 전부 삭제 - 관리자 역할 변경(WARD → GUARDIAN) 시 고아 방지. 삭제 건수를 돌려준다.
    long deleteByWardId(String wardId);
    boolean existsByDeviceId(String deviceId);      // DeviceID 발급기 중복 검사

    /**
     * 관리자 이상감지 로그 검색 - 설치 위치(label) 부분일치로 sessionId만 모은다.
     * {@code keyword}는 호출부가 소문자화·LIKE 메타문자 이스케이프를 마친 값이다({@code escape '\'}와 짝).
     */
    @Query("select c.sessionId from Camera c where lower(c.label) like concat('%', :keyword, '%') escape '\\'")
    List<String> findSessionIdsByLabelContaining(@Param("keyword") String keyword);

    /**
     * 서비스 전체 카메라 등록 상한 검사를 한 줄로 세우는 트랜잭션 수준 어드바이저리 락(커밋·롤백 때 자동 해제).
     * 카운트 → INSERT 사이의 경합으로 상한을 넘지 않게 한다. 새 카메라를 만드는 경로에서만 잡는다.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(:key)) t", nativeQuery = true)
    int lockTotalLimit(@Param("key") long key);

    /**
     * 이상감지 클립 기록용 - 카메라 행을 {@code FOR KEY SHARE}로 잠그고 소유 피보호자 ID를 돌려준다(2026-10 전체 점검 M-7).
     *
     * <p>잠근 동안 카메라 삭제가 기다리므로, 삭제 정리({@code CameraDeletedEvent})는 이 클립 행이 커밋된 뒤에 돌아 함께 지운다.
     * 삭제가 먼저 커밋됐으면 빈 값이다. {@code KEY SHARE}는 방 이름·사용 중지 같은 일반 UPDATE와는 충돌하지 않는다.</p>
     */
    @Query(value = "SELECT ward_id FROM camera WHERE session_id = :sessionId FOR KEY SHARE", nativeQuery = true)
    Optional<String> lockOwnerWardIdForKeyShare(@Param("sessionId") String sessionId);

    // ===== 관리자 대시보드 집계 =====

    /** 활성(isActive=true) 카메라 수 - 대시보드 "끊김" 계산의 분모(ADMIN-G20). 비활성 카메라는 구독 대상이 아니다. */
    long countByIsActiveTrue();

    /** 카메라를 1대 이상 등록한 피보호자 수. */
    @Query("select count(distinct c.wardId) from Camera c")
    long countDistinctWards();
}
