package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.client.AiServerHealthClient;
import kr.silverbridge.main.domain.admin.dto.AdminAiStatusResponse;
import kr.silverbridge.main.domain.admin.dto.AiServiceState;
import kr.silverbridge.main.domain.anomaly.client.AiLiveStreamSubscriber;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
    private ChatRelayProperties chatRelayProperties;
    private AdminAiStatusService service;

    @BeforeEach
    void setUp() {
        chatRelayProperties = new ChatRelayProperties();
        service = new AdminAiStatusService(aiLiveStreamSubscriber, aiServerHealthClient, chatRelayProperties);
        // 기본: 연결됨 + 첫 목록 받음 + AI 정상
        when(aiLiveStreamSubscriber.isConnected()).thenReturn(true);
        when(aiLiveStreamSubscriber.liveSessionSnapshot()).thenReturn(Optional.of(Set.of("ward_a1B2c3D4e5F6g7H8")));
        when(aiServerHealthClient.isHealthy()).thenReturn(true);
    }

    @Test
    @DisplayName("둘 다 정상이면 OK, 확인 시각은 KST")
    void allOk() {
        AdminAiStatusResponse response = service.getAiStatus();

        assertThat(response.anomalyDetection().status()).isEqualTo(AiServiceState.OK);
        assertThat(response.chatbot().status()).isEqualTo(AiServiceState.OK);
        assertThat(response.anomalyDetection().checkedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
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
}
