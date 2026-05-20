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

    @Schema(description = "피보호자와의 관계 (예: 아들, 딸, 며느리, 사위, 손자, 손녀, 기타)", example = "아들")
    @NotBlank(message = "피보호자와의 관계를 선택해주세요.")
    @Size(max = 10, message = "관계는 최대 10자입니다.")
    private String relation;
}
