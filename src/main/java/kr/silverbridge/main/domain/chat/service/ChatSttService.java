package kr.silverbridge.main.domain.chat.service;

import kr.silverbridge.main.domain.chat.client.AiSttClient;
import kr.silverbridge.main.domain.chat.config.ChatSttProperties;
import kr.silverbridge.main.domain.chat.dto.ChatSttResponse;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 챗봇 마이크 음성 인식 중계(2026-11-04). 피보호자·보호자 챗봇 화면의 마이크 녹음을 AI에 보내 글자만 돌려준다.
 *
 * <p>순서: 킬 스위치 → 속도 제한 → 파일 검사(비었는지·10MB) → 동시 상한 → AI 호출. 사용자는 토큰에서만 정한다.
 * <b>음성과 글자는 저장하지 않고 로그에도 남기지 않는다</b> - 로그는 크기·처리 시간·결과뿐이다. 형식은 AI가 파일 머리로
 * 판별한다. 동시 상한(서버 전체·1인)은 이 인스턴스 기준이며, 넘치면 AI를 부르지 않고 바로 503 {@code CHAT_STT_BUSY}다
 * (AI도 2건만 처리하고 5초 기다린 뒤 같은 코드로 거절한다).</p>
 */
@Slf4j
@Service
public class ChatSttService {

    static final String RATE_LIMIT_ENDPOINT = "chat-stt";

    private final AiSttClient client;
    private final ChatSttProperties properties;
    private final RateLimitService rateLimitService;
    private final Semaphore global;
    private final Map<String, Integer> perUser = new ConcurrentHashMap<>();

    public ChatSttService(AiSttClient client, ChatSttProperties properties, RateLimitService rateLimitService) {
        this.client = client;
        this.properties = properties;
        this.rateLimitService = rateLimitService;
        this.global = new Semaphore(properties.getMaxConcurrent());
    }

    public ChatSttResponse transcribe(String userId, MultipartFile audio) {
        if (!properties.isUsable()) {
            throw new CustomException(ErrorCode.CHAT_STT_DISABLED);
        }
        rateLimitService.check(RATE_LIMIT_ENDPOINT, userId, properties.getPerMinute(), properties.getPerHour());
        byte[] bytes = read(audio);

        if (!reserveUser(userId)) {
            log.info("[CHAT-STT] 사용자 동시 요청 상한: userId={}", userId);
            throw new CustomException(ErrorCode.CHAT_STT_BUSY);
        }
        try {
            if (!global.tryAcquire()) {
                log.warn("[CHAT-STT] 서버 전체 동시 요청 상한 - 요청 거부: userId={}", userId);
                throw new CustomException(ErrorCode.CHAT_STT_BUSY);
            }
            long started = System.nanoTime();
            try {
                String text = client.transcribe(bytes);
                log.info("[CHAT-STT] 완료: userId={}, bytes={}, elapsedMs={}, empty={}",
                        userId, bytes.length, (System.nanoTime() - started) / 1_000_000, text.isBlank());
                return new ChatSttResponse(text);
            } catch (CustomException e) {
                log.info("[CHAT-STT] 실패: userId={}, bytes={}, elapsedMs={}, code={}",
                        userId, bytes.length, (System.nanoTime() - started) / 1_000_000, e.getErrorCode().name());
                throw e;
            } finally {
                global.release();
            }
        } finally {
            releaseUser(userId);
        }
    }

    private byte[] read(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new CustomException(ErrorCode.CHAT_STT_AUDIO_INVALID);
        }
        if (audio.getSize() > properties.getMaxAudioBytes()) {
            throw new CustomException(ErrorCode.CHAT_STT_AUDIO_TOO_LARGE);
        }
        try {
            return audio.getBytes();
        } catch (IOException e) {
            log.warn("[CHAT-STT] 업로드 읽기 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.CHAT_STT_AUDIO_INVALID);
        }
    }

    private boolean reserveUser(String userId) {
        boolean[] reserved = {false};
        perUser.compute(userId, (key, count) -> {
            int current = count == null ? 0 : count;
            if (current >= properties.getMaxPerUser()) {
                return count;
            }
            reserved[0] = true;
            return current + 1;
        });
        return reserved[0];
    }

    private void releaseUser(String userId) {
        perUser.computeIfPresent(userId, (key, count) -> count <= 1 ? null : count - 1);
    }

    int activeUsers() {
        return perUser.size();
    }
}
