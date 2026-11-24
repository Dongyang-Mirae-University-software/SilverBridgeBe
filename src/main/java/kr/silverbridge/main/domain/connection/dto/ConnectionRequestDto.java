package kr.silverbridge.main.domain.connection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.global.validation.VisibleText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Schema(description = "페어링 요청")
@Getter
public class ConnectionRequestDto {

    @Schema(description = "연결할 상대방 ID (6자리)", example = "AB1234")
    @NotBlank(message = "상대방 ID를 입력해주세요.")
    @Size(min = 6, max = 6, message = "사용자 ID는 6자리입니다.")
    private String targetId;

    @Schema(description = "어르신과의 관계 (예: 아들, 딸, 며느리, 사위, 손자, 손녀, 기타)", example = "아들")
    @NotBlank(message = "어르신과의 관계를 선택해주세요.")
    // @NotBlank는 U+0020 이하만 공백으로 본다 - 제로폭 문자·NBSP·전각공백만 있는 값도 막는다(CONN-G14)
    @VisibleText(message = "어르신과의 관계를 선택해주세요.")
    @Size(max = 10, message = "관계는 최대 10자입니다.")
    private String relation;

    // relation 과 방향이 반대다 - 어르신이 보호자에게 누구인지. 보호자 화면에서 어르신 이름 옆에 보인다.
    // 화면 업데이트 전 요청도 받도록 선택값이다(비우면 화면에 "어르신"으로 보임).
    @Schema(description = "보호자가 부르는 어르신 호칭 - 어르신이 보호자에게 누구인지 (예: 엄마, 아빠, 할머니, 할아버지, 외할머니, "
            + "외할아버지, 삼촌, 이모, 고모, 남편, 아내, 기타). relation(보호자가 어르신에게 누구인지)과 방향이 반대다. 선택값",
            example = "엄마", nullable = true)
    @VisibleText(message = "어르신 호칭을 다시 선택해주세요.")
    @Size(max = 10, message = "어르신 호칭은 최대 10자입니다.")
    private String wardRelation;
}
