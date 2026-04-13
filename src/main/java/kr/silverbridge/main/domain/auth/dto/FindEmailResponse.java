package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "아이디(이메일) 찾기 응답")
public class FindEmailResponse {

    @Schema(
            description = "일반(LOCAL) 계정의 마스킹된 이메일. 일반 계정이 없으면 null",
            example = "us**@example.com",
            nullable = true
    )
    private String maskedEmail;

    @Schema(
            description = "카카오(KAKAO) 계정 존재 여부. true이면 '카카오 계정이 존재합니다' 표시",
            example = "false"
    )
    private boolean hasKakaoAccount;
}
