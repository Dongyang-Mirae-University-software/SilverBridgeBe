package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 강제 연결 요청.
 *
 * <p>고객센터 문의를 받아 관리자가 대신 연결해 주는 경로다 - 시니어가 수락 버튼을 누르지 못해
 * 가족이 연결하지 못하는 경우가 실제로 있다.</p>
 *
 * <p>관계(relation)는 받지 않는다. 관리자는 두 사람의 가족 관계를 알 수 없고, 그 값은 보호자가
 * 직접 요청할 때만 입력된다.</p>
 */
@Schema(description = "강제 연결 요청")
public record AdminForceConnectRequest(

        @Schema(description = "보호자 회원 ID (6자리)", example = "EE81BF")
        @NotBlank(message = "보호자 ID를 입력해주세요.")
        @Size(min = 6, max = 6, message = "사용자 ID는 6자리입니다.")
        String guardianId,

        @Schema(description = "피보호자 회원 ID (6자리)", example = "C82D3E")
        @NotBlank(message = "피보호자 ID를 입력해주세요.")
        @Size(min = 6, max = 6, message = "사용자 ID는 6자리입니다.")
        String wardId
) {}
