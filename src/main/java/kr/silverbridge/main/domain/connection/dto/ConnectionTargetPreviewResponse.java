package kr.silverbridge.main.domain.connection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.util.MaskingUtil;

/**
 * 연결 요청 전 확인용 - 입력한 ID가 의도한 피보호자가 맞는지 보호자가 확인하는 단계(CONN-G06).
 *
 * <p><b>가린 이름만</b> 싣는다. 수락 전 상대의 연락처·주소·이메일·프로필은 보호자에게 노출 대상이 아니다
 * (수락 전 마스킹 정책 - {@link ConnectionResponse}는 ACTIVE일 때만 연락처를 채운다). 필드를 늘리지 말 것.</p>
 */
@Schema(description = "연결 요청 전 상대 확인(가린 이름)")
public record ConnectionTargetPreviewResponse(
        @Schema(description = "입력한 상대 ID(그대로 되돌려 준다)", example = "AB1234")
        String targetId,

        @Schema(description = "가린 이름 - 첫 글자와 마지막 글자만 보인다", example = "홍*동")
        String maskedName
) {
    public static ConnectionTargetPreviewResponse of(User target) {
        return new ConnectionTargetPreviewResponse(target.getId(), MaskingUtil.maskName(target.getName()));
    }
}
