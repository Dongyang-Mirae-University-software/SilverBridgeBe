package kr.silverbridge.main.domain.sos;

import com.google.firebase.messaging.MulticastMessage;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.service.SosService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 챗봇 119(CHATBOT_119) SOS도 SOS 버튼과 같은 알림 경로를 탄다 (2026-11-17 영향 범위 점검 L-1).
 *
 * <p>실서버(gosky)에서 실제로 보내면 FE 보호자 이력 화면이 이 값을 몰라 깨지고(점검 H-1) 강제 푸시·문자가 실제로 나가므로,
 * 서비스 → 커밋 → 긴급 executor 리스너 → 디스패처 → 채널 → 발송 이력까지를 여기서 확인한다(policy-sos 규칙 ② -
 * 발생 경로는 알림을 가르지 않는다).</p>
 */
class SosChatbot119FullIntegrationTest extends FullContextIntegrationTest {

    private static final String WARD = "WCB119";
    private static final String GUARDIAN = "GCB119";

    @Autowired private SosService sosService;

    @Test
    @DisplayName("챗봇 119 → 이력 trigger_type CHATBOT_119 저장, ACTIVE 보호자에게 WARD_SOS 강제 푸시·WS sos-triggered·발송 이력 DELIVERED")
    void 챗봇119도_WARD_SOS로_발송() throws Exception {
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        signedIn(GUARDIAN);

        sosService.trigger(WARD, null, SosTriggerType.CHATBOT_119);

        eventually().untilAsserted(() -> assertThat(count(
                "SELECT count(*) FROM notification_log WHERE type = 'WARD_SOS' AND recipient_id = ? AND result = 'DELIVERED'",
                GUARDIAN)).isEqualTo(1));
        assertThat(count("SELECT count(*) FROM sos_event WHERE ward_id = ? AND trigger_type = 'CHATBOT_119'", WARD))
                .isEqualTo(1);
        verify(firebaseMessaging, times(1)).sendEachForMulticast(any(MulticastMessage.class));
        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN), eq("sos-triggered"), any());
        verifyNoInteractions(smsSender); // 푸시가 전달됐으니 문자 대체 없음
    }
}
