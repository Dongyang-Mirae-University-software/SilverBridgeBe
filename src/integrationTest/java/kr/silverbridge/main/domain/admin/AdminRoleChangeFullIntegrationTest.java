package kr.silverbridge.main.domain.admin;

import kr.silverbridge.main.domain.admin.dto.AdminUserUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminUserService;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.util.RedisKeys;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 역할 변경 = 연결 정리 + 카메라 삭제 + 토큰 무효화 (통합 테스트 2단계 시나리오 4, CLAUDE.md §8 2026-09-10).
 *
 * <p>세 효과가 서로 다른 곳에 있다 - 연결 정리는 {@code ConnectionService}(같은 트랜잭션, 해제 이벤트 발행), 카메라는
 * {@code CameraService}, 토큰은 {@code UserRoleChangedEvent}의 AFTER_COMMIT 리스너(REQUIRES_NEW), 상대방 알림은 해제
 * 이벤트의 {@code @Async} 리스너. 단위 테스트는 각각을 목으로 따로 보므로, 한 번의 수정으로 넷이 모두 커밋되는지는
 * 여기서만 보인다(회귀 고정 - 되돌릴 수정 커밋은 없다).</p>
 */
class AdminRoleChangeFullIntegrationTest extends FullContextIntegrationTest {

    private static final String ADMIN = "ARC001";
    private static final String GUARDIAN = "GRC001";
    private static final String WARD = "WRC001";

    @Autowired private AdminUserService adminUserService;
    @Autowired private CameraRepository cameraRepository;

    @Test
    @DisplayName("피보호자 → 보호자: 연결 해제·카메라 삭제·토큰 무효화가 모두 커밋되고, 상대 보호자에게 해제 알림이 가며 감사 로그가 남는다")
    void 역할_변경은_연결_카메라_토큰을_함께_정리한다() {
        saveUser(ADMIN, Role.ADMIN, Status.ACTIVE);
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        Long connectionId = saveActiveConnection(GUARDIAN, WARD);
        cameraRepository.save(TestData.camera(WARD, "ward_itrolechange0001", "거실"));
        signedIn(WARD);

        adminUserService.updateUser(WARD, new AdminUserUpdateRequest(null, Role.GUARDIAN, null, null), ADMIN);

        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, WARD)).isEqualTo("GUARDIAN");
        assertThat(jdbc.queryForObject("SELECT status FROM connection WHERE id = ?", String.class, connectionId))
                .as("연결 정리").isEqualTo("DISCONNECTED");
        assertThat(cameraRepository.findByWardIdOrderByCreatedAtDesc(WARD))
                .as("보호자가 되면 카메라 API를 못 쓰므로 함께 삭제").isEmpty();
        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", WARD))
                .as("옛 역할 refresh 토큰 삭제").isZero();
        assertThat(redis.hasKey(RedisKeys.PASSWORD_INVALIDATE + WARD))
                .as("옛 역할 access 토큰 무효화").isTrue();
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE admin_id = ? AND action = 'USER_ROLE_CHANGE' "
                + "AND target_id = ?", ADMIN, WARD)).as("감사 로그").isEqualTo(1);
        eventually().untilAsserted(() -> assertThat(notificationLogs(GUARDIAN, NotificationType.CONNECTION_DISCONNECTED))
                .as("상대 보호자에게 해제 알림(이력)").isEqualTo(1));
    }
}
