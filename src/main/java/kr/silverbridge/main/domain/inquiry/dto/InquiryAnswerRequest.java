package kr.silverbridge.main.domain.inquiry.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Schema(description = "문의 답변 요청")
@Getter
public class InquiryAnswerRequest {

    @Schema(description = "답변 내용", example = "확인 결과 알림 설정이 꺼져 있었습니다. 설정 > 알림에서 켜주세요.")
    @NotBlank(message = "답변 내용을 입력해주세요.")
    @Size(max = 2000, message = "답변은 최대 2000자입니다.")
    private String answer;
}
