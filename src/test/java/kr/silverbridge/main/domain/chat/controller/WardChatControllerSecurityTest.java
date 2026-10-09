package kr.silverbridge.main.domain.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.chat.dto.ChatRelayRequest;
import kr.silverbridge.main.domain.chat.service.ChatRelayService;
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
import static org.mockito.Mockito.verifyNoInteractions;

/** 챗 중계 API 권한 - 피보호자(WARD) 전용(GUARDIAN·ADMIN 403)이고 요청에서 userId를 받지 않는다. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        WardChatControllerSecurityTest.MethodSecurityTestConfig.class,
        WardChatController.class
})
class WardChatControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private ChatRelayService chatRelayService;

    @Autowired
    private WardChatController controller;

    private static final ChatRelayRequest REQUEST = new ChatRelayRequest("안녕", null, null, null, null);

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("WARD → 전송·기록 목록·기록 상세 허용")
    void ward_허용() {
        assertThatNoException().isThrownBy(() -> controller.send("WRD001", REQUEST));
        assertThatNoException().isThrownBy(() -> controller.logs("WRD001"));
        assertThatNoException().isThrownBy(() -> controller.logDetail("WRD001", 1L));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN → 3개 API 모두 403")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.send("GRD001", REQUEST)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.logs("GRD001")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.logDetail("GRD001", 1L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(chatRelayService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 3개 API 모두 403")
    void admin_거부() {
        assertThatThrownBy(() -> controller.send("ADM001", REQUEST)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.logs("ADM001")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.logDetail("ADM001", 1L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(chatRelayService);
    }

    @Test
    @DisplayName("요청 DTO에 userId 필드가 없고, 본문에 userId가 와도 역직렬화에서 버려진다")
    void 요청에_userId_없음() throws Exception {
        assertThat(Arrays.stream(ChatRelayRequest.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("userId");

        ChatRelayRequest parsed = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue("{\"message\":\"안녕\",\"userId\":\"VICTIM\"}", ChatRelayRequest.class);
        assertThat(parsed.message()).isEqualTo("안녕");
    }
}
