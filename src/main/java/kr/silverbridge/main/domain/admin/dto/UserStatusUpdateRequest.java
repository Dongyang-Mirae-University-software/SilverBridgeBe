package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.silverbridge.main.global.enums.Status;
import lombok.Getter;

@Getter
@Schema(description = "사용자 상태 변경 요청")
public class UserStatusUpdateRequest {

    @Schema(description = "변경할 상태. ACTIVE: 활성화, INACTIVE: 비활성화 (로그인 차단)", example = "INACTIVE", allowableValues = {"ACTIVE", "INACTIVE"})
    @NotNull(message = "변경할 상태를 입력해주세요.")
    private Status status;
}
