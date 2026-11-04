package kr.silverbridge.main.domain.notification.controller;

import kr.silverbridge.main.domain.notification.dto.UserNotificationItem;
import kr.silverbridge.main.domain.notification.service.UserNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 내 알림 기록 권한 - 보호자·피보호자만(관리자 403), 사용자 ID를 경로·쿼리로 받지 않고, 관리자용 칼럼을 응답에 싣지 않는다.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        UserNotificationControllerSecurityTest.MethodSecurityTestConfig.class,
        UserNotificationController.class
})
class UserNotificationControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean private UserNotificationService service;
    @Autowired private UserNotificationController controller;

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자 → 네 API 모두 허용")
    void guardian_허용() {
        assertAllAllowed("GD0001");
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자 → 네 API 모두 허용")
    void ward_허용() {
        assertAllAllowed("WD0001");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자 → 네 API 모두 403 (관리자는 알림을 받지 않는다)")
    void admin_거부() {
        assertThatThrownBy(() -> controller.getMyNotifications("AD0001", null, 0, 20)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.countUnread("AD0001")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.markRead("AD0001", 1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.markAllRead("AD0001")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("경로·쿼리 파라미터에 사용자 ID가 없다 - 대상은 토큰 사용자뿐")
    void 사용자ID_입력_없음() {
        for (Method method : UserNotificationController.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter.isAnnotationPresent(RequestParam.class) || parameter.isAnnotationPresent(PathVariable.class)) {
                    assertThat(parameter.getType().getSimpleName()).isNotEqualTo("String");
                }
            }
        }
    }

    @Test
    @DisplayName("응답 항목에 발송 결과·채널 결과·보내지 않은 사유·수신자 ID가 없다")
    void 응답_필드_고정() {
        assertThat(Arrays.stream(UserNotificationItem.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("id", "type", "category", "title", "body", "createdAt", "read", "wardId", "wardName");
    }

    private void assertAllAllowed(String userId) {
        assertThatNoException().isThrownBy(() -> controller.getMyNotifications(userId, null, 0, 20));
        assertThatNoException().isThrownBy(() -> controller.countUnread(userId));
        assertThatNoException().isThrownBy(() -> controller.markRead(userId, 1L));
        assertThatNoException().isThrownBy(() -> controller.markAllRead(userId));
    }
}
