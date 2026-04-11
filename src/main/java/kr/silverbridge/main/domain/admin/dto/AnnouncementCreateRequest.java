package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Schema(description = "공지 생성 요청")
@Getter
public class AnnouncementCreateRequest {

    @Schema(description = "공지 제목", example = "서비스 점검 안내")
    @NotBlank(message = "제목을 입력해주세요.")
    @Size(max = 200, message = "제목은 200자 이내로 입력해주세요.")
    private String title;

    @Schema(description = "공지 내용", example = "2025년 5월 1일 오전 2시부터 4시까지 서버 점검이 예정되어 있습니다.")
    @NotBlank(message = "내용을 입력해주세요.")
    private String content;
}
