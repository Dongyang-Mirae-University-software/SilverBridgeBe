package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 회원관리 목록의 한 행.
 *
 * <p>연결은 <b>요약만</b> 싣는다({@code connectionCount} + 첫 상대). 목록에서 전체 연결을 펼치면 응답이 커지고,
 * 화면도 "박민수 외 2명"까지만 보여준다. 전체 목록은 상세 조회가 담당한다.</p>
 */
@Schema(description = "회원관리 목록 항목")
public record AdminUserListItem(

        @Schema(description = "회원 ID (6자리)", example = "EE81BF")
        String userId,

        @Schema(description = "가입 방식", example = "KAKAO", allowableValues = {"LOCAL", "KAKAO"})
        Provider provider,

        @Schema(description = "이메일", example = "hong@example.com")
        String email,

        @Schema(description = "이름", example = "홍길동")
        String name,

        @Schema(description = "전화번호", example = "010-1234-5678")
        String phone,

        @Schema(description = "역할", example = "GUARDIAN", allowableValues = {"WARD", "GUARDIAN", "ADMIN"})
        Role role,

        @Schema(description = "계정 상태 (ACTIVE=이용 중, RESTRICTED=이용 제한)",
                example = "ACTIVE", allowableValues = {"ACTIVE", "RESTRICTED"})
        Status status,

        @Schema(description = "연결 상태. 관리자 계정은 null", nullable = true, example = "CONNECTED")
        AdminUserConnectionState connectionState,

        @Schema(description = "연결(ACTIVE+PENDING) 건수", example = "3")
        int connectionCount,

        @Schema(description = "대표로 보여줄 연결 상대 1명. 연결이 없으면 null", nullable = true)
        AdminUserConnectionItem firstConnection,

        @Schema(description = "가입 일시")
        OffsetDateTime createdAt
) {
    public static AdminUserListItem of(User user, List<AdminUserConnectionItem> connections) {
        boolean hasConnectionAxis = user.getRole() != Role.ADMIN;
        return new AdminUserListItem(
                user.getId(),
                user.getProvider(),
                user.getEmail(),
                user.getName(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                hasConnectionAxis ? AdminUserConnectionStates.of(connections) : null,
                connections.size(),
                connections.isEmpty() ? null : connections.get(0),
                user.getCreatedAt()
        );
    }
}
