package kr.silverbridge.main.domain.camera.service;

import jakarta.servlet.http.HttpServletResponse;
import kr.silverbridge.main.domain.camera.client.AiStreamClient;
import kr.silverbridge.main.domain.camera.client.AiStreamClient.AiLiveStream;
import kr.silverbridge.main.domain.camera.client.AiStreamClient.AiMjpegStream;
import kr.silverbridge.main.domain.camera.client.AiStreamUnavailableException;
import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.domain.camera.dto.CameraLiveStatus;
import kr.silverbridge.main.domain.camera.dto.CameraLiveStatusResponse;
import kr.silverbridge.main.domain.camera.dto.CameraResponse;
import kr.silverbridge.main.domain.camera.dto.GuardianCameraView;
import kr.silverbridge.main.domain.camera.dto.GuardianLiveCameraView;
import kr.silverbridge.main.domain.camera.dto.StreamTicketResponse;
import kr.silverbridge.main.domain.camera.dto.WardLiveCameraView;
import kr.silverbridge.main.domain.camera.service.CameraStreamTicketService.StreamTicket;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 보호자 실시간 카메라 보기 - AI 서버 영상·상태를 백엔드가 인가 후 중계한다(2026-10-03).
 *
 * <p>예전에는 브라우저가 FE 무인증 프록시·AI WebSocket에 직접 붙어 누구나 모든 집 영상을 볼 수 있었고 AI 키가
 * 브라우저에 노출됐다. 이제 브라우저는 백엔드만 부르고, AI 키는 서버 안에서만 쓴다.</p>
 *
 * <p><b>인가</b>: 모든 경로가 {@link CameraService#getViewableCamera} 한 곳을 지난다 - ACTIVE 연결된 피보호자의
 * 활성 등록 카메라만. 영상은 보는 동안에도 {@code revalidateInterval}마다 다시 확인해 연결 해제·정지·카메라 삭제·
 * 비밀번호 변경(토큰 무효화)을 열린 영상에 반영한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraStreamService {

    private static final String DEFAULT_MJPEG_TYPE = "multipart/x-mixed-replace; boundary=frame";
    private static final int BUFFER_BYTES = 16 * 1024;

    private final CameraService cameraService;
    private final AiStreamClient aiStreamClient;
    private final CameraStreamTicketService ticketService;
    private final CameraStreamSlots slots;
    private final LiveAnalysisSnapshotPort analysisSnapshots;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final CameraStreamProperties properties;
    private final RateLimitService rateLimitService;

    /**
     * 연결된 피보호자의 등록 카메라 + 송출 상태. AI가 응답하지 않으면 카메라 목록은 그대로 주고 상태만 {@code null}(확인 불가)이다.
     */
    public List<GuardianLiveCameraView> getLiveCameras(String guardianId) {
        rateLimit(CameraStreamRateLimit.LIVE, guardianId);
        List<GuardianCameraView> cameras = cameraService.getConnectedWardCameras(guardianId);
        if (cameras.isEmpty()) {
            return List.of();
        }
        Map<String, AiLiveStream> live;
        try {
            live = aiStreamClient.fetchLiveStreams();
        } catch (AiStreamUnavailableException e) {
            live = null;   // 모르는 값을 offline으로 채우지 않는다
        }
        Map<String, AiLiveStream> liveStreams = live;
        return cameras.stream()
                .map(camera -> toLiveView(camera, liveStreams))
                .toList();
    }

    /**
     * 피보호자 "내 카메라" - 본인 등록 카메라 전부 + 송출 상태(보호자 실시간 목록과 같은 기준).
     * AI가 응답하지 않으면 목록은 그대로 주고 상태만 {@code null}(확인 불가)이다. AI 목록은 카메라 수와 관계없이 1번만 부른다.
     */
    public List<WardLiveCameraView> getWardLiveCameras(String wardId) {
        rateLimit(CameraStreamRateLimit.WARD_LIVE, wardId);
        List<CameraResponse> cameras = cameraService.getMyCameras(wardId);
        if (cameras.isEmpty()) {
            return List.of();
        }
        Map<String, AiLiveStream> live;
        try {
            live = aiStreamClient.fetchLiveStreams();
        } catch (AiStreamUnavailableException e) {
            live = null;   // 모르는 값을 offline으로 채우지 않는다
        }
        Map<String, AiLiveStream> liveStreams = live;
        return cameras.stream()
                .map(camera -> {
                    if (liveStreams == null) {
                        return WardLiveCameraView.of(camera, null, null);
                    }
                    AiLiveStream stream = liveStreams.get(camera.sessionId());
                    return stream == null
                            ? WardLiveCameraView.of(camera, CameraLiveStatus.OFFLINE, null)
                            : WardLiveCameraView.of(camera, CameraLiveStatus.fromAi(stream.status()), stream.lastFrameAt());
                })
                .toList();
    }

    private static GuardianLiveCameraView toLiveView(GuardianCameraView camera, Map<String, AiLiveStream> live) {
        if (live == null) {
            return new GuardianLiveCameraView(camera.sessionId(), camera.wardId(), camera.wardName(), camera.label(),
                    null, null);
        }
        AiLiveStream stream = live.get(camera.sessionId());
        if (stream == null) {
            return new GuardianLiveCameraView(camera.sessionId(), camera.wardId(), camera.wardName(), camera.label(),
                    CameraLiveStatus.OFFLINE, null);
        }
        return new GuardianLiveCameraView(camera.sessionId(), camera.wardId(), camera.wardName(), camera.label(),
                CameraLiveStatus.fromAi(stream.status()), stream.lastFrameAt());
    }

    /** 선택한 카메라의 송출 상태 + 최근 AI 분석. */
    public CameraLiveStatusResponse getStatus(String guardianId, String sessionId) {
        rateLimit(CameraStreamRateLimit.STATUS, guardianId);
        cameraService.getViewableCamera(guardianId, sessionId);
        Optional<AiStreamClient.AiStreamStatus> status;
        try {
            status = aiStreamClient.fetchStatus(sessionId);
        } catch (AiStreamUnavailableException e) {
            throw new CustomException(ErrorCode.CAMERA_STREAM_UNAVAILABLE);
        }
        if (status.isEmpty()) {
            return CameraLiveStatusResponse.offline();
        }
        AiStreamClient.AiStreamStatus s = status.get();
        return new CameraLiveStatusResponse(CameraLiveStatus.fromAi(s.status()), s.lastFrameAt(), s.fps(),
                s.isAnalyzing(), analysisSnapshots.findLatest(sessionId).orElse(null));
    }

    /** 최신 정지 화면(JPEG). */
    public byte[] getLatestFrame(String guardianId, String sessionId) {
        rateLimit(CameraStreamRateLimit.FRAME, guardianId);
        cameraService.getViewableCamera(guardianId, sessionId);
        try {
            return aiStreamClient.fetchLatestFrame(sessionId)
                    .orElseThrow(() -> new CustomException(ErrorCode.CAMERA_NOT_STREAMING));
        } catch (AiStreamUnavailableException e) {
            throw new CustomException(ErrorCode.CAMERA_STREAM_UNAVAILABLE);
        }
    }

    /** 영상 1회용 티켓. 인가를 통과한 카메라에만 발급한다. */
    public StreamTicketResponse issueTicket(String guardianId, String sessionId) {
        rateLimit(CameraStreamRateLimit.TICKET, guardianId);
        cameraService.getViewableCamera(guardianId, sessionId);
        String ticket = ticketService.issue(guardianId, sessionId);
        return new StreamTicketResponse(ticket, properties.getTicketTtl().toSeconds());
    }

    /**
     * MJPEG 중계. 티켓을 소비하고 인가·계정 상태를 다시 확인한 뒤 AI 스트림을 응답으로 복사한다.
     *
     * <p>응답을 쓰기 시작하기 전의 실패는 예외(JSON 오류 응답)로, 쓰기 시작한 뒤의 종료(시청자 이탈·시간 초과·권한 변경·
     * 서버 종료)는 조용히 끝낸다. 어느 경우든 AI 연결과 자리는 반드시 반납된다(try-with-resources).</p>
     */
    public void relay(String sessionId, String ticket, HttpServletResponse response) throws IOException {
        StreamTicket streamTicket = ticketService.consume(ticket, sessionId);
        String userId = streamTicket.userId();
        requireActiveAccount(userId);
        if (isRevokedSince(userId, streamTicket.issuedAtMillis())) {
            throw new CustomException(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
        }
        cameraService.getViewableCamera(userId, sessionId);

        try (CameraStreamSlots.Slot slot = slots.acquire(userId)) {
            AiMjpegStream aiStream;
            try {
                aiStream = aiStreamClient.openMjpeg(sessionId);
            } catch (AiStreamUnavailableException e) {
                throw new CustomException(ErrorCode.CAMERA_STREAM_UNAVAILABLE);
            }
            slot.attach(aiStream);   // 자리를 닫으면(정상 종료·예외·서버 종료) AI 소켓도 끊긴다

            String contentType = aiStream.contentType();
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(contentType != null && contentType.startsWith("multipart/")
                    ? contentType : DEFAULT_MJPEG_TYPE);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");   // nginx가 영상을 모아 보내지 않게
            log.info("[CAMERA-STREAM] 영상 중계 시작: userId={}, sessionId={}", userId, sessionId);

            StopReason reason = pump(aiStream.body(), response.getOutputStream(), userId, sessionId,
                    streamTicket.issuedAtMillis());
            log.info("[CAMERA-STREAM] 영상 중계 종료: userId={}, sessionId={}, reason={}", userId, sessionId, reason);
        }
    }

    // 인가·AI 호출보다 먼저 - 가장 싼 검사로 반복 호출을 막는다(점검 L-4). 식별자는 사용자 ID다(공용 NAT의 시니어를 IP로 묶지 않는다).
    private void rateLimit(CameraStreamRateLimit limit, String userId) {
        rateLimitService.check(limit.endpoint, userId, limit.perMinute, limit.perHour);
    }

    enum StopReason { AI_ENDED, AI_IDLE, AI_ERROR, CLIENT_GONE, MAX_DURATION, REVOKED, SHUTDOWN }

    StopReason pump(InputStream in, OutputStream out, String userId, String sessionId, long issuedAtMillis) {
        byte[] buffer = new byte[BUFFER_BYTES];
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + properties.getMaxStreamDuration().toMillis();
        long revalidateMillis = properties.getRevalidateInterval().toMillis();
        long nextCheck = startedAt + revalidateMillis;

        while (true) {
            int read;
            try {
                read = in.read(buffer);
            } catch (SocketTimeoutException e) {
                return StopReason.AI_IDLE;
            } catch (IOException e) {
                // 종료 처리가 AI 연결을 닫은 경우도 여기로 온다
                return slots.isAccepting() ? StopReason.AI_ERROR : StopReason.SHUTDOWN;
            }
            if (read < 0) {
                return StopReason.AI_ENDED;
            }
            try {
                out.write(buffer, 0, read);
                out.flush();
            } catch (IOException e) {
                return StopReason.CLIENT_GONE;   // 브라우저가 닫음 - 정상 종료
            }

            if (!slots.isAccepting()) {
                return StopReason.SHUTDOWN;
            }
            long now = System.currentTimeMillis();
            if (now >= deadline) {
                return StopReason.MAX_DURATION;
            }
            if (now >= nextCheck) {
                if (!isStillViewable(userId, sessionId, issuedAtMillis)) {
                    return StopReason.REVOKED;
                }
                nextCheck = now + revalidateMillis;
            }
        }
    }

    /**
     * 시청 중 재확인 - 계정 이용 중 + ACTIVE 연결 + 활성 카메라 + 티켓 발급 뒤 토큰 무효화 없음.
     *
     * <p>DB 조회가 실패하면 끊는다(확인할 수 없으면 계속 보여주지 않는다). 무효화 키(Redis) 조회만 실패하면
     * 계정·연결 확인은 통과했으므로 이어 간다 - Redis 순단으로 전 영상이 끊기지 않게.</p>
     */
    boolean isStillViewable(String userId, String sessionId, long issuedAtMillis) {
        try {
            Optional<Status> status = userRepository.findStatusById(userId);
            if (status.isEmpty() || status.get() != Status.ACTIVE) {
                return false;
            }
            if (!cameraService.isViewable(userId, sessionId)) {
                return false;
            }
        } catch (RuntimeException e) {
            log.warn("[CAMERA-STREAM] 시청 재확인 실패 - 영상 종료: userId={}, error={}",
                    userId, e.getClass().getSimpleName());
            return false;
        }
        return !isRevokedSince(userId, issuedAtMillis);
    }

    private void requireActiveAccount(String userId) {
        Optional<Status> status = userRepository.findStatusById(userId);
        if (status.isEmpty()) {
            throw new CustomException(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
        }
        if (status.get() != Status.ACTIVE) {
            throw new CustomException(ErrorCode.INACTIVE_USER);
        }
    }

    // 티켓 발급 뒤 비밀번호 변경·정지·역할 변경으로 토큰이 무효화됐는가(HTTP 필터와 같은 비교 - TokenInvalidation)
    private boolean isRevokedSince(String userId, long issuedAtMillis) {
        try {
            String stored = redisTemplate.opsForValue().get(RedisKeys.PASSWORD_INVALIDATE + userId);
            return stored != null && TokenInvalidation.isRevoked(issuedAtMillis, TokenInvalidation.parseEpochSecond(stored));
        } catch (RuntimeException e) {
            log.warn("[CAMERA-STREAM] 토큰 무효화 조회 실패 - 계정·연결 확인만으로 진행: userId={}, error={}",
                    userId, e.getClass().getSimpleName());
            return false;
        }
    }
}
