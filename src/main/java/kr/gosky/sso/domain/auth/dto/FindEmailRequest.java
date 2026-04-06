package kr.gosky.sso.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class FindEmailRequest {

    @NotBlank(message = "이름을 입력해주세요.")
    private String name;

    @NotBlank(message = "전화번호를 입력해주세요.")
    private String phone;
}
