package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Schema(description = "FCM 토큰 등록 요청")
@Getter
public class FcmTokenRegisterRequest {

    @Schema(description = "FCM 디바이스 토큰", example = "dGhpcyBpcyBhIHNhbXBsZSB0b2tlbg...")
    @NotBlank(message = "FCM 토큰을 입력해주세요.")
    private String token;

    @Schema(description = "플랫폼", allowableValues = {"ANDROID", "IOS", "WEB"}, example = "ANDROID")
    @NotBlank(message = "플랫폼을 입력해주세요.")
    @Pattern(regexp = "ANDROID|IOS|WEB", message = "플랫폼은 ANDROID, IOS, WEB 중 하나여야 합니다.")
    private String platform;
}