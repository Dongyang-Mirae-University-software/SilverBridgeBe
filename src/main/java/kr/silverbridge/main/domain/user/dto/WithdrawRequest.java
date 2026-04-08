package kr.silverbridge.main.domain.user.dto;

import lombok.Getter;

@Getter
public class WithdrawRequest {

    // 일반 로그인 사용자: 필수 / 소셜 로그인 사용자: null 허용
    private String password;
}
