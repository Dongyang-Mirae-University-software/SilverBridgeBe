package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.config.CameraUploadProperties;
import kr.silverbridge.main.domain.camera.dto.UploadTicketResponse;
import kr.silverbridge.main.domain.camera.service.CameraUploadTicketService.UploadTicket;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.jwt.TokenInvalidation;
import kr.silverbridge.main.global.security.RateLimitService;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 송출(피보호자 기기 -> BE -> AI) H.264 WebSocket의 인가 - 티켓 발급, 핸드셰이크 소비, 수립 후 재확인(2026-10-14).
 *
 * <p><b>인가</b>: 피보호자 본인 카메라만이다({@link CameraService#getOwnedSessionId}). 시청과 달리 {@code is_active}는
 * 보지 않는다 - 꺼진 카메라도 감지·알림은 계속되므로(ANOM-G08) 송출 자격을 막으면 "끄면 감지가 멈춘다"는 정반대 정책이 된다.
 * 보호자·관리자용 송출 자격은 만들지 않는다.</p>
 *
 * <p><b>Redis 장애</b>: 발급·소비는 fail-closed(503 - 일반 인증 필터가 이미 무효화 조회 실패를 503으로 막는다. SOS 하나만 fail-open
 * 목록에 있고 넓히지 않는다). 수립된 연결은 이어 간다 - 재확인의 무효화 키 조회 실패는 통과다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraUploadService {

    private final CameraService cameraService;
    private final CameraUploadTicketService ticketService;
    private final CameraUploadProperties properties;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final RateLimitService rateLimitService;

    /** 핸드셰이크를 통과한 연결의 신원 - 재확인이 이 값으로 계정·카메라·무효화를 다시 본다. */
    public record UploadGrant(String wardId, String sessionId, long issuedAtMillis) {}

    /** 새 수립(발급·핸드셰이크)을 받을 수 있는 상태인가. 킬 스위치와 공개 주소 설정을 함께 본다. */
    public boolean isAcceptingNewConnections() {
        return properties.isTicketEnabled();
    }

    public UploadTicketResponse issueTicket(String wardId, Long cameraId) {
        // 인가보다 먼저 - 가장 싼 검사로 반복 호출을 막는다(시청 티켓과 같은 순서)
        rateLimitService.check(CameraStreamRateLimit.UPLOAD_TICKET.endpoint, wardId,
                CameraStreamRateLimit.UPLOAD_TICKET.perMinute, CameraStreamRateLimit.UPLOAD_TICKET.perHour);
        if (!properties.isTicketEnabled() || isBlank(properties.getWsUrl())) {
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_UNAVAILABLE);
        }
        String sessionId = cameraService.getOwnedSessionId(wardId, cameraId);   // 타인 403 + [IDOR-ATTEMPT], 없음 404
        requireEnabledFor(sessionId);
        String ticket = ticketService.issue(wardId, sessionId);
        return new UploadTicketResponse(ticket, properties.getTicketTtl().toSeconds(), properties.getWsUrl());
    }

    /**
     * 핸드셰이크 인가 - 형식 -> GETDEL -> 계정 -> 카메라 -> 플래그 -> 발급 뒤 무효화. 실패는 모두 WS 수립 전 HTTP 오류다.
     */
    public UploadGrant authorize(String ticket) {
        if (!properties.isTicketEnabled()) {
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_UNAVAILABLE);
        }
        UploadTicket consumed = ticketService.consume(ticket);
        requireActiveAccount(consumed.wardId());
        if (!cameraService.isOwnedSession(consumed.wardId(), consumed.sessionId())) {
            // 발급 뒤 카메라가 삭제됐거나 소유가 바뀜 - 내용은 알리지 않는다
            throw new CustomException(ErrorCode.CAMERA_NOT_FOUND);
        }
        requireEnabledFor(consumed.sessionId());
        if (isRevokedSince(consumed.wardId(), consumed.issuedAtMillis())) {
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
        }
        return new UploadGrant(consumed.wardId(), consumed.sessionId(), consumed.issuedAtMillis());
    }

    /**
     * 수립 후 주기 재확인 - 계정 이용 중 + 본인 카메라 존재 + 플래그 + 발급 뒤 토큰 무효화 없음.
     * DB 조회가 실패하면 끊는다(확인할 수 없으면 이어 주지 않는다). 무효화 키(Redis) 조회만 실패하면 이어 간다.
     * 킬 스위치는 보지 않는다 - 새 수립만 막는다.
     */
    public boolean isStillValid(UploadGrant grant) {
        try {
            Optional<Status> status = userRepository.findStatusById(grant.wardId());
            if (status.isEmpty() || status.get() != Status.ACTIVE) {
                return false;
            }
            if (!cameraService.isOwnedSession(grant.wardId(), grant.sessionId())) {
                return false;
            }
        } catch (RuntimeException e) {
            log.warn("[CAMERA-UPLOAD] 재확인 실패 - 연결 종료: sessionId={}, error={}",
                    grant.sessionId(), e.getClass().getSimpleName());
            return false;
        }
        if (!CameraTransport.WS_H264.equals(
                CameraTransport.resolve(grant.sessionId(), properties.getWsH264SessionIds()))) {
            return false;   // 목록에서 빠졌다 = 롤백(env 한 줄) - 열린 연결도 60초 안에 JPEG로 돌아간다
        }
        return !isRevokedSince(grant.wardId(), grant.issuedAtMillis());
    }

    private void requireEnabledFor(String sessionId) {
        if (!CameraTransport.WS_H264.equals(CameraTransport.resolve(sessionId, properties.getWsH264SessionIds()))) {
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_NOT_ENABLED);
        }
    }

    private void requireActiveAccount(String wardId) {
        Optional<Status> status = userRepository.findStatusById(wardId);
        if (status.isEmpty()) {
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
        }
        if (status.get() != Status.ACTIVE) {
            throw new CustomException(ErrorCode.INACTIVE_USER);
        }
    }

    // 티켓 발급 뒤 비밀번호 변경·정지·역할 변경으로 토큰이 무효화됐는가(HTTP 필터와 같은 비교 - TokenInvalidation)
    private boolean isRevokedSince(String wardId, long issuedAtMillis) {
        try {
            String stored = redisTemplate.opsForValue().get(RedisKeys.PASSWORD_INVALIDATE + wardId);
            return stored != null && TokenInvalidation.isRevoked(issuedAtMillis, TokenInvalidation.parseEpochSecond(stored));
        } catch (RuntimeException e) {
            log.warn("[CAMERA-UPLOAD] 토큰 무효화 조회 실패 - 계정·카메라 확인만으로 진행: error={}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
