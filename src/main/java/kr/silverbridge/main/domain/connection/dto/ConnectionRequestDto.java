package kr.silverbridge.main.domain.connection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Schema(description = "페어링 요청")
@Getter
public class ConnectionRequestDto {

    @Schema(description = "연결할 상대방 ID (6자리)", example = "AB1234")
    @NotBlank(message = "상대방 ID를 입력해주세요.")
    @Size(min = 6, max = 6, message = "사용자 ID는 6자리입니다.")
    private String targetId;
}