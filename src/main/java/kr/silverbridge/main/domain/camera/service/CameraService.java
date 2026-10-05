package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.dto.CameraRegisterRequest;
import kr.silverbridge.main.domain.camera.dto.CameraResponse;
import kr.silverbridge.main.domain.camera.dto.CameraUpdateRequest;
import kr.silverbridge.main.domain.camera.dto.GuardianCameraView;
import kr.silverbridge.main.domain.camera.entity.Camera;
import kr.silverbridge.main.domain.camera.event.CameraDeletedEvent;
import kr.silverbridge.main.domain.camera.event.CameraRegisteredEvent;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.validation.TextSanitizer;
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
 * <p>피보호자는 본인 카메라만 CRUD 하고(타인 것은 403 + [IDOR-ATTEMPT]로 차단),
 * 보호자는 ACTIVE 연결된 피보호자들의 활성 카메라만 allowlist로 조회한다 —
 * 별도 카메라-보호자 매핑 없이 기존 {@code connections}를 재사용하므로 연결이 끊기면 접근도 자동 소멸한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraService {

    private final CameraRepository cameraRepository;
    private final ConnectionService connectionService;
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
        String label = sanitizeLabel(request.label()); // 수정 경로와 같은 기준 (ANOM-G12 후속)
        Optional<Camera> existing = findOwnedByDeviceId(wardId, request.deviceId());
        if (existing.isPresent()) {
            Camera camera = existing.get();
            camera.rename(label);
            publishRegistered(camera);
            return CameraResponse.of(camera, recommendedFps);
        }

        Camera camera = Camera.builder()
                .wardId(wardId)
                .registeredBy(wardId)
                .label(label)
                .sessionId(identifierFactory.newSessionId())
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
            camera.rename(sanitizeLabel(request.label()));
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

    /**
     * 방 이름을 정리(제어·서식문자 제거, 공백 정리, trim)해 돌려준다. 정리 후 보이는 글자가 없으면 400이다(ANOM-G12).
     * DTO의 {@code @VisibleText}가 1차로 막지만, 방 이름은 화재 알림 문구의 위치로 쓰이므로 서비스에서도 한 번 더 막는다.
     */
    private static String sanitizeLabel(String label) {
        String sanitized = TextSanitizer.sanitize(label);
        if (!TextSanitizer.hasVisibleChar(sanitized)) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        return sanitized;
    }

    /**
     * 카메라 삭제. 그 카메라의 이상감지 클립은 커밋 뒤 anomaly 도메인이 지운다({@link CameraDeletedEvent}).
     */
    @Transactional
    public void delete(String wardId, Long cameraId) {
        Camera camera = getOwnedCamera(wardId, cameraId);
        cameraRepository.delete(camera);
        eventPublisher.publishEvent(new CameraDeletedEvent(camera.getWardId(), List.of(camera.getSessionId())));
    }

    /**
     * 회원의 카메라를 전부 삭제한다 - 관리자 역할 변경(WARD → GUARDIAN)용.
     *
     * <p>카메라는 피보호자 자산이라 보호자가 된 뒤에는 카메라 API를 쓸 수 없어 아무도 지울 수 없는
     * 고아가 되고, AI 구독과 본인 화재 알림은 계속된다. 다시 쓰려면 피보호자 계정으로 재등록해야 한다.</p>
     *
     * <p>AI 구독은 여기서 따로 끊지 않는다 - 피보호자 본인의 삭제와 같은 방식이다. 삭제된 세션은 AI가
     * 목록을 갱신할 때 정리되고, 그 사이 들어오는 신호는 "등록된 카메라 없음"으로 이력이 스킵된다.</p>
     *
     * <p>이 카메라들의 이상감지 클립은 커밋 뒤 anomaly 도메인이 지운다({@link CameraDeletedEvent}).</p>
     *
     * @return 삭제한 카메라 수
     */
    @Transactional
    public int deleteAllByWard(String wardId) {
        // 벌크 삭제 뒤에는 세션을 알 수 없어 먼저 모은다 - 이상감지 클립 정리(CameraDeletedEvent)에 쓴다
        List<String> sessionIds = cameraRepository.findByWardIdOrderByCreatedAtDesc(wardId).stream()
                .map(Camera::getSessionId)
                .toList();
        long deleted = cameraRepository.deleteByWardId(wardId);
        if (deleted > 0) {
            log.info("[CAMERA] 역할 변경으로 카메라 일괄 삭제: wardId={}, {}대", wardId, deleted);
        }
        if (!sessionIds.isEmpty()) {
            eventPublisher.publishEvent(new CameraDeletedEvent(wardId, sessionIds));
        }
        return (int) deleted;
    }

    /**
     * 보호자 allowlist — ACTIVE 연결된 피보호자들의 활성 카메라.
     * 피보호자 이름은 배치 조회로 채운다(N+1 회피, ConnectionService.getMyWards 동일 패턴).
     */
    @Transactional(readOnly = true)
    public List<GuardianCameraView> getConnectedWardCameras(String guardianId) {
        // 인가 목록은 connection 도메인의 getActiveWardIds만 쓴다 - 연결 판정 로직을 밖에서 복제하면
        // 정책이 바뀔 때 이 한 곳만 따라오지 않는다(2026-09-10 점검 G-1).
        List<String> wardIds = connectionService.getActiveWardIds(guardianId);

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
     * 보호자 영상·상태 조회 인가 - 그 세션이 <b>ACTIVE 연결된 피보호자의 활성 등록 카메라</b>일 때만 통과한다.
     *
     * <p>없는·비활성·백엔드 미등록 세션은 모두 404다(미등록은 AI에 송출 중이어도 주인을 모른다). 연결되지 않은
     * 피보호자의 카메라는 403 + {@code [IDOR-ATTEMPT]}다 - 응답에는 소유자·방 이름을 싣지 않는다(2026-07-14 정책).
     * 인가 근거는 {@code isActiveConnection}뿐이다({@code getMyWards}는 PENDING이 섞여 금지).</p>
     */
    @Transactional(readOnly = true)
    public CameraOwner getViewableCamera(String guardianId, String sessionId) {
        Camera camera = cameraRepository.findBySessionId(sessionId)
                .filter(Camera::isActive)
                .orElseThrow(() -> {
                    log.info("[CAMERA-UNKNOWN-SESSION] 등록되지 않았거나 꺼진 카메라 영상 요청: guardianId={}, sessionId={}",
                            guardianId, sessionId);
                    return new CustomException(ErrorCode.CAMERA_NOT_FOUND);
                });
        if (!connectionService.isActiveConnection(guardianId, camera.getWardId())) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 카메라 접근 시도: guardianId={}, sessionId={}",
                    guardianId, sessionId);
            throw new CustomException(ErrorCode.CAMERA_NOT_CONNECTED);
        }
        return new CameraOwner(camera.getWardId(), camera.getLabel());
    }

    /**
     * {@link #getViewableCamera}와 같은 기준의 예외 없는 판정 - 시청 중 주기 재확인용(연결 해제·카메라 삭제·끄기를
     * 열린 영상에 반영한다). 로그는 남기지 않는다(재확인은 정상 경로라 IDOR 시도가 아니다).
     */
    @Transactional(readOnly = true)
    public boolean isViewable(String guardianId, String sessionId) {
        return cameraRepository.findBySessionId(sessionId)
                .filter(Camera::isActive)
                .map(camera -> connectionService.isActiveConnection(guardianId, camera.getWardId()))
                .orElse(false);
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
     * {@link #findOwnerBySessionId}와 같지만 <b>활성 카메라만</b> - 보호자 화면 표시용(실시간 분석 상태) 협력.
     * 꺼진 카메라는 영상·목록에서 빠지므로 분석 상태도 보내지 않는다. 감지·알림 경로는 이 메서드를 쓰지 않는다
     * ({@code is_active}는 감지·알림을 끄지 않는다, ANOM-G08).
     */
    @Transactional(readOnly = true)
    public Optional<CameraOwner> findActiveOwnerBySessionId(String sessionId) {
        return cameraRepository.findBySessionId(sessionId)
                .filter(Camera::isActive)
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

    /**
     * 설치 위치 부분일치 → {@code sessionId} 목록(anomaly 도메인 협력용, 관리자 이상감지 로그 검색).
     * 삭제된 카메라는 위치를 알 수 없어 검색되지 않는다.
     *
     * @param escapedKeyword 소문자화·LIKE 메타문자 이스케이프를 마친 검색어
     */
    @Transactional(readOnly = true)
    public List<String> findSessionIdsByLabelKeyword(String escapedKeyword) {
        return cameraRepository.findSessionIdsByLabelContaining(escapedKeyword);
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
