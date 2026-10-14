package kr.silverbridge.main.domain.camera.upload;

import kr.silverbridge.main.domain.camera.config.CameraUploadProperties;
import kr.silverbridge.main.domain.camera.service.CameraUploadService.UploadGrant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 연결 하나(브라우저 <-> AI)의 중계 상태와 한도. <b>바이트를 해석하지 않는다</b> - 텍스트·바이너리 구분과 크기·속도만 본다.
 *
 * <p>종료는 한 번만 일어나고({@link #close}) 브라우저·AI 양쪽을 함께 닫는다. 로그에는 sessionId·집계 수치·종료 코드만
 * 남긴다(티켓·프레임·AI 키 원문 금지).</p>
 */
@Slf4j
final class CameraUploadRelay {

    /** 텍스트 메시지(config) 최대 크기. 바이너리 한도와 별개다. */
    static final int TEXT_LIMIT_BYTES = 4096;
    private static final long RATE_WINDOW_NANOS = 1_000_000_000L;
    private static final String KEYFRAME_REQUEST = "{\"type\":\"keyframe_request\"}";

    private final UploadGrant grant;
    private final CameraUploadProperties properties;
    private final int maxMessagesPerSecond;

    private volatile WebSocketSession browser;
    private volatile WebSocketSession ai;
    private final AtomicBoolean closed = new AtomicBoolean();
    // 브라우저가 붙기 전에 AI가 먼저 닫은 경우(플래그 꺼짐 등) 그 사유를 브라우저에 그대로 전하려고 남긴다
    private volatile CloseStatus finalStatus;
    private volatile long lastReceivedNanos = System.nanoTime();
    private volatile long nextRecheckNanos;

    // 속도 창은 브라우저 수신 스레드 하나에서만 만진다(한 세션의 메시지는 순서대로 온다)
    private long windowStartNanos = System.nanoTime();
    private int windowCount;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong forwarded = new AtomicLong();
    private final AtomicLong keyframeRequests = new AtomicLong();
    private final long startedNanos = System.nanoTime();

    CameraUploadRelay(UploadGrant grant, CameraUploadProperties properties, int recommendedFps) {
        this.grant = grant;
        this.properties = properties;
        this.maxMessagesPerSecond = Math.max(5, (int) Math.ceil(recommendedFps * properties.getMaxFpsFactor()));
        this.nextRecheckNanos = System.nanoTime() + properties.getRevalidateInterval().toNanos();
    }

    UploadGrant grant() {
        return grant;
    }

    String sessionId() {
        return grant.sessionId();
    }

    void attachAi(WebSocketSession raw) {
        // 전송 대기 바이트·시간 상한 - 넘으면 연결을 끊어 지연이 쌓이지 않게 한다(FE는 JPEG로 폴백)
        this.ai = new ConcurrentWebSocketSessionDecorator(raw,
                (int) properties.getSendTimeLimit().toMillis(), properties.getSendBufferBytes());
    }

    /** @return 이미 닫힌 연결이면 false(호출자가 브라우저 세션을 닫는다) */
    boolean attachBrowser(WebSocketSession raw) {
        this.browser = new ConcurrentWebSocketSessionDecorator(raw,
                (int) properties.getSendTimeLimit().toMillis(), 64 * 1024);
        return !closed.get();
    }

    boolean isClosed() {
        return closed.get();
    }

    /** 닫혔다면 그 사유(닫히지 않았으면 null). */
    CloseStatus finalStatus() {
        return finalStatus;
    }

    // ---- 브라우저 -> AI ----

    void fromBrowserText(String payload) {
        lastReceivedNanos = System.nanoTime();
        received.incrementAndGet();
        if (payload.length() > TEXT_LIMIT_BYTES) {
            close(1009, "MESSAGE_TOO_LARGE");
            return;
        }
        send(ai, new TextMessage(payload));
    }

    void fromBrowserBinary(ByteBuffer payload) {
        long now = System.nanoTime();
        lastReceivedNanos = now;
        received.incrementAndGet();
        if (payload.remaining() > properties.getMaxMessageBytes()) {
            close(1009, "MESSAGE_TOO_LARGE");
            return;
        }
        if (now - windowStartNanos >= RATE_WINDOW_NANOS) {
            windowStartNanos = now;
            windowCount = 0;
        }
        if (++windowCount > maxMessagesPerSecond) {
            close(1008, "RATE_LIMITED");
            return;
        }
        send(ai, new BinaryMessage(payload));
    }

    private void send(WebSocketSession target, WebSocketMessage<?> message) {
        if (closed.get() || target == null) {
            return;
        }
        try {
            target.sendMessage(message);
            forwarded.incrementAndGet();
        } catch (SessionLimitExceededException e) {
            close(1011, "UPSTREAM_BACKPRESSURE");
        } catch (Exception e) {
            // 예외 원문에는 접속 정보가 섞일 수 있어 클래스명만 남긴다
            log.warn("[CAMERA-UPLOAD] 전송 실패 - 종료: sessionId={}, error={}", grant.sessionId(), e.getClass().getSimpleName());
            close(1011, "UPSTREAM_ERROR");
        }
    }

    // ---- AI -> 브라우저 ----

    /** AI가 보낼 수 있는 텍스트는 keyframe_request 하나다. 그 밖은 버린다. */
    void fromAiText(String payload) {
        String compact = payload.replaceAll("\\s+", "");
        if (!KEYFRAME_REQUEST.equals(compact)) {
            return;
        }
        keyframeRequests.incrementAndGet();
        send(browser, new TextMessage(KEYFRAME_REQUEST));
    }

    /** AI 쪽이 먼저 닫혔다 - 그 코드를 브라우저에 그대로 전한다(허용 목록 밖은 1011). */
    void onAiClosed(CloseStatus status) {
        int code = status.getCode();
        boolean known = code == 1000 || code == 1003 || code == 1008 || code == 1009 || code == 1011 || code == 1013;
        String reason = sanitizeReason(status.getReason());
        close(known ? code : 1011, reason.isEmpty() ? "UPSTREAM_CLOSED" : reason);
    }

    // AI가 보내는 사유는 고정 코드(AUTH_INVALID_KEY 등)뿐이지만 형식이 어긋나면 일반 사유로 바꾼다
    private static String sanitizeReason(String reason) {
        return reason != null && reason.matches("[A-Z_]{1,40}") ? reason : "";
    }

    // ---- 점검·종료 ----

    boolean isIdle(long nowNanos) {
        return nowNanos - lastReceivedNanos > properties.getIdleTimeout().toNanos();
    }

    boolean isRecheckDue(long nowNanos) {
        return nowNanos >= nextRecheckNanos;
    }

    void scheduleNextRecheck(long nowNanos) {
        this.nextRecheckNanos = nowNanos + properties.getRevalidateInterval().toNanos();
    }

    /** 양쪽을 한 번만 닫는다. 이미 닫혔으면 아무것도 하지 않는다. */
    void close(int code, String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        finalStatus = new CloseStatus(code, reason);
        long seconds = (System.nanoTime() - startedNanos) / 1_000_000_000L;
        log.info("[CAMERA-UPLOAD] 종료: sessionId={}, code={}, reason={}, durationSec={}, received={}, forwarded={}, keyframeRequests={}",
                grant.sessionId(), code, reason, seconds, received.get(), forwarded.get(), keyframeRequests.get());
        closeQuietly(browser, new CloseStatus(code, reason));
        closeQuietly(ai, CloseStatus.NORMAL);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        if (session == null) {
            return;
        }
        try {
            session.close(status);
        } catch (Exception ignored) {
            // 이미 끊긴 연결 - 정리 중이므로 무시한다
        }
    }
}
