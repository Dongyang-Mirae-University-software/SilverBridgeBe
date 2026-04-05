package kr.gosky.sso.domain.admin.dto;

import jakarta.validation.constraints.NotNull;
import kr.gosky.sso.global.enums.Status;
import lombok.Getter;

@Getter
public class UserStatusUpdateRequest {

    @NotNull(message = "변경할 상태를 입력해주세요.")
    private Status status;
}
