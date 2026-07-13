package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 카메라 부분 수정 요청. 전달한 필드만 갱신한다(null은 미변경).
 */
@Schema(description = "카메라 수정 요청 (부분 수정 — null 필드는 미변경)")
public record CameraUpdateRequest(

        @Schema(description = "설치 위치(방 이름)", example = "안방", nullable = true)
        @Size(max = 30, message = "설치 위치는 최대 30자입니다.")
        String label,

        @Schema(description = "사용/중지 토글", nullable = true)
        Boolean isActive
) {}
