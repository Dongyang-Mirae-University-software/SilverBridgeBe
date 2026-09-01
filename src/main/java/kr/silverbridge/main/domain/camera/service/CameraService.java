package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.dto.CameraRegisterRequest;
import kr.silverbridge.main.domain.camera.dto.CameraResponse;
import kr.silverbridge.main.domain.camera.dto.CameraUpdateRequest;
import kr.silverbridge.main.domain.camera.dto.GuardianCameraView;
import kr.silverbridge.main.domain.camera.entity.Camera;
import kr.silverbridge.main.domain.camera.event.CameraRegisteredEvent;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraService {

    private final CameraRepository cameraRepository;
    private final ConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final CameraIdentifierFactory identifierFactory;
    private final ApplicationEventPublisher eventPublisher;

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
            publishRegistered(camera);
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

        Camera saved = cameraRepository.save(camera);
        publishRegistered(saved);
        return CameraResponse.of(saved, recommendedFps);
    }

    /**
     * 등록 사실을 알려 이상감지 구독자가 AI 세션 목록을 다시 확인하게 한다(재등록도 포함 — 구독 갱신은 멱등).
     * AI는 세션 생성·종료 시에만 목록을 broadcast하므로, 스트리밍이 먼저 시작된 경우 이 재확인이 없으면
     * 해당 세션은 구독되지 않는다.
     */
    private void publishRegistered(Camera camera) {
        eventPublisher.publishEvent(new CameraRegisteredEvent(camera.getWardId(), camera.getSessionId()));
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

    /**
     * AI 이상감지 신호의 {@code sessionId}를 소유 피보호자·설치 위치로 매핑한다(anomaly 도메인 협력용).
     *
     * <p>백엔드에 등록되지 않은 세션(직접 AI에 붙은 카메라 등)은 소유자를 알 수 없으므로 빈 값을 돌려주고,
     * 호출부가 해당 신호를 버린다 — 소유권 없는 세션의 이력을 남기지 않는다.</p>
     *
     * <p>위치({@code label})는 알림 문구("…님 댁 <b>거실</b>에서 화재가 감지되었습니다")에 쓰인다.</p>
     */
    @Transactional(readOnly = true)
    public Optional<CameraOwner> findOwnerBySessionId(String sessionId) {
        return cameraRepository.findBySessionId(sessionId)
                .map(camera -> new CameraOwner(camera.getWardId(), camera.getLabel()));
    }

    /**
     * {@code sessionId} → 설치 위치 맵(anomaly 도메인 협력용). 이상감지 이력 목록에서 "어디서 감지됐는지"를
     * 표시하는 데 쓴다.
     *
     * <p>삭제된 카메라의 세션은 맵에 없어 호출부에서 {@code null}(위치 미상)이 된다 - 카메라가 사라져도
     * 과거 이력은 남아야 하므로 이력 쪽을 지우거나 빈 문자열로 채우지 않는다.</p>
     */
    @Transactional(readOnly = true)
    public Map<String, String> findLabelsBySessionIds(Collection<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        return cameraRepository.findBySessionIdIn(sessionIds).stream()
                .collect(Collectors.toMap(Camera::getSessionId, Camera::getLabel));
    }

    // 전달된 deviceId가 본인 소유일 때만 기존 카메라로 인정 (타인/무효 토큰은 신규 발급 경로로)
    private Optional<Camera> findOwnedByDeviceId(String wardId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return Optional.empty();
        }
        return cameraRepository.findByWardIdAndDeviceId(wardId, deviceId);
    }

    /**
     * 조회 + 소유권 검증. 없으면 404, <b>타인 것이면 403 + 명시적 안내</b>.
     *
     * <p>이전에는 타인 카메라를 404로 위장했으나(존재 노출 차단), 무슨 일인지 알 수 없는 오류로 시니어가 이탈하는
     * 것을 막기 위해 그대로 알린다(2026-07-14 정책). 노출은 "그 id의 카메라가 있다"는 사실뿐이며(방 이름·세션ID 등
     * 내용은 주지 않는다), 시도는 WARN으로 남긴다.</p>
     */
    private Camera getOwnedCamera(String wardId, Long cameraId) {
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new CustomException(ErrorCode.CAMERA_NOT_FOUND));
        if (!camera.getWardId().equals(wardId)) {
            log.warn("[IDOR-ATTEMPT] 타인 카메라 접근 시도: wardId={}, cameraId={}", wardId, cameraId);
            throw new CustomException(ErrorCode.CAMERA_NOT_AUTHORIZED);
        }
        return camera;
    }
}
