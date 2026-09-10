package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.ConnectionStatus;

import java.time.OffsetDateTime;

/** 강제 연결 결과. 화면이 목록을 다시 부르지 않고 그 자리에 행을 추가할 수 있도록 돌려준다. */
@Schema(description = "강제 연결 결과")
public record AdminConnectionResponse(

        @Schema(description = "연결 ID", example = "42")
        Long connectionId,

        @Schema(description = "보호자 회원 ID", example = "EE81BF")
        String guardianId,

        @Schema(description = "보호자 이름", example = "홍길동")
        String guardianName,

        @Schema(description = "피보호자 회원 ID", example = "C82D3E")
        String wardId,

        @Schema(description = "피보호자 이름", example = "박민수")
        String wardName,

        @Schema(description = "연결 상태", example = "ACTIVE")
        ConnectionStatus status,

        @Schema(description = "연결 성립 시각")
        OffsetDateTime connectedAt
) {
    public static AdminConnectionResponse of(Connection connection, User guardian, User ward) {
        return new AdminConnectionResponse(
                connection.getId(),
                guardian.getId(), guardian.getName(),
                ward.getId(), ward.getName(),
                connection.getStatus(),
                connection.getConnectedAt());
    }
}
