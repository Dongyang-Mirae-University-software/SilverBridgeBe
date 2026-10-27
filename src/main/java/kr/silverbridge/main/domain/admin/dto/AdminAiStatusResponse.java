package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 관리자 대시보드 - AI 서비스 상태(2026-10-27).
 *
 * <p>BE가 실제로 확인할 수 있는 서비스만 담는다. 디자인의 "AI 안부 말벗 · 정서 상태 분석"은 BE에 없는 서비스라
 * 칸을 만들지 않았다 - 확인하지 않은 서비스를 "정상"으로 보이면 거짓이 된다.</p>
 */
@Schema(description = "관리자 대시보드 - AI 서비스 상태")
public record AdminAiStatusResponse(

        @Schema(description = """
                이상감지(영상 분석) - 백엔드의 AI WebSocket 구독 연결 상태.

                OK = 구독 연결됨, DISCONNECTED = 구독이 끊김(이상감지 알림이 나가지 않는 상태).
                안전 현황의 aiConnected와 같은 값입니다.
                """)
        Item anomalyDetection,

        @Schema(description = """
                AI 의료 챗봇 - 챗봇을 서빙하는 AI 서버의 GET /health 응답.

                OK = AI 서버가 정상 응답, null = 확인 불가(시간 초과 5초·통신 오류·서버 오류·설정 없음).
                AI에 챗봇 전용 상태 경로가 없어 **AI 서버 프로세스 응답까지만** 확인합니다(모델 응답 성공은 보장하지 않음).
                확인에 실패해도 "중단"으로 단정하지 않으므로 DISCONNECTED는 내려가지 않습니다.
                """)
        Item chatbot,

        @Schema(description = "이 응답을 만든 시각(KST, 오프셋 포함)", example = "2026-10-27T14:05:00+09:00")
        OffsetDateTime generatedAt
) {

    @Schema(description = "서비스 1건의 상태")
    public record Item(
            @Schema(description = "OK 정상 · DISCONNECTED 연결 끊김 · null 확인 불가", nullable = true, example = "OK")
            AiServiceState status,

            @Schema(description = "확인한 시각(KST, 오프셋 포함). 서버 캐시가 없어 요청 때마다 새로 확인합니다",
                    example = "2026-10-27T14:05:00+09:00")
            OffsetDateTime checkedAt
    ) {
    }
}
