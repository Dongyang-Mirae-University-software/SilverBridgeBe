package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "SMS 인증코드 확인 성공 응답. '이 전화번호가 인증되었음'을 증명하는 verificationNonce를 돌려준다.")
public class SmsVerifyResponse {

    @Schema(description = """
            전화번호 SMS 인증을 통과했음을 증명하는 1회용 식별자(서버 발급 UUID).
            - 어디서 받나: POST /api/auth/signup/sms/verify (코드 확인) 성공 응답의 data.verificationNonce
            - 어디에 쓰나: 바로 다음 단계 요청 body의 verificationNonce 필드에 이 값을 그대로 넣어 전송
              · 일반 회원가입  POST /api/auth/signup
              · 카카오 회원가입 POST /api/auth/signup/kakao
              · 전화번호 변경  PUT  /api/user/me (번호를 바꿀 때만)
            - 수명: 발급 후 10분 이내 사용. 1회 사용 시 즉시 폐기(재사용 불가). 만료/재사용 시 SMS 인증부터 다시.
            - 형식이 UUID일 뿐 사용자가 입력/표시하는 값이 아님 (프론트가 메모리에 들고 있다가 그대로 전달)
            """,
            example = "550e8400-e29b-41d4-a716-446655440000")
    private String verificationNonce;
}