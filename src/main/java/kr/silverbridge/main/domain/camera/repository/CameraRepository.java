package kr.silverbridge.main.domain.camera.repository;

import kr.silverbridge.main.domain.camera.entity.Camera;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CameraRepository extends JpaRepository<Camera, Long> {

    // 피보호자 본인 카메라 목록 (방별, 최신순)
    List<Camera> findByWardIdOrderByCreatedAtDesc(String wardId);

    // 재등록 멱등 — 같은 피보호자·같은 기기의 기존 카메라
    Optional<Camera> findByWardIdAndDeviceId(String wardId, String deviceId);

    // AI 이상감지 신호(sessionId) → 소유 피보호자 매핑
    Optional<Camera> findBySessionId(String sessionId);

    // 이상감지 이력 화면의 위치 표시 — sessionId 묶음으로 한 번에 조회(건별 조회로 인한 N+1 회피)
    List<Camera> findBySessionIdIn(Collection<String> sessionIds);

    // 보호자 allowlist — 연결된 피보호자들의 활성 카메라 일괄 조회 (N+1 없이 단일 IN 쿼리)
    List<Camera> findByWardIdInAndIsActiveTrue(Collection<String> wardIds);

    boolean existsBySessionId(String sessionId);   // SessionID 발급기 중복 검사
    boolean existsByDeviceId(String deviceId);      // DeviceID 발급기 중복 검사
}
