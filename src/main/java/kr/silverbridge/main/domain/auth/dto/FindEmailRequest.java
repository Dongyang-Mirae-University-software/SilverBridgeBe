package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
@Schema(description = "아이디(이메일) 찾기 요청")
public class FindEmailRequest {

    @Schema(description = "가입 시 입력한 이름 (최대 20자)", example = "홍길동")
    @NotBlank(message = "이름을 입력해주세요.")
    @Size(max = 20, message = "이름은 20자 이하여야 합니다.")
    private String name;

    @Schema(description = "가입 시 입력한 전화번호 (숫자만, 하이픈 없이)", example = "01012345678")
    @NotBlank(message = "전화번호를 입력해주세요.")
    private String phone;
}
