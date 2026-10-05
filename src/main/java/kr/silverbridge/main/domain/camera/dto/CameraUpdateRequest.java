package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.validation.NoControlChars;
import kr.silverbridge.main.global.validation.VisibleText;

/**
 * 카메라 부분 수정 요청. 전달한 필드만 갱신한다(null은 미변경).
 *
 * <p>{@code label}을 보냈다면 등록과 같이 비어 있으면 안 된다 - 방 이름은 화재 알림 문구의 위치
 * ("…님 댁 <b>거실</b>에서")로 쓰여, 빈 값이면 위치가 빠진 알림이 나간다(ANOM-G12).
 * 빈 문자열·공백·제로폭 문자만 있는 값은 400이다.</p>
 */
@Schema(description = "카메라 수정 요청 (부분 수정 — null 필드는 미변경)")
public record CameraUpdateRequest(

        @Schema(description = "설치 위치(방). 보낼 경우 거실·침실·주방·화장실·현관·베란다·작은방·작은방2 중 하나, "
                + "다른 카메라가 쓰는 방은 불가", example = "침실", nullable = true)
        @VisibleText(message = "설치 위치(방 이름)를 입력해주세요.")
        @NoControlChars
        @Size(max = 30, message = "설치 위치는 최대 30자입니다.")
        String label,

        @Schema(description = "사용/중지 토글", nullable = true)
        Boolean isActive
) {}
