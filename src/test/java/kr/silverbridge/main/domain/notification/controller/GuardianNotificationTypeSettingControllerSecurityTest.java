package kr.silverbridge.main.domain.notification.controller;

import kr.silverbridge.main.domain.notification.dto.GuardianNotificationTypeSettingRequest;
import kr.silverbridge.main.domain.notification.service.GuardianNotificationPreferenceService;
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

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 보호자 알림 종류 설정 권한 테스트 - GUARDIAN만 허용(WARD·ADMIN 403), 남의 설정을 가리킬 입력이 없다.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        GuardianNotificationTypeSettingControllerSecurityTest.MethodSecurityTestConfig.class,
        GuardianNotificationTypeSettingController.class
})
class GuardianNotificationTypeSettingControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean private GuardianNotificationPreferenceService service;
    @Autowired private GuardianNotificationTypeSettingController controller;

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자 → 조회·변경 허용")
    void guardian_허용() {
        assertThatNoException().isThrownBy(() -> controller.getSettings("GD0001"));
        assertThatNoException().isThrownBy(() ->
                controller.updateSettings("GD0001", new GuardianNotificationTypeSettingRequest(null, false)));
    }

    @Test
    @WithMockUser(roles = {"WARD", "ADMIN"})
    @DisplayName("피보호자·관리자 → 조회·변경 모두 403")
    void 보호자_아니면_거부() {
        assertThatThrownBy(() -> controller.getSettings("WD0001")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.updateSettings("WD0001",
                new GuardianNotificationTypeSettingRequest(null, false))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("요청에는 변경 가능한 두 종류만 있고 userId·필수 알림 필드는 없다")
    void 요청_필드_고정() {
        assertThat(Arrays.stream(GuardianNotificationTypeSettingRequest.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactlyInAnyOrder("anomalyReviewReminder", "medication");
    }
}
