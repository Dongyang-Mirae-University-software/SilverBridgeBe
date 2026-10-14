package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.config.CameraUploadProperties;
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
 * 송출(업로드) 1회용 티켓 - 피보호자 기기가 {@code /ws/camera-upload} 핸드셰이크에 쓴다(2026-10-14).
 *
 * <p>시청 티켓({@link CameraStreamTicketService})과 구조는 같지만 <b>접두사({@code ut_})와 Redis 키가 달라
 * 서로 소비할 수 없다</b>(시청 티켓으로 송출을 열거나 그 반대가 되면 권한 경계가 무너진다). 코드를 공유하려고
 * 한 서비스에 purpose 파라미터를 끼우지 말 것 - 형식이 달라 Redis를 조회하기도 전에 거절되는 것이 이 분리의 핵심이다.</p>
 *
 * <p>소비는 {@code GETDEL} 한 번이다. sessionId는 발급 때 티켓에 묶이고 핸드셰이크는 그 값만 쓴다(URL·메시지로 받지 않는다).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraUploadTicketService {

    private static final String PREFIX = "ut_";
    private static final int TOKEN_BYTES = 32;
    // ut_ + base64url(32바이트, 패딩 없음) = 43자. 형식이 다르면 Redis를 조회하지 않는다.
    private static final Pattern TICKET_FORMAT = Pattern.compile("^ut_[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final CameraUploadProperties properties;

    /** 소비된 티켓의 주인(피보호자)·세션·발급 시각(발급 뒤 토큰 무효화 비교용). */
    public record UploadTicket(String wardId, String sessionId, long issuedAtMillis) {}

    public String issue(String wardId, String sessionId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String ticket = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String value = wardId + ":" + sessionId + ":" + System.currentTimeMillis();
        try {
            redisTemplate.opsForValue().set(RedisKeys.CAMERA_UPLOAD_TICKET + ticket, value, properties.getTicketTtl());
        } catch (RuntimeException e) {
            throw storeUnavailable("issue", e);
        }
        return ticket;
    }

    /** 티켓을 소비한다. 없음·만료·이미 사용·형식 오류는 401({@code CAMERA_UPLOAD_TICKET_INVALID}). 저장소 오류는 503. */
    public UploadTicket consume(String ticket) {
        if (ticket == null || !TICKET_FORMAT.matcher(ticket).matches()) {
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
        }
        String value;
        try {
            value = redisTemplate.opsForValue().getAndDelete(RedisKeys.CAMERA_UPLOAD_TICKET + ticket);
        } catch (RuntimeException e) {
            throw storeUnavailable("consume", e);
        }
        if (value == null) {
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
        }
        int first = value.indexOf(':');
        int last = value.lastIndexOf(':');
        if (first <= 0 || last <= first + 1) {
            log.warn("[CAMERA-UPLOAD] 티켓 저장값 형식 오류 - 거부");
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
        }
        try {
            return new UploadTicket(value.substring(0, first), value.substring(first + 1, last),
                    Long.parseLong(value.substring(last + 1)));
        } catch (NumberFormatException e) {
            log.warn("[CAMERA-UPLOAD] 티켓 발급 시각 형식 오류 - 거부");
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
        }
    }

    // 예외 원문에는 접속 정보가 섞일 수 있어 클래스명만 남긴다. 티켓 원문도 남기지 않는다.
    private CustomException storeUnavailable(String op, RuntimeException e) {
        log.warn("[CAMERA-UPLOAD] 티켓 저장소 오류 - 503: op={}, error={}", op, e.getClass().getSimpleName());
        return new CustomException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
