package kr.silverbridge.main.domain.user;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대시보드 "알림을 받을 수 있는 보호자가 없는 피보호자" 쿼리(ADMIN-G27)와 WebSocket 차단용 상태 조회(ANOM-G10).
 * 목 테스트는 JPQL(두 엔티티 조인 서브쿼리·스칼라 조회)을 실행하지 않는다.
 */
class WardReachableGuardianCountIntegrationTest extends PostgresIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ConnectionRepository connectionRepository;

    private long withoutActive() {
        return userRepository.countWardsWithoutActiveGuardian(Role.WARD, Status.ACTIVE, ConnectionStatus.ACTIVE);
    }

    private long withoutReachable() {
        return userRepository.countWardsWithoutReachableGuardian(Role.WARD, Status.ACTIVE, ConnectionStatus.ACTIVE);
    }

    private void connect(String guardianId, String wardId, ConnectionStatus status) {
        connectionRepository.save(Connection.builder()
                .guardianId(guardianId)
                .wardId(wardId)
                .status(status)
                .initiatedBy(guardianId)
                .build());
    }

    @Test
    @DisplayName("정지 보호자만 남은 피보호자는 새 지표에만 잡히고, 이용 중 보호자가 한 명이라도 있으면 어느 쪽에도 안 잡힌다")
    void 정지_보호자만_남은_피보호자() {
        long baseActive = withoutActive();
        long baseReachable = withoutReachable();

        User activeGuardian = TestData.user("GRC001", "이용중보호자", Role.GUARDIAN);
        User restrictedGuardian = TestData.user("GRC002", "정지보호자", Role.GUARDIAN);
        restrictedGuardian.restrict("테스트");
        userRepository.saveAll(List.of(activeGuardian, restrictedGuardian,
                TestData.user("WRC001", "연결없음", Role.WARD),
                TestData.user("WRC002", "정지보호자만", Role.WARD),
                TestData.user("WRC003", "이용중보호자", Role.WARD),
                TestData.user("WRC004", "수락대기만", Role.WARD),
                TestData.user("WRC005", "정지+이용중", Role.WARD)));
        connect("GRC002", "WRC002", ConnectionStatus.ACTIVE);
        connect("GRC001", "WRC003", ConnectionStatus.ACTIVE);
        connect("GRC001", "WRC004", ConnectionStatus.PENDING);
        connect("GRC002", "WRC005", ConnectionStatus.ACTIVE);
        connect("GRC001", "WRC005", ConnectionStatus.ACTIVE);

        // 기존 지표: 연결 없음(WRC001)·수락 대기만(WRC004)
        assertThat(withoutActive() - baseActive).isEqualTo(2);
        // 새 지표: 위 둘 + 정지 보호자만 남은 WRC002. WRC005는 이용 중 보호자가 있어 제외
        assertThat(withoutReachable() - baseReachable).isEqualTo(3);
    }

    @Test
    @DisplayName("상태 조회는 상태 값만 돌려주고, 없는 회원은 빈 값이다")
    void 상태_조회() {
        User restricted = TestData.user("GRC003", "정지", Role.GUARDIAN);
        restricted.restrict("테스트");
        userRepository.save(restricted);

        assertThat(userRepository.findStatusById("GRC003")).contains(Status.RESTRICTED);
        assertThat(userRepository.findStatusById("NOPE01")).isEmpty();
    }
}
