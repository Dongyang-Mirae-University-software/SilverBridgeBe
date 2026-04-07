package kr.silverbridge.main.domain.auth.dto;

import jakarta.validation.constraints.NotNull;
import kr.silverbridge.main.global.enums.Role;
import lombok.Getter;

@Getter
public class KakaoRoleRequest {

    // WARD(피보호자) 또는 GUARDIAN(보호자) 중 하나 필수
    @NotNull(message = "역할을 선택해주세요. (WARD: 피보호자, GUARDIAN: 보호자)")
    private Role role;
}
