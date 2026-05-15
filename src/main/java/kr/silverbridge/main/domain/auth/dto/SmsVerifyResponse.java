package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "SMS 인증 확인 응답. 회원가입·프로필 수정 요청 시 함께 전달해야 하는 verificationNonce를 반환한다.")
public class SmsVerifyResponse {

    @Schema(description = "SMS 인증 세션 식별자. 회원가입(POST /api/auth/signup 또는 /api/auth/signup/kakao) 또는 전화번호 변경(PUT /api/user/me/update) 요청의 verificationNonce 필드에 그대로 전달.",
            example = "550e8400-e29b-41d4-a716-446655440000")
    private String verificationNonce;
}