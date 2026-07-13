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

    // 보호자 allowlist — 연결된 피보호자들의 활성 카메라 일괄 조회 (N+1 없이 단일 IN 쿼리)
    List<Camera> findByWardIdInAndIsActiveTrue(Collection<String> wardIds);

    boolean existsBySessionId(String sessionId);   // SessionID 발급기 중복 검사
    boolean existsByDeviceId(String deviceId);      // DeviceID 발급기 중복 검사
}
