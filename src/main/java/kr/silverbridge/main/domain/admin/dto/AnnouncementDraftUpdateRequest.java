package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Schema(description = "공지 임시저장 수정 요청")
@Getter
public class AnnouncementDraftUpdateRequest {

    @Schema(description = "수정할 제목 (작성 중이라 비어 있을 수 있음)", example = "서비스 점검 안내 (수정)")
    @Size(max = 200, message = "제목은 200자 이내로 입력해주세요.")
    private String title;

    @Schema(description = "수정할 내용 (작성 중이라 비어 있을 수 있음)", example = "점검 시간이 변경되었습니다.")
    @Size(max = 5000, message = "내용은 5000자 이내로 입력해주세요.")
    private String content;
}
