package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 카메라 등록/재등록 요청.
 *
 * <p>{@code deviceId}는 최초 등록 시 생략(null) — 백엔드가 발급해 응답으로 돌려주며, FE는 이를 localStorage에 저장했다가
 * 같은 기기 재등록 시 다시 보낸다(멱등: 기존 SessionID 재사용). 본인 소유가 아닌 값이면 무시하고 신규 발급한다.</p>
 */
@Schema(description = "카메라 등록 요청")
public record CameraRegisterRequest(

        @Schema(description = "설치 위치(방 이름)", example = "거실")
        @NotBlank(message = "설치 위치(방 이름)를 입력해주세요.")
        @Size(max = 30, message = "설치 위치는 최대 30자입니다.")
        String label,

        @Schema(description = "기기 토큰(FE localStorage 보관값). 최초 등록 시 생략", nullable = true)
        @Size(max = 64, message = "기기 토큰 형식이 올바르지 않습니다.")
        String deviceId
) {}
