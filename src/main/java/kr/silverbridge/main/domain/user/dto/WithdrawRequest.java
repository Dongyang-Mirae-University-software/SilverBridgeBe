package kr.silverbridge.main.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
@Schema(description = "회원 탈퇴 요청. 일반 사용자는 비밀번호로, 카카오 사용자는 confirmation 문자열로 본인 확인.")
public class WithdrawRequest {

    @Schema(description = "현재 비밀번호. 일반(LOCAL) 가입자: 필수. 카카오(KAKAO) 가입자: null 허용.", example = "Password1!", nullable = true)
    // 일반 로그인 사용자: 필수 / 소셜 로그인 사용자: null 허용. null 은 서비스 분기에서 처리하므로 @Size 만 적용(비정상 길이 입력 방어)
    @Size(max = 100, message = "비밀번호 입력값이 올바르지 않습니다.")
    private String password;

    @Schema(description = "카카오(KAKAO) 가입자 본인 확인용 문자열. 사용자가 화면에서 \"탈퇴\"를 직접 입력해 전달. 일반 가입자는 무시됨.",
            example = "탈퇴", nullable = true)
    @Size(max = 20, message = "탈퇴 확인 문구 입력값이 올바르지 않습니다.")
    private String confirmation;
}
