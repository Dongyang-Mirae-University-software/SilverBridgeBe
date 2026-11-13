package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.client.AiCompanionClient;
import kr.silverbridge.main.domain.companion.client.CompanionAudio;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * 말벗 목소리 중계(2026-11-13). 피보호자가 자기 대화에서 말벗이 한 말의 소리를 받는다 - AI
 * {@code /companion/sessions/{sessionId}/utterances/{index}/audio}를 그대로 넘긴다.
 *
 * <p><b>소리는 저장하지 않는다</b>(음성 미저장 불변 규칙): 디스크·DB·Redis 어디에도 두지 않고 응답으로만 넘긴다.
 * 본인 대화만(남의 대화 403 {@code COMPANION_SESSION_NOT_OWNED} + {@code [IDOR-ATTEMPT]}, 없으면 404), 말벗 스위치는
 * 다른 말벗 API와 같은 기준이다. 대화가 끝났어도 받는다 - 마지막 대답의 마무리 문장도 읽어 줘야 한다.</p>
 *
 * <p>요청 하나가 스레드를 최대 {@code audio-call-timeout}(8초) 붙들므로 대답 턴과 따로 동시 상한을 둔다. 넘치면 503
 * {@code COMPANION_AUDIO_NOT_READY} - 기기는 404·503 모두 직접 읽어 주는 것으로 대신한다.</p>
 */
@Slf4j
@Service
public class CompanionAudioService {

    /** 소리 번호는 0 이상 정수(앞 0 없음, 최대 3자리) - 범위는 설정 상한으로 다시 본다. */
    private static final Pattern INDEX = Pattern.compile("^(0|[1-9][0-9]{0,2})$");

    private final AiCompanionClient aiClient;
    private final CompanionProperties properties;
    private final CompanionService companionService;
    private final Semaphore inFlight;

    public CompanionAudioService(AiCompanionClient aiClient, CompanionProperties properties,
                                 CompanionService companionService) {
        this.aiClient = aiClient;
        this.properties = properties;
        this.companionService = companionService;
        this.inFlight = new Semaphore(Math.max(1, properties.getAudioMaxConcurrent()));
    }

    public CompanionAudio utteranceAudio(String wardId, long sessionId, String rawIndex) {
        companionService.requireUsable();
        int index = parseIndex(rawIndex);
        companionService.requireOwned(wardId, sessionId);
        if (!inFlight.tryAcquire()) {
            log.warn("[COMPANION-LIMIT] 목소리 중계 동시 상한 - 요청 거부: sessionId={}", sessionId);
            throw new CustomException(ErrorCode.COMPANION_AUDIO_NOT_READY);
        }
        try {
            return aiClient.fetchUtteranceAudio(sessionId, index);
        } finally {
            inFlight.release();
        }
    }

    /** 형식·범위가 틀리면 400. 로그에는 원문 대신 길이만 남긴다. */
    private int parseIndex(String raw) {
        if (raw == null || !INDEX.matcher(raw).matches()) {
            log.info("[COMPANION] 목소리 번호 형식 오류: length={}", raw == null ? 0 : raw.length());
            throw new CustomException(ErrorCode.COMPANION_AUDIO_INDEX_INVALID);
        }
        int index = Integer.parseInt(raw);
        if (index > properties.getAudioMaxIndex()) {
            log.info("[COMPANION] 목소리 번호 범위 밖: max={}", properties.getAudioMaxIndex());
            throw new CustomException(ErrorCode.COMPANION_AUDIO_INDEX_INVALID);
        }
        return index;
    }

    int availablePermits() {
        return inFlight.availablePermits();
    }
}
