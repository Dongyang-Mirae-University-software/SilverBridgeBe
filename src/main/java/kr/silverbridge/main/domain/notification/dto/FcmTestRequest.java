package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "FCM 발송 테스트 요청 (개발용). token 또는 userId 중 하나는 필수.")
public class FcmTestRequest {

    @Schema(description = "FCM 디바이스 토큰. 지정 시 DB 조회 없이 해당 토큰으로 직접 발송",
            example = "fGxT...abcd",
            nullable = true)
    private String token;

    @Schema(description = "대상 사용자 ID. 지정 시 해당 사용자의 등록된 모든 FCM 토큰으로 발송",
            example = "user-uuid-1234",
            nullable = true)
    private String userId;

    @Schema(description = "푸시 알림 제목", example = "테스트 알림")
    private String title;

    @Schema(description = "푸시 알림 본문", example = "FCM 발송 경로 확인용 메시지입니다.")
    private String body;
}
