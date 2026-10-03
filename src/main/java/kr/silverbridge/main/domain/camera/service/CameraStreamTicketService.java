package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 영상(MJPEG) 1회용 티켓.
 *
 * <p>{@code <img>}는 {@code Authorization} 헤더를 보낼 수 없어, 인증된 API로 티켓을 받아 영상 주소 쿼리에 붙인다.
 * access token을 쿼리에 싣지 않는 이유: 프록시·접속 로그에 남으면 30분짜리 전권 토큰이 새어 나간다. 티켓은
 * <b>한 세션·한 사용자·60초·1회</b>로 묶여 새어도 피해가 그 영상 1건으로 한정된다.</p>
 *
 * <p>소비는 {@code GETDEL} 한 번이다 - 조회와 삭제를 나누면 같은 티켓으로 동시에 두 영상이 열릴 수 있다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraStreamTicketService {

    private static final String PREFIX = "st_";
    private static final int TOKEN_BYTES = 32;
    // st_ + base64url(32바이트, 패딩 없음) = 43자. 형식이 다르면 Redis를 조회하지 않는다.
    private static final Pattern TICKET_FORMAT = Pattern.compile("^st_[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final CameraStreamProperties properties;

    /** 소비된 티켓의 주인과 발급 시각(발급 이후 토큰이 무효화됐는지 시청 중에 비교한다). */
    public record StreamTicket(String userId, long issuedAtMillis) {}

    public String issue(String userId, String sessionId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String ticket = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String value = userId + ":" + sessionId + ":" + System.currentTimeMillis();
        try {
            redisTemplate.opsForValue().set(RedisKeys.CAMERA_STREAM_TICKET + ticket, value, properties.getTicketTtl());
        } catch (RuntimeException e) {
            throw storeUnavailable("issue", e);
        }
        return ticket;
    }

    /**
     * 티켓을 소비한다. 없음·만료·이미 사용·다른 세션용이면 401({@code CAMERA_STREAM_TICKET_INVALID}).
     */
    public StreamTicket consume(String ticket, String sessionId) {
        if (ticket == null || !TICKET_FORMAT.matcher(ticket).matches()) {
            throw new CustomException(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
        }
        String value;
        try {
            value = redisTemplate.opsForValue().getAndDelete(RedisKeys.CAMERA_STREAM_TICKET + ticket);
        } catch (RuntimeException e) {
            throw storeUnavailable("consume", e);
        }
        if (value == null) {
            throw new CustomException(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
        }

        int first = value.indexOf(':');
        int last = value.lastIndexOf(':');
        if (first <= 0 || last <= first) {
            log.warn("[CAMERA-STREAM] 티켓 저장값 형식 오류 - 거부");
            throw new CustomException(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
        }
        String userId = value.substring(0, first);
        String ticketSessionId = value.substring(first + 1, last);
        if (!ticketSessionId.equals(sessionId)) {
            // 다른 카메라용 티켓으로 이 카메라를 열려 함 - 티켓은 이미 소비됐다(재사용 불가)
            log.warn("[IDOR-ATTEMPT] 다른 카메라용 스트림 티켓 사용: userId={}, sessionId={}", userId, sessionId);
            throw new CustomException(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
        }
        try {
            return new StreamTicket(userId, Long.parseLong(value.substring(last + 1)));
        } catch (NumberFormatException e) {
            log.warn("[CAMERA-STREAM] 티켓 발급 시각 형식 오류 - 거부");
            throw new CustomException(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
        }
    }

    // 예외 원문에는 접속 정보가 섞일 수 있어 클래스명만 남긴다(JwtAuthenticationFilter와 같은 기준)
    private CustomException storeUnavailable(String op, RuntimeException e) {
        log.warn("[CAMERA-STREAM] 티켓 저장소 오류 - 503: op={}, error={}", op, e.getClass().getSimpleName());
        return new CustomException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
