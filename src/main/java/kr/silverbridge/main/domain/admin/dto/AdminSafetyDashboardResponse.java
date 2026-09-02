package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.global.enums.DetectedType;

import java.util.List;

/**
 * 관리자 대시보드 - 안전 현황 탭.
 *
 * <p>데이터가 0건이어도 <b>키는 항상 존재</b>한다(0 또는 빈 배열). 프론트가 존재 여부를 분기할 필요가 없다.
 * 다만 {@code streamingCameras}·{@code disconnectedCameras}는 예외적으로 {@code null}이 될 수 있는데,
 * 이는 "0대"가 아니라 <b>"알 수 없음"</b>을 뜻한다(아래 설명 참조).</p>
 */
@Schema(description = "관리자 대시보드 - 안전 현황")
public record AdminSafetyDashboardResponse(

        @Schema(description = "AI WebSocket 구독 연결 상태", example = "true")
        boolean aiConnected,

        @Schema(description = "현재 구독 중인 세션 수. AI 미연결이면 0", example = "12")
        int subscribedSessions,

        @Schema(description = "등록된 카메라 대수", example = "29")
        long totalCameras,

        @Schema(description = """
                현재 스트리밍이 잡히는 카메라 대수.

                AI 미연결(aiConnected=false)이면 **null** 입니다 - "0대"가 아니라 "알 수 없음"입니다.
                우리 수신기가 끊긴 것을 현장 카메라가 전멸한 것으로 표시하지 않기 위함입니다.
                """, example = "27", nullable = true)
        Long streamingCameras,

        @Schema(description = "보호 사각지대 신호")
        SafetyEvents safetyEvents,

        @Schema(description = "오늘(KST) 이상감지 - 단위는 상황(incident)")
        TodayAnomaly todayAnomaly
) {

    @Schema(description = "보호 사각지대 신호")
    public record SafetyEvents(

            @Schema(description = """
                    스트리밍이 잡히지 않는 카메라 대수.

                    AI 미연결이면 **null**(알 수 없음)입니다.
                    """, example = "2", nullable = true)
            Long disconnectedCameras,

            @Schema(description = "ACTIVE 연결이 하나도 없는 피보호자 수", example = "3")
            long wardsWithoutGuardian,

            @Schema(description = "카메라를 한 대도 등록하지 않은 피보호자 수", example = "5")
            long wardsWithoutCamera,

            @Schema(description = "오래 방치된 연결 요청 수", example = "1")
            long stalePendingConnections
    ) {
    }

    /**
     * 오늘(KST) 이상감지.
     *
     * <p><b>낙상·흉기는 담지 않는다</b> - AI 모델 미탑재라 항상 0이고, 0을 보여주면 "그 위험은 없었다"로
     * 오독된다. 같은 이유로 {@code byType}에는 <b>실제로 집계된 유형만</b> 들어간다.</p>
     */
    @Schema(description = "오늘(KST) 이상감지 집계")
    public record TodayAnomaly(

            @Schema(description = "오늘 발생한 상황 수", example = "4")
            long total,

            @Schema(description = "유형별 건수. 0건인 유형은 항목 자체가 없습니다")
            List<TypeCount> byType,

            @Schema(description = "판정 상태별 건수")
            ReviewCount review
    ) {
    }

    @Schema(description = "유형별 건수")
    public record TypeCount(
            @Schema(description = "감지 유형", example = "FIRE")
            DetectedType detectedType,

            @Schema(description = "건수", example = "3")
            long count
    ) {
    }

    /**
     * 판정 상태별 건수.
     *
     * <p>네 값을 모두 내리는 이유는 <b>오탐률을 응답률과 함께</b> 보여주기 위해서다. 응답 수는
     * {@code total - pending}이므로 "응답 9건 중 오탐 6건(전체 15건, 응답률 60%)"처럼 분모를 밝힐 수 있다.
     * 오탐 건수만 단독으로 띄우면 분모가 거짓이 되어 관리자가 그 숫자를 믿을 수 없다.</p>
     */
    @Schema(description = "판정 상태별 건수")
    public record ReviewCount(
            @Schema(description = "미응답", example = "2") long pending,
            @Schema(description = "실제 위험", example = "1") long real,
            @Schema(description = "오탐", example = "1") long falseAlarm,
            @Schema(description = "보호자 응답이 엇갈림", example = "0") long conflicted
    ) {
    }
}
