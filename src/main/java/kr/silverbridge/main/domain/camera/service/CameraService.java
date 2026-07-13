package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.dto.CameraRegisterRequest;
import kr.silverbridge.main.domain.camera.dto.CameraResponse;
import kr.silverbridge.main.domain.camera.dto.CameraUpdateRequest;
import kr.silverbridge.main.domain.camera.dto.GuardianCameraView;
import kr.silverbridge.main.domain.camera.entity.Camera;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 이상감지 카메라 서비스.
 *
 * <p>피보호자는 본인 카메라만 CRUD 하고(타인 것은 404 위장으로 IDOR 차단),
 * 보호자는 ACTIVE 연결된 피보호자들의 활성 카메라만 allowlist로 조회한다 —
 * 별도 카메라-보호자 매핑 없이 기존 {@code connections}를 재사용하므로 연결이 끊기면 접근도 자동 소멸한다.</p>
 */
@Service
@RequiredArgsConstructor
public class CameraService {

    private final CameraRepository cameraRepository;
    private final ConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final CameraIdentifierFactory identifierFactory;

    // 서버가 소유하는 권장 송출 fps (FE 매직상수 방지) — application.yaml camera.recommended-fps
    @Value("${camera.recommended-fps:5}")
    private int recommendedFps;

    /**
     * 카메라 등록/재등록. {@code (wardId, deviceId)} 기준 멱등 —
     * 같은 기기가 다시 등록하면 기존 SessionID를 그대로 재사용하고 방 이름만 갱신한다.
     * deviceId가 없거나 본인 소유가 아니면 새 SessionID·DeviceID를 발급한다.
     */
    @Transactional
    public CameraResponse register(String wardId, CameraRegisterRequest request) {
        Optional<Camera> existing = findOwnedByDeviceId(wardId, request.deviceId());
        if (existing.isPresent()) {
            Camera camera = existing.get();
            camera.rename(request.label());
            return CameraResponse.of(camera, recommendedFps);
        }

        Camera camera = Camera.builder()
                .wardId(wardId)
                .registeredBy(wardId)
                .label(request.label())
                .sessionId(identifierFactory.newSessionId(wardId))
                .deviceId(identifierFactory.newDeviceId())
                .isActive(true)
                .build();

        return CameraResponse.of(cameraRepository.save(camera), recommendedFps);
    }

    // 내 카메라 목록 (방별, 최신순)
    @Transactional(readOnly = true)
    public List<CameraResponse> getMyCameras(String wardId) {
        return cameraRepository.findByWardIdOrderByCreatedAtDesc(wardId).stream()
                .map(camera -> CameraResponse.of(camera, recommendedFps))
                .toList();
    }

    // 방 이름 변경 / 사용 토글 (전달한 필드만 갱신)
    @Transactional
    public CameraResponse update(String wardId, Long cameraId, CameraUpdateRequest request) {
        Camera camera = getOwnedCamera(wardId, cameraId);

        if (request.label() != null) {
            camera.rename(request.label());
        }
        if (request.isActive() != null) {
            if (request.isActive()) {
                camera.activate();
            } else {
                camera.deactivate();
            }
        }
        return CameraResponse.of(camera, recommendedFps);
    }

    @Transactional
    public void delete(String wardId, Long cameraId) {
        cameraRepository.delete(getOwnedCamera(wardId, cameraId));
    }

    /**
     * 보호자 allowlist — ACTIVE 연결된 피보호자들의 활성 카메라.
     * 피보호자 이름은 배치 조회로 채운다(N+1 회피, ConnectionService.getMyWards 동일 패턴).
     */
    @Transactional(readOnly = true)
    public List<GuardianCameraView> getConnectedWardCameras(String guardianId) {
        List<String> wardIds = connectionRepository
                .findByGuardianIdAndStatusInOrderByCreatedAtDesc(guardianId, List.of(ConnectionStatus.ACTIVE))
                .stream()
                .map(Connection::getWardId)
                .distinct()
                .toList();

        if (wardIds.isEmpty()) {
            return List.of();
        }

        Map<String, String> wardNames = userRepository.findAllById(wardIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        return cameraRepository.findByWardIdInAndIsActiveTrue(wardIds).stream()
                .map(camera -> GuardianCameraView.of(camera, wardNames.get(camera.getWardId())))
                .toList();
    }

    // 전달된 deviceId가 본인 소유일 때만 기존 카메라로 인정 (타인/무효 토큰은 신규 발급 경로로)
    private Optional<Camera> findOwnedByDeviceId(String wardId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return Optional.empty();
        }
        return cameraRepository.findByWardIdAndDeviceId(wardId, deviceId);
    }

    // 조회 + 소유권 검증 (없거나 타인 것이면 404 위장으로 IDOR 차단)
    private Camera getOwnedCamera(String wardId, Long cameraId) {
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new CustomException(ErrorCode.CAMERA_NOT_FOUND));
        if (!camera.getWardId().equals(wardId)) {
            throw new CustomException(ErrorCode.CAMERA_NOT_FOUND);
        }
        return camera;
    }
}
