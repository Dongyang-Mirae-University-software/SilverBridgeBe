package kr.silverbridge.main.domain.chat;

import kr.silverbridge.main.domain.admin.dto.AdminUserUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminConnectionService;
import kr.silverbridge.main.domain.admin.service.AdminUserService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.service.UserService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 연결이 끝나면 AI의 대리 상담 기록 삭제를 요청한다 - 탈퇴는 제외 (통합 테스트 2단계 후속, 2026-11-01).
 *
 * <p>근거: policy-chat "2026-10-31 전체 점검 반영 (L-13)". {@code AiProxyChatLogPurgeListener}가
 * {@code ConnectionDisconnectedEvent}(보호자·피보호자 해제·역할 변경 정리·관리자 강제 해제) AFTER_COMMIT에서
 * {@code aiPurgeExecutor}로 AI {@code DELETE /api/v1/chat/logs/proxy}를 부른다. 탈퇴({@code WITHDRAWN})는 탈퇴 정리가
 * 같은 기록을 지우므로 건너뛴다. 실제 서비스 진입점 → 커밋 → 비동기 리스너까지 경로마다 정확한 (보호자, 피보호자)
 * 쌍으로 불리는지를 본다. AI 클라이언트는 기반 클래스의 목이다(실제 호출 0건).</p>
 *
 * <p>"부르지 않는다" 단언은 다른 비동기 결과(같은 이벤트의 다른 리스너가 남기는 흔적)를 기다린 뒤 모든 executor가
 * 비었을 때 한다 - 리스너가 아직 안 돌아서 통과하는 거짓 통과를 막는다.</p>
 */
class ConnectionEndProxyChatPurgeFullIntegrationTest extends FullContextIntegrationTest {

    private static final String ADMIN = "APP001";
    private static final String GUARDIAN = "GPP001";
    private static final String GUARDIAN2 = "GPP002";
    private static final String WARD = "WPP001";

    @Autowired private ConnectionService connectionService;
    @Autowired private AdminUserService adminUserService;
    @Autowired private AdminConnectionService adminConnectionService;
    @Autowired private UserService userService;

    @Test
    @DisplayName("보호자 해제: 그 (보호자, 피보호자) 쌍으로 대리 상담 기록 삭제를 정확히 1회 요청한다")
    void 보호자_해제() {
        Long connectionId = connected(GUARDIAN, WARD);

        connectionService.disconnectAsGuardian(GUARDIAN, connectionId);

        assertPurgedOnceEach(GUARDIAN, WARD);
    }

    @Test
    @DisplayName("피보호자 해제: 그 (보호자, 피보호자) 쌍으로 대리 상담 기록 삭제를 정확히 1회 요청한다")
    void 피보호자_해제() {
        Long connectionId = connected(GUARDIAN, WARD);

        connectionService.disconnectAsWard(WARD, connectionId);

        assertPurgedOnceEach(GUARDIAN, WARD);
    }

    @Test
    @DisplayName("관리자 역할 변경 정리: 피보호자의 연결 2건이 끊기면 보호자별로 1회씩 요청한다")
    void 역할_변경_정리() {
        saveUser(ADMIN, Role.ADMIN, Status.ACTIVE);
        connected(GUARDIAN, WARD);
        saveUser(GUARDIAN2, Role.GUARDIAN, Status.ACTIVE);
        saveActiveConnection(GUARDIAN2, WARD);

        adminUserService.updateUser(WARD, new AdminUserUpdateRequest(null, Role.GUARDIAN, null, null), ADMIN);

        verify(aiChatClient, timeout(10_000)).deleteProxyLogs(GUARDIAN, WARD);
        verify(aiChatClient, timeout(10_000)).deleteProxyLogs(GUARDIAN2, WARD);
        awaitAsyncIdle();
        verify(aiChatClient, times(2)).deleteProxyLogs(anyString(), anyString());
    }

    @Test
    @DisplayName("관리자 강제 해제: 양쪽 알림용으로 이벤트가 두 번 발행되어 같은 쌍으로 2회 요청한다(AI 삭제는 멱등)")
    void 관리자_강제_해제() {
        saveUser(ADMIN, Role.ADMIN, Status.ACTIVE);
        Long connectionId = connected(GUARDIAN, WARD);

        adminConnectionService.forceDisconnect(connectionId, ADMIN);

        verify(aiChatClient, timeout(10_000).times(2)).deleteProxyLogs(GUARDIAN, WARD);
        awaitAsyncIdle();
        verify(aiChatClient, times(2)).deleteProxyLogs(anyString(), anyString());
    }

    @Test
    @DisplayName("탈퇴로 끝난 연결: 대리 상담 삭제는 요청하지 않는다(탈퇴 정리가 회원 단위로 지운다)")
    void 탈퇴는_제외한다() {
        connected(GUARDIAN, WARD);

        userService.withdraw(GUARDIAN, PASSWORD, null, "127.0.0.1", "it");
        userService.purgeWithdrawnUser(GUARDIAN);

        // 같은 해제 이벤트(WITHDRAWN)의 다른 리스너가 남은 피보호자에게 알림을 남겼고, 탈퇴 정리의 회원 단위 삭제가 나갔다
        eventually().untilAsserted(() -> assertThat(notificationLogs(WARD, NotificationType.CONNECTION_DISCONNECTED))
                .as("WITHDRAWN 해제 이벤트가 실제로 발행·처리됨").isEqualTo(1));
        verify(aiChatClient, timeout(10_000)).deleteLogs(GUARDIAN);
        awaitAsyncIdle();
        verify(aiChatClient, never()).deleteProxyLogs(anyString(), anyString());
    }

    @Test
    @DisplayName("AI 삭제 요청이 실패해도 연결 해제는 커밋되고 상대 해제 알림도 나간다(best-effort)")
    void AI_실패는_해제를_되돌리지_않는다() {
        Long connectionId = connected(GUARDIAN, WARD);
        doThrow(new IllegalStateException("it: ai down")).when(aiChatClient).deleteProxyLogs(GUARDIAN, WARD);

        connectionService.disconnectAsGuardian(GUARDIAN, connectionId);

        verify(aiChatClient, timeout(10_000)).deleteProxyLogs(GUARDIAN, WARD);
        eventually().untilAsserted(() -> assertThat(notificationLogs(WARD, NotificationType.CONNECTION_DISCONNECTED))
                .as("상대 해제 알림").isEqualTo(1));
        awaitAsyncIdle();
        assertThat(jdbc.queryForObject("SELECT status FROM connection WHERE id = ?", String.class, connectionId))
                .isEqualTo("DISCONNECTED");
    }

    private Long connected(String guardianId, String wardId) {
        if (!userRepository.existsById(guardianId)) {
            saveUser(guardianId, Role.GUARDIAN, Status.ACTIVE);
        }
        if (!userRepository.existsById(wardId)) {
            saveUser(wardId, Role.WARD, Status.ACTIVE);
        }
        return saveActiveConnection(guardianId, wardId);
    }

    private void assertPurgedOnceEach(String guardianId, String wardId) {
        verify(aiChatClient, timeout(10_000)).deleteProxyLogs(guardianId, wardId);
        awaitAsyncIdle();
        verify(aiChatClient, times(1)).deleteProxyLogs(anyString(), anyString());
    }
}
