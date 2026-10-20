package kr.silverbridge.main.domain.connection;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionRequestLimiter;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code isActiveConnection} 이 실제 DB 에서 ACTIVE 만 true 인가 (2026-10-20 챗 wardId 검증 점검 L-4).
 *
 * <p>SOS·복약·이상감지·카메라·챗 대리 상담의 인가가 모두 이 메서드 하나에 기댄다. 서비스 단위 테스트는 저장소를 목으로
 * 바꾸므로 PENDING·CANCELLED·REFUSED·DISCONNECTED 가 정말 false 인지는 여기서만 확인된다.</p>
 */
@Import(ConnectionService.class)
class ConnectionActiveStatusIntegrationTest extends PostgresIntegrationTest {

    private static final String GUARDIAN = "GAS001";
    private static final String OTHER_GUARDIAN = "GAS002";

    @Autowired private ConnectionService connectionService;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private UserRepository userRepository;

    // 요청 횟수 제한(Redis)은 이 검증과 무관하다
    @MockitoBean private ConnectionRequestLimiter connectionRequestLimiter;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(GUARDIAN, "보호자", Role.GUARDIAN));
        userRepository.save(TestData.user(OTHER_GUARDIAN, "다른보호자", Role.GUARDIAN));
        for (ConnectionStatus status : ConnectionStatus.values()) {
            String wardId = wardOf(status);
            userRepository.save(TestData.user(wardId, "피보호자" + status.ordinal(), Role.WARD));
            connectionRepository.save(Connection.builder()
                    .guardianId(GUARDIAN)
                    .wardId(wardId)
                    .status(status)
                    .initiatedBy(GUARDIAN)
                    .relation("아들")
                    .build());
        }
        connectionRepository.flush();
    }

    private static String wardOf(ConnectionStatus status) {
        return "GW" + status.ordinal() + "001";
    }

    @Test
    @DisplayName("ACTIVE 만 true - PENDING·CANCELLED·REFUSED·DISCONNECTED 는 false")
    void ACTIVE만_true() {
        for (ConnectionStatus status : ConnectionStatus.values()) {
            assertThat(connectionService.isActiveConnection(GUARDIAN, wardOf(status)))
                    .as("상태 %s", status)
                    .isEqualTo(status == ConnectionStatus.ACTIVE);
        }
    }

    @Test
    @DisplayName("ACTIVE 여도 다른 보호자·반대 방향·없는 ID 는 false")
    void ACTIVE여도_당사자가_아니면_false() {
        String activeWard = wardOf(ConnectionStatus.ACTIVE);

        assertThat(connectionService.isActiveConnection(OTHER_GUARDIAN, activeWard)).isFalse();
        assertThat(connectionService.isActiveConnection(activeWard, GUARDIAN)).isFalse();
        assertThat(connectionService.isActiveConnection(GUARDIAN, "NOBODY")).isFalse();
    }
}
