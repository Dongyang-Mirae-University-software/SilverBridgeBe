package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.validation.NoControlChars;
import kr.silverbridge.main.global.validation.VisibleText;

/** 진료과 추가·이름 변경(2026-10-26). 같은 병원의 사용 중인 진료과와 이름이 같으면 409. */
@Schema(description = "진료과")
public record AdminHospitalDepartmentRequest(
        @Schema(description = "진료과 이름(최대 30자)", example = "재활의학과")
        @NotBlank @VisibleText @NoControlChars @Size(max = 30)
        String name
) {
}
