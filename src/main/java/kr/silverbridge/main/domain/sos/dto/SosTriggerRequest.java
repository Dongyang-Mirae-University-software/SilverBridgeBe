package kr.silverbridge.main.domain.sos.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 긴급 SOS 발생 요청. <b>바디 전체가 선택</b>이다 — 바디 없이 호출하면 위치 미상으로 기록된다
 * (프론트가 위치를 보내기 전에도 기존과 동일하게 동작한다).
 *
 * @param location 발생 위치 자유 문구(선택, 100자). 서버는 위치를 추정하지 않고 받은 값을 그대로 보관한다
 */
@Schema(description = "긴급 SOS 발생 요청 (바디 없이 호출 가능)")
public record SosTriggerRequest(

        @Size(max = 100, message = "위치는 100자를 초과할 수 없습니다.")
        @Schema(description = "발생 위치 (선택). 예 \"자택 거실\", \"역삼동 인근\"", example = "자택 거실")
        String location
) {}
