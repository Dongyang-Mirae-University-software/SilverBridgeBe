package kr.silverbridge.main.domain.companion.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.companion.client.AiCompanionClient;
import kr.silverbridge.main.domain.companion.client.CompanionAudio;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.UtteranceAudio;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionQuestionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 말벗 목소리 중계(2026-11-13) - 본인 대화만·번호 검사·스위치·동시 상한·AI 소리 번호 옮기기. */
class CompanionAudioServiceTest {

    private static final String WARD = "W00001";
    private static final String OTHER = "W00002";
    private static final ObjectMapper JSON = new ObjectMapper();

    private AiCompanionClient aiClient;
    private CompanionProperties properties;
    private CompanionSessionRepository sessionRepository;
    private CompanionService companionService;
    private CompanionAudioService service;
    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;
    private Logger audioLogger;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiCompanionClient.class);
        properties = new CompanionProperties();
        properties.setEnabled(true);
        properties.setAiBaseUrl("http://ai.test");
        properties.setApiKey("key");
        sessionRepository = mock(CompanionSessionRepository.class);
        companionService = new CompanionService(aiClient, properties, mock(CompanionQuestionRepository.class),
                sessionRepository, mock(CompanionSessionWriter.class), new CompanionTurnSlots(properties),
                mock(RateLimitService.class), mock(CompanionPromptScheduler.class));
        service = new CompanionAudioService(aiClient, properties, companionService);

        appender = new ListAppender<>();
        appender.start();
        serviceLogger = (Logger) LoggerFactory.getLogger(CompanionService.class);
        audioLogger = (Logger) LoggerFactory.getLogger(CompanionAudioService.class);
        serviceLogger.addAppender(appender);
        audioLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        audioLogger.detachAppender(appender);
    }

    private CompanionSession session(long id, String wardId) {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-13T09:10:00+09:00");
        CompanionSession session = CompanionSession.claim(wardId, LocalDate.of(2026, 11, 13), CompanionSlot.S09, 1L, now);
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }

    @Test
    @DisplayName("본인 대화: AI 소리를 그대로 돌려주고 BE 대화 번호·소리 번호로 부른다")
    void ownSessionRelays() {
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(session(10L, WARD)));
        CompanionAudio audio = new CompanionAudio(new byte[]{1, 2, 3}, "audio/mpeg");
        when(aiClient.fetchUtteranceAudio(10L, 2)).thenReturn(audio);

        assertThat(service.utteranceAudio(WARD, 10L, "2")).isSameAs(audio);
        assertThat(service.availablePermits()).isEqualTo(properties.getAudioMaxConcurrent());
    }

    @Test
    @DisplayName("끝난 대화도 받는다 - 마지막 대답의 마무리 문장을 읽어 줘야 한다")
    void finishedSessionAllowed() {
        CompanionSession finished = session(11L, WARD);
        ReflectionTestUtils.setField(finished, "status",
                kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus.FINISHED);
        when(sessionRepository.findById(11L)).thenReturn(Optional.of(finished));
        when(aiClient.fetchUtteranceAudio(11L, 0)).thenReturn(new CompanionAudio(new byte[]{1}, "audio/ogg"));

        assertThat(service.utteranceAudio(WARD, 11L, "0").contentType()).isEqualTo("audio/ogg");
    }

    @Test
    @DisplayName("남의 대화: 403 COMPANION_SESSION_NOT_OWNED + [IDOR-ATTEMPT] WARN, AI는 부르지 않는다")
    void otherWardForbidden() {
        when(sessionRepository.findById(12L)).thenReturn(Optional.of(session(12L, OTHER)));

        assertThatThrownBy(() -> service.utteranceAudio(WARD, 12L, "1"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_SESSION_NOT_OWNED);
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().startsWith("[IDOR-ATTEMPT]"));
        verify(aiClient, never()).fetchUtteranceAudio(anyLong(), anyInt());
    }

    @Test
    @DisplayName("없는 대화: 404 COMPANION_SESSION_NOT_FOUND, AI는 부르지 않는다")
    void missingSession() {
        when(sessionRepository.findById(13L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.utteranceAudio(WARD, 13L, "1"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_SESSION_NOT_FOUND);
        verify(aiClient, never()).fetchUtteranceAudio(anyLong(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "51", "01", "1.5", "abc", "", "9999", "1\nINJECT", "2147483648"})
    @DisplayName("번호 형식·범위 오류: 400 COMPANION_AUDIO_INDEX_INVALID, DB·AI 미조회, 로그에 원문 없음")
    void invalidIndex(String raw) {
        assertThatThrownBy(() -> service.utteranceAudio(WARD, 10L, raw))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_AUDIO_INDEX_INVALID);
        verify(sessionRepository, never()).findById(anyLong());
        verify(aiClient, never()).fetchUtteranceAudio(anyLong(), anyInt());
        if (!raw.isEmpty()) {
            assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(raw));
        }
    }

    @Test
    @DisplayName("상한 50까지는 받는다")
    void maxIndexAccepted() {
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(session(10L, WARD)));
        when(aiClient.fetchUtteranceAudio(10L, 50)).thenReturn(new CompanionAudio(new byte[]{1}, "audio/wav"));

        assertThat(service.utteranceAudio(WARD, 10L, "50").contentType()).isEqualTo("audio/wav");
    }

    @Test
    @DisplayName("말벗 꺼짐: 503 COMPANION_DISABLED - 다른 말벗 API와 같은 기준, DB·AI 미조회")
    void disabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.utteranceAudio(WARD, 10L, "1"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_DISABLED);
        verify(sessionRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("AI 오류는 그대로 올리고 자리를 돌려준다")
    void aiErrorReleasesPermit() {
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(session(10L, WARD)));
        when(aiClient.fetchUtteranceAudio(10L, 1)).thenThrow(new CustomException(ErrorCode.COMPANION_AUDIO_NOT_FOUND));

        assertThatThrownBy(() -> service.utteranceAudio(WARD, 10L, "1"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_AUDIO_NOT_FOUND);
        assertThat(service.availablePermits()).isEqualTo(properties.getAudioMaxConcurrent());
    }

    @Test
    @DisplayName("동시 상한을 넘으면 AI를 부르지 않고 503 COMPANION_AUDIO_NOT_READY")
    void concurrencyLimit() throws Exception {
        properties.setAudioMaxConcurrent(1);
        service = new CompanionAudioService(aiClient, properties, companionService);
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(session(10L, WARD)));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(aiClient.fetchUtteranceAudio(10L, 1)).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new CompanionAudio(new byte[]{1}, "audio/mpeg");
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> service.utteranceAudio(WARD, 10L, "1"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.utteranceAudio(WARD, 10L, "2"))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_AUDIO_NOT_READY);
            verify(aiClient, never()).fetchUtteranceAudio(10L, 2);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(service.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("AI audio 필드 옮기기: {index:n}만 받고 null·음수·상한 초과·문자열·소수는 null")
    void audioRefMapping() throws Exception {
        assertThat(companionService.audioRef(JSON.readTree("{\"audio\":{\"index\":3}}"))).isEqualTo(new UtteranceAudio(3));
        assertThat(companionService.audioRef(JSON.readTree("{\"audio\":{\"index\":0}}"))).isEqualTo(new UtteranceAudio(0));
        assertThat(companionService.audioRef(JSON.readTree("{\"audio\":null}"))).isNull();
        assertThat(companionService.audioRef(JSON.readTree("{}"))).isNull();
        assertThat(companionService.audioRef(JSON.readTree("{\"audio\":{\"index\":-1}}"))).isNull();
        assertThat(companionService.audioRef(JSON.readTree("{\"audio\":{\"index\":51}}"))).isNull();
        assertThat(companionService.audioRef(JSON.readTree("{\"audio\":{\"index\":\"2\"}}"))).isNull();
        assertThat(companionService.audioRef(JSON.readTree("{\"audio\":{\"index\":1.5}}"))).isNull();
        assertThat(companionService.audioRef(JSON.readTree("{\"audio\":{\"index\":99999999999}}"))).isNull();
    }
}
