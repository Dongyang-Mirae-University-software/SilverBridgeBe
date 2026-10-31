package kr.silverbridge.main.domain.admin;

import kr.silverbridge.main.domain.admin.dto.AdminForceConnectRequest;
import kr.silverbridge.main.domain.admin.service.AdminConnectionService;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 관리자 강제 연결 - 양쪽 화면 갱신(WS)이 알림 발송보다 먼저, 전용 문구, 감사 로그 (통합 테스트 2단계 후속, 2026-11-01).
 *
 * <p>근거: CLAUDE.md §8 "관리자 강제 연결 = 양쪽 알림 + 감사 로그, 전용 문구(CONNECTION_FORCED)",
 * domain-security-policy CONN-G02 "양쪽 connection-accepted WS를 dispatch보다 먼저(설정 DB 오류가 피보호자 화면 갱신을
 * 막지 않게), 알림은 SETTINGS_ONLY". 순서는 커밋 뒤 {@code @Async} 리스너 한 메서드 안에서 정해져 단위 테스트의 목
 * 순서만으로는 실제 빈 구성에서 지켜지는지 보이지 않는다 - 실제 WS 발행자·디스패처를 스파이로 감싸 호출 순서를 본다.</p>
 */
class AdminForceConnectFullIntegrationTest extends FullContextIntegrationTest {

    private static final String ADMIN = "AFC001";
    private static final String GUARDIAN = "GFC001";
    private static final String WARD = "WFC001";
    private static final String WS_EVENT = "connection-accepted";

    @Autowired private AdminConnectionService adminConnectionService;

    @Test
    @DisplayName("강제 연결: 양쪽 WS가 알림 발송보다 먼저 나가고, 양쪽 알림은 CONNECTION_FORCED이며 감사 로그가 1건 남는다")
    void 강제_연결은_WS를_먼저_보내고_양쪽에_전용_알림과_감사_로그를_남긴다() {
        saveUsers();
        signedIn(GUARDIAN);
        signedIn(WARD);

        Long connectionId = adminConnectionService
                .forceConnect(new AdminForceConnectRequest(GUARDIAN, WARD), ADMIN).connectionId();

        assertThat(jdbc.queryForObject("SELECT status FROM connection WHERE id = ?", String.class, connectionId))
                .isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE admin_id = ? AND action = 'FORCE_CONNECT' "
                + "AND target_id = ?", ADMIN, String.valueOf(connectionId)))
                .as("감사 로그(동의의 대체물)").isEqualTo(1);
        eventually().untilAsserted(() -> {
            assertThat(notificationLogs(GUARDIAN, NotificationType.CONNECTION_FORCED)).as("보호자 알림").isEqualTo(1);
            assertThat(notificationLogs(WARD, NotificationType.CONNECTION_FORCED)).as("피보호자 알림").isEqualTo(1);
        });
        awaitAsyncIdle();
        assertThat(notificationLogs(GUARDIAN, NotificationType.CONNECTION_ACCEPTED) + notificationLogs(WARD,
                NotificationType.CONNECTION_ACCEPTED)).as("'수락했습니다' 문구 재사용 금지").isZero();

        InOrder order = inOrder(webSocketEventPublisher, notificationDispatcher);
        order.verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN), eq(WS_EVENT), argThat(AdminForceConnectFullIntegrationTest::isForced));
        order.verify(webSocketEventPublisher).sendToUser(eq(WARD), eq(WS_EVENT), argThat(AdminForceConnectFullIntegrationTest::isForced));
        order.verify(notificationDispatcher).dispatch(eq(GUARDIAN), eq(WARD), eq(NotificationType.CONNECTION_FORCED), any());
        order.verify(notificationDispatcher).dispatch(eq(WARD), eq(WARD), eq(NotificationType.CONNECTION_FORCED), any());
    }

    @Test
    @DisplayName("강제 연결: 보호자 알림 설정 조회(DB)가 실패해도 양쪽 화면 갱신 WS는 나가고 연결·감사 로그는 커밋된다")
    void 알림_설정_조회가_실패해도_양쪽_WS는_나간다() {
        saveUsers();
        doThrow(new DataAccessResourceFailureException("it: settings lookup down"))
                .when(notificationSettingService).enabledChannels(GUARDIAN);

        Long connectionId = adminConnectionService
                .forceConnect(new AdminForceConnectRequest(GUARDIAN, WARD), ADMIN).connectionId();

        verify(webSocketEventPublisher, timeout(10_000))
                .sendToUser(eq(GUARDIAN), eq(WS_EVENT), argThat(AdminForceConnectFullIntegrationTest::isForced));
        verify(webSocketEventPublisher, timeout(10_000))
                .sendToUser(eq(WARD), eq(WS_EVENT), argThat(AdminForceConnectFullIntegrationTest::isForced));
        verify(notificationSettingService, timeout(10_000)).enabledChannels(GUARDIAN);
        awaitAsyncIdle();
        assertThat(jdbc.queryForObject("SELECT status FROM connection WHERE id = ?", String.class, connectionId))
                .isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'FORCE_CONNECT'")).isEqualTo(1);
        // 피보호자 푸시가 이 경우 함께 빠지는지는 단언하지 않는다 - 현재 동작은 PHASE 3 이슈 표(🟡)로 보고했다.
        // 여기서 고정하면 그 문제를 고칠 때 이 테스트가 막는다.
    }

    private void saveUsers() {
        saveUser(ADMIN, Role.ADMIN, Status.ACTIVE);
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
    }

    /** "피보호자가 수락했습니다"로 오인되지 않게 payload에 강제 연결 종류가 실려 있는가(CONN-G01). */
    private static boolean isForced(Object payload) {
        return payload instanceof Map<?, ?> map && "CONNECTION_FORCED".equals(map.get("type"));
    }
}
