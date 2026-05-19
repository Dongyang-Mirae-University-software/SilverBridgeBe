package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@AllArgsConstructor
@Schema(description = "아이디(이메일) 찾기 응답")
public class FindEmailResponse {

    @Schema(
            description = "일반(LOCAL) 계정의 마스킹된 이메일 (앞 일부 + *** + 뒤 일부). 일반 계정이 없으면 null",
            example = "yo***ee@naver.com",
            nullable = true
    )
    private String maskedEmail;

    @Schema(
            description = "카카오(KAKAO) 계정 존재 여부. true이면 '카카오 계정이 존재합니다' 표시",
            example = "false"
    )
    private boolean hasKakaoAccount;

    @Schema(
            description = "일반(LOCAL) 계정의 가입일 (yyyy-MM-dd). 화면의 '가입일' 표시에 사용. 일반 계정이 없으면 null",
            example = "2024-08-12",
            nullable = true
    )
    private LocalDate joinedAt;
}
