package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;

/**
 * 회원 한 명이 맺고 있는 연결 하나(상대방 기준).
 *
 * <p><b>{@code relation}의 방향에 주의할 것.</b> 이 값은 연결을 요청한 <b>보호자가 피보호자에게 어떤 사람인지</b>를
 * 가리킨다("아들", "며느리"). 저장은 한 방향뿐이라 반대 라벨(피보호자가 보호자에게 무엇인지)은 존재하지 않는다.
 * 화면에서 "{상대이름} ({relation})"으로 붙이면 relation이 상대를 가리키는 것처럼 읽히므로,
 * 이 값이 누구를 가리키는지 문장으로 풀어 쓰는 편이 정확하다("이 회원은 박민수님의 아들").</p>
 *
 * @param counterpartRole 상대방의 역할. 이 값이 GUARDIAN이면 relation은 상대방을, WARD이면 조회 대상 회원을 가리킨다
 */
@Schema(description = "연결된 상대방 한 명")
public record AdminUserConnectionItem(

        @Schema(description = "상대방 회원 ID", example = "C82D3E")
        String counterpartId,

        @Schema(description = "상대방 이름", example = "박민수")
        String counterpartName,

        @Schema(description = "상대방 역할", example = "WARD", allowableValues = {"WARD", "GUARDIAN"})
        Role counterpartRole,

        @Schema(description = "보호자가 입력한 관계. 보호자를 가리키는 라벨이며, 입력 전 데이터는 null",
                nullable = true, example = "아들")
        String relation,

        @Schema(description = "연결 상태", example = "ACTIVE", allowableValues = {"ACTIVE", "PENDING"})
        ConnectionStatus status
) {}
