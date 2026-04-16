package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Schema(description = "공지 수정 요청")
@Getter
public class AnnouncementUpdateRequest {

    @Schema(description = "수정할 제목", example = "서비스 점검 안내 (수정)")
    @NotBlank(message = "제목을 입력해주세요.")
    @Size(max = 200, message = "제목은 200자 이내로 입력해주세요.")
    private String title;

    @Schema(description = "수정할 내용", example = "점검 시간이 변경되었습니다. 오전 3시부터 5시까지입니다.")
    @NotBlank(message = "내용을 입력해주세요.")
    @Size(max = 5000, message = "내용은 5000자 이내로 입력해주세요.")
    private String content;
}
