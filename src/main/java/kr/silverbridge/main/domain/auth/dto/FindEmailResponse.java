package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "아이디(이메일) 찾기 응답")
public class FindEmailResponse {

    @Schema(description = "가입된 이메일 (보안을 위해 앞 2자리만 노출, 나머지 마스킹 처리)", example = "us**@example.com")
    private String maskedEmail;
}
