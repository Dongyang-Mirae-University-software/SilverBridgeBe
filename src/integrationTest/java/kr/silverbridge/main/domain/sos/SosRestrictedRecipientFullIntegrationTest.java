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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 정지(RESTRICTED) 계정은 수신자일 때 강제 FCM까지 전 채널 차단 (통합 테스트 2단계 시나리오 5, CLAUDE.md §8 2026-09-09).
 *
 * <p>SOS는 강제 푸시 + SMS 대체 발송 종류라 차단이 빠지면 정지된 사람에게도 기어이 도달한다. 차단은
 * {@code NotificationDispatcher.dispatch()} 한 곳에 있다 - SOS 서비스 → 커밋 → 긴급 executor 리스너 → 디스패처 →
 * 채널 → 발송 이력까지 실제로 거쳐, 같은 SOS에서 정지 보호자는 막히고 정상 보호자는 받는지 본다(회귀 고정).</p>
 */
class SosRestrictedRecipientFullIntegrationTest extends FullContextIntegrationTest {

    private static final String WARD = "WSR001";
    private static final String ACTIVE_GUARDIAN = "GSR001";
    private static final String RESTRICTED_GUARDIAN = "GSR002";

    @Autowired private SosService sosService;

    @Test
    @DisplayName("정지 보호자는 이력만 NOT_SENT(RESTRICTED_ACCOUNT)로 남고, 같은 피보호자의 정상 보호자는 FCM으로 받는다")
    void 정지_보호자는_막히고_정상_보호자는_받는다() throws Exception {
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveUser(ACTIVE_GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(RESTRICTED_GUARDIAN, Role.GUARDIAN, Status.RESTRICTED);
        saveActiveConnection(ACTIVE_GUARDIAN, WARD);
        saveActiveConnection(RESTRICTED_GUARDIAN, WARD);
        signedIn(ACTIVE_GUARDIAN);
        signedIn(RESTRICTED_GUARDIAN);

        sosService.trigger(WARD, "서울시 테스트구", SosTriggerType.SOS_BUTTON);

        eventually().untilAsserted(() -> assertThat(results()).containsOnlyKeys(ACTIVE_GUARDIAN, RESTRICTED_GUARDIAN));
        assertThat(results())
                .containsEntry(ACTIVE_GUARDIAN, "DELIVERED/-")
                .containsEntry(RESTRICTED_GUARDIAN, "NOT_SENT/RESTRICTED_ACCOUNT");
        verify(firebaseMessaging, times(1)).sendEachForMulticast(any(MulticastMessage.class));
        verifyNoInteractions(smsSender);
    }

    /** 수신자별 "결과/미발송 사유". */
    private Map<String, String> results() {
        return Map.copyOf(jdbc.query(
                "SELECT recipient_id, result, coalesce(not_sent_reason, '-') AS reason FROM notification_log "
                        + "WHERE type = 'WARD_SOS'",
                rs -> {
                    Map<String, String> m = new java.util.HashMap<>();
                    while (rs.next()) {
                        m.put(rs.getString("recipient_id"), rs.getString("result") + "/" + rs.getString("reason"));
                    }
                    return m;
                }));
    }
}
