package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.client.AiServerHealthClient;
import kr.silverbridge.main.domain.admin.dto.AdminAiStatusResponse;
import kr.silverbridge.main.domain.admin.dto.AiServiceState;
import kr.silverbridge.main.domain.anomaly.client.AiLiveStreamSubscriber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * AI 서비스 상태 매핑. 핵심은 <b>"아는 끊김"과 "모름"을 나누는 것</b>이다 - 이상감지 구독 끊김은 확인된 사실(DISCONNECTED),
 * 챗봇 확인 실패는 원인을 모르므로 null(확인 불가)이다.
 */
@ExtendWith(MockitoExtension.class)
class AdminAiStatusServiceTest {

    @Mock private AiLiveStreamSubscriber aiLiveStreamSubscriber;
    @Mock private AiServerHealthClient aiServerHealthClient;
    @InjectMocks private AdminAiStatusService service;

    @Test
    @DisplayName("둘 다 정상이면 OK, 확인 시각은 KST")
    void allOk() {
        when(aiLiveStreamSubscriber.isConnected()).thenReturn(true);
        when(aiServerHealthClient.isHealthy()).thenReturn(true);

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
        when(aiServerHealthClient.isHealthy()).thenReturn(true);

        assertThat(service.getAiStatus().anomalyDetection().status()).isEqualTo(AiServiceState.DISCONNECTED);
    }

    @Test
    @DisplayName("챗봇 확인에 실패하면 DISCONNECTED로 단정하지 않고 null(확인 불가)")
    void chatbotUnknown() {
        when(aiLiveStreamSubscriber.isConnected()).thenReturn(true);
        when(aiServerHealthClient.isHealthy()).thenReturn(false);

        AdminAiStatusResponse response = service.getAiStatus();

        assertThat(response.chatbot().status()).isNull();
        assertThat(response.chatbot().checkedAt()).isNotNull();
    }
}
