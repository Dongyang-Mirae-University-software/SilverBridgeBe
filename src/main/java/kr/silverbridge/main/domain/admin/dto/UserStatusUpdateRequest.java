package kr.silverbridge.main.domain.admin.dto;

import jakarta.validation.constraints.NotNull;
import kr.silverbridge.main.global.enums.Status;
import lombok.Getter;

@Getter
public class UserStatusUpdateRequest {

    @NotNull(message = "변경할 상태를 입력해주세요.")
    private Status status;
}
