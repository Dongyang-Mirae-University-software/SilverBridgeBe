package kr.silverbridge.main.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "회원 탈퇴 요청")
public class WithdrawRequest {

    @Schema(description = "현재 비밀번호. 일반(LOCAL) 가입자: 필수. 카카오(KAKAO) 가입자: null 허용.", example = "Password1!", nullable = true)
    // 일반 로그인 사용자: 필수 / 소셜 로그인 사용자: null 허용
    private String password;
}
