package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.silverbridge.main.global.enums.Role;
import lombok.Getter;

@Getter
@Schema(description = "사용자 역할 변경 요청")
public class UserRoleUpdateRequest {

    @Schema(description = "변경할 역할. WARD: 피보호자, GUARDIAN: 보호자", example = "GUARDIAN", allowableValues = {"WARD", "GUARDIAN"})
    @NotNull(message = "역할을 선택해주세요.")
    private Role role;
}
