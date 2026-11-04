package kr.silverbridge.main.domain.chat.service;

import kr.silverbridge.main.domain.chat.client.AiSttClient;
import kr.silverbridge.main.domain.chat.config.ChatSttProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ChatSttServiceTest {

    private static final String USER = "WRD001";
    private static final String SECRET_TEXT = "비밀스러운받아쓰기";

    @Mock private AiSttClient client;
    @Mock private RateLimitService rateLimitService;
    private ChatSttProperties properties;
    private ChatSttService service;

    @BeforeEach
    void setUp() {
        properties = new ChatSttProperties();
        properties.setEnabled(true);
        properties.setAiBaseUrl("http://ai");
        properties.setApiKey("k");
        properties.setMaxConcurrent(2);
        service = new ChatSttService(client, properties, rateLimitService);
    }

    private static MockMultipartFile audio(int size) {
        return new MockMultipartFile("audio", "rec.webm", "audio/webm", new byte[size]);
    }

    @Test
    @DisplayName("성공: 글자를 돌려주고, 속도 제한을 토큰 사용자로 확인하며, 로그에 글자를 남기지 않는다")
    void success(CapturedOutput output) {
        when(client.transcribe(any())).thenReturn(SECRET_TEXT);

        assertThat(service.transcribe(USER, audio(10)).text()).isEqualTo(SECRET_TEXT);
        verify(rateLimitService).check(ChatSttService.RATE_LIMIT_ENDPOINT, USER, 10, 100);
        assertThat(output.getAll()).doesNotContain(SECRET_TEXT);
        assertThat(service.activeUsers()).isZero();
    }

    @Test
    @DisplayName("스위치 꺼짐 → 503 DISABLED, 속도 제한·AI 호출 없음")
    void disabled() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.transcribe(USER, audio(10)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_DISABLED);
        verifyNoInteractions(client, rateLimitService);
    }

    @Test
    @DisplayName("키 미설정 → 503 DISABLED")
    void notConfigured() {
        properties.setApiKey("");
        assertThatThrownBy(() -> service.transcribe(USER, audio(10)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_DISABLED);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("속도 제한 초과는 그대로 올라가고 AI를 부르지 않는다")
    void rateLimited() {
        doThrow(new CustomException(ErrorCode.TOO_MANY_REQUESTS))
                .when(rateLimitService).check(anyString(), anyString(), anyInt(), anyInt());
        assertThatThrownBy(() -> service.transcribe(USER, audio(10)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("파일 없음·빈 파일 → 400 AUDIO_INVALID")
    void missingOrEmpty() {
        assertThatThrownBy(() -> service.transcribe(USER, null))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_AUDIO_INVALID);
        assertThatThrownBy(() -> service.transcribe(USER, audio(0)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_AUDIO_INVALID);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("상한 초과 → 413 AUDIO_TOO_LARGE")
    void tooLarge() {
        properties.setMaxAudioBytes(5);
        assertThatThrownBy(() -> service.transcribe(USER, audio(6)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_AUDIO_TOO_LARGE);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("AI 실패·시간 초과도 1인·서버 전체 자리를 돌려준다(전체 상한 1에서 연속 호출이 막히지 않는다)")
    void releasesOnFailure() {
        properties.setMaxConcurrent(1);
        service = new ChatSttService(client, properties, rateLimitService);
        when(client.transcribe(any()))
                .thenThrow(new CustomException(ErrorCode.CHAT_STT_BUSY))
                .thenThrow(new CustomException(ErrorCode.CHAT_STT_UNAVAILABLE))
                .thenThrow(new IllegalStateException("unexpected"))
                .thenReturn("ok");
        assertThatThrownBy(() -> service.transcribe(USER, audio(10)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_BUSY);
        assertThatThrownBy(() -> service.transcribe("WRD002", audio(10)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_UNAVAILABLE);
        assertThatThrownBy(() -> service.transcribe(USER, audio(10))).isInstanceOf(IllegalStateException.class);
        assertThat(service.activeUsers()).isZero();

        assertThat(service.transcribe("WRD003", audio(10)).text()).isEqualTo("ok");
    }

    @Test
    @DisplayName("같은 사용자 동시 2건 → 두 번째는 AI를 부르지 않고 503 BUSY, 서버 전체 상한도 같다")
    void concurrencyLimits() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(client.transcribe(any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "ok";
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> service.transcribe(USER, audio(10)));
            Future<?> other = pool.submit(() -> service.transcribe("WRD002", audio(10)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            // 1인 상한
            assertThatThrownBy(() -> service.transcribe(USER, audio(10)))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_BUSY);
            // 서버 전체 상한(2)
            assertThatThrownBy(() -> service.transcribe("WRD003", audio(10)))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_BUSY);

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            other.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(service.activeUsers()).isZero();
    }
}
