package kr.silverbridge.main.domain.notification.dto;

import java.util.Arrays;
import java.util.List;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("알림 설정 변경 DTO 검증 (USER-G09)")
class NotificationSettingUpdateRequestValidationTest {

    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("리스트 요소가 null이면 제약 위반(400)")
    void 요소_null_거부() {
        var r = new NotificationSettingUpdateRequest(Arrays.asList((NotificationSettingUpdateRequest.ChannelSettingUpdate) null));
        assertThat(validator.validate(r)).isNotEmpty();
    }

    @Test
    @DisplayName("요소 내부 필드 null은 여전히 위반")
    void 요소_필드_null_거부() {
        var r = new NotificationSettingUpdateRequest(List.of(new NotificationSettingUpdateRequest.ChannelSettingUpdate(null, true)));
        assertThat(validator.validate(r)).isNotEmpty();
    }
}
