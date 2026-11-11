package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.client.AiServerHealthClient;
import kr.silverbridge.main.domain.admin.dto.AdminAiStatusResponse;
import kr.silverbridge.main.domain.admin.dto.AiServiceState;
import kr.silverbridge.main.domain.anomaly.client.AiLiveStreamSubscriber;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.domain.companion.client.CompanionAiStatusClient;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 서비스 상태 매핑. 핵심은 <b>"아는 사실"과 "모름"을 나누는 것</b>이다 - 이상감지 구독 끊김·챗봇 킬 스위치는 확인된 사실
 * (DISCONNECTED·DISABLED), 챗봇 확인 실패·재접속 직후 첫 목록 전은 모르는 상태(null)다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAiStatusServiceTest {

    @Mock private AiLiveStreamSubscriber aiLiveStreamSubscriber;
    @Mock private AiServerHealthClient aiServerHealthClient;
    @Mock private CompanionAiStatusClient companionAiStatusClient;
    private ChatRelayProperties chatRelayProperties;
    private CompanionProperties companionProperties;
    private AdminAiStatusService service;

    @BeforeEach
    void setUp() {
        chatRelayProperties = new ChatRelayProperties();
        companionProperties = new CompanionProperties();
        companionProperties.setEnabled(true);
        companionProperties.setAiBaseUrl("http://companion-ai.test");
        companionProperties.setApiKey("companion-key");
        service = new AdminAiStatusService(aiLiveStreamSubscriber, aiServerHealthClient, chatRelayProperties,
                companionAiStatusClient, companionProperties);
        // 기본: 말벗 AI 정상
        when(companionAiStatusClient.isAvailable()).thenReturn(true);
        when(companionAiStatusClient.maxWait()).thenReturn(Duration.ofSeconds(2));
        // 기본: 연결됨 + 첫 목록 받음 + AI 정상
        when(aiLiveStreamSubscriber.isConnected()).thenReturn(true);
        when(aiLiveStreamSubscriber.liveSessionSnapshot()).thenReturn(Optional.of(Set.of("ward_a1B2c3D4e5F6g7H8")));
        when(aiServerHealthClient.isHealthy()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @DisplayName("셋 다 정상이면 OK, 확인 시각은 KST")
    void allOk() {
        AdminAiStatusResponse response = service.getAiStatus();

        assertThat(response.anomalyDetection().status()).isEqualTo(AiServiceState.OK);
        assertThat(response.chatbot().status()).isEqualTo(AiServiceState.OK);
        assertThat(response.companion().status()).isEqualTo(AiServiceState.OK);
        assertThat(response.anomalyDetection().checkedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(response.companion().checkedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(response.chatbot().checkedAt()).isNotNull();
        assertThat(response.generatedAt()).isNotNull();
    }

    @Test
    @DisplayName("이상감지 구독이 끊기면 null이 아니라 DISCONNECTED")
    void anomalyDisconnected() {
        when(aiLiveStreamSubscriber.isConnected()).thenReturn(false);
        when(aiLiveStreamSubscriber.liveSessionSnapshot()).thenReturn(Optional.empty());

        assertThat(service.getAiStatus().anomalyDetection().status()).isEqualTo(AiServiceState.DISCONNECTED);
    }

    @Test
    @DisplayName("연결됐지만 첫 세션 목록 전이면 OK가 아니라 null(확인 중) - 안전 현황 streamingCameras와 같은 기준(점검 L-1)")
    void anomalyBeforeFirstList() {
        when(aiLiveStreamSubscriber.liveSessionSnapshot()).thenReturn(Optional.empty());

        assertThat(service.getAiStatus().anomalyDetection().status()).isNull();
    }

    @Test
    @DisplayName("첫 목록을 받았으면 송출 0대여도 OK - 0대는 아는 값이다")
    void anomalyEmptyListIsOk() {
        when(aiLiveStreamSubscriber.liveSessionSnapshot()).thenReturn(Optional.of(Set.of()));

        assertThat(service.getAiStatus().anomalyDetection().status()).isEqualTo(AiServiceState.OK);
    }

    @Test
    @DisplayName("챗봇 확인에 실패하면 DISCONNECTED로 단정하지 않고 null(확인 불가)")
    void chatbotUnknown() {
        when(aiServerHealthClient.isHealthy()).thenReturn(false);

        AdminAiStatusResponse response = service.getAiStatus();

        assertThat(response.chatbot().status()).isNull();
        assertThat(response.chatbot().checkedAt()).isNotNull();
    }

    @Test
    @DisplayName("챗봇 킬 스위치를 끄면 AI가 살아 있어도 DISABLED이고 AI를 부르지 않는다(점검 M-1)")
    void chatbotDisabled() {
        chatRelayProperties.setEnabled(false);

        assertThat(service.getAiStatus().chatbot().status()).isEqualTo(AiServiceState.DISABLED);
        verify(aiServerHealthClient, never()).isHealthy();
    }

    @Test
    @DisplayName("말벗 스위치를 끄면 AI가 살아 있어도 DISABLED이고 AI를 부르지 않는다")
    void companionDisabled() {
        companionProperties.setEnabled(false);

        AdminAiStatusResponse response = service.getAiStatus();

        assertThat(response.companion().status()).isEqualTo(AiServiceState.DISABLED);
        assertThat(response.companion().checkedAt()).isNotNull();
        verify(companionAiStatusClient, never()).isAvailable();
    }

    @Test
    @DisplayName("말벗이 켜져 있어도 주소·키가 비면 AI를 부르지 않고 null(확인 불가)")
    void companionNotConfigured() {
        companionProperties.setApiKey("");

        AdminAiStatusResponse response = service.getAiStatus();

        assertThat(response.companion().status()).isNull();
        assertThat(response.companion().checkedAt()).isNotNull();
        verify(companionAiStatusClient, never()).isAvailable();
    }

    @Test
    @DisplayName("말벗 확인 실패(통신 오류·5xx·형식 이상·enabled false - 클라이언트가 false)는 DISCONNECTED로 단정하지 않고 null")
    void companionUnknown() {
        when(companionAiStatusClient.isAvailable()).thenReturn(false);

        AdminAiStatusResponse response = service.getAiStatus();

        assertThat(response.companion().status()).isNull();
        assertThat(response.companion().checkedAt()).isNotNull();
        // 다른 칸은 영향받지 않는다
        assertThat(response.chatbot().status()).isEqualTo(AiServiceState.OK);
        assertThat(response.anomalyDetection().status()).isEqualTo(AiServiceState.OK);
    }

    @Test
    @DisplayName("말벗 확인이 기다림 상한을 넘으면 기다리지 않고 null")
    void companionTimeout() {
        when(companionAiStatusClient.maxWait()).thenReturn(Duration.ofMillis(100));
        when(companionAiStatusClient.isAvailable()).thenAnswer(invocation -> {
            Thread.sleep(3_000);
            return true;
        });

        long started = System.nanoTime();
        AdminAiStatusResponse response = service.getAiStatus();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(response.companion().status()).isNull();
        assertThat(elapsedMillis).isLessThan(2_000);
    }

    @Test
    @DisplayName("챗봇·말벗 확인은 동시에 한다 - 각각 0.8초면 합쳐 1.6초가 아니라 약 0.8초")
    void chatbotAndCompanionRunInParallel() {
        when(aiServerHealthClient.isHealthy()).thenAnswer(invocation -> {
            Thread.sleep(800);
            return true;
        });
        when(companionAiStatusClient.isAvailable()).thenAnswer(invocation -> {
            Thread.sleep(800);
            return true;
        });

        long started = System.nanoTime();
        AdminAiStatusResponse response = service.getAiStatus();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(response.chatbot().status()).isEqualTo(AiServiceState.OK);
        assertThat(response.companion().status()).isEqualTo(AiServiceState.OK);
        assertThat(elapsedMillis).isLessThan(1_400);
    }

    @Test
    @DisplayName("generatedAt은 모든 칸의 확인이 끝난 뒤의 시각이다")
    void generatedAtAfterAllChecks() {
        AdminAiStatusResponse response = service.getAiStatus();

        assertThat(response.generatedAt())
                .isAfterOrEqualTo(response.anomalyDetection().checkedAt())
                .isAfterOrEqualTo(response.chatbot().checkedAt())
                .isAfterOrEqualTo(response.companion().checkedAt());
    }
}
