package kr.silverbridge.main.domain.inquiry.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.enums.InquiryCategory;
import lombok.Getter;

@Schema(description = "문의 작성 요청")
@Getter
public class InquiryCreateRequest {

    @Schema(description = "문의 카테고리", example = "SERVICE",
            allowableValues = {"ANOMALY", "HOSPITAL", "ACCOUNT", "SERVICE", "ETC"})
    @NotNull(message = "카테고리를 선택해주세요.")
    private InquiryCategory category;

    @Schema(description = "제목", example = "이상감지 알림이 오지 않아요")
    @NotBlank(message = "제목을 입력해주세요.")
    @Size(max = 100, message = "제목은 최대 100자입니다.")
    private String title;

    @Schema(description = "내용", example = "어제부터 피보호자 이상감지 알림이 전혀 오지 않습니다.")
    @NotBlank(message = "내용을 입력해주세요.")
    @Size(max = 2000, message = "내용은 최대 2000자입니다.")
    private String content;
}
