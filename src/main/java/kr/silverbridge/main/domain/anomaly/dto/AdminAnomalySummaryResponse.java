package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.global.enums.DetectedType;

import java.util.List;

/**
 * 관리자 이상감지 로그 화면의 집계(유형 탭 건수 · 응답률 · 판정별 현황 · AI 신뢰도).
 *
 * <p><b>유형 탭 건수({@code byType})만 유형 필터를 무시</b>한다 - 탭을 골라도 탭 옆 숫자는 그대로여야 한다.
 * 나머지({@code total}·{@code review}·{@code responseRate}·{@code accuracy})는 고른 유형으로 좁혀 계산한다.</p>
 *
 * <p><b>모르는 값을 0으로 채우지 않는다</b> - 분모가 0이면 비율은 null이다(대시보드 2026-09-02 규칙).
 * 오탐 비율은 응답률과 함께 내려간다(오탐만 단독 노출 금지).</p>
 */
@Schema(description = "관리자 이상감지 로그 집계")
public record AdminAnomalySummaryResponse(

        @Schema(description = "적용된 기간", example = "THIS_WEEK")
        AdminAnomalyPeriod period,

        @Schema(description = "상황 수 (유형 필터 적용)", example = "52")
        long total,

        @Schema(description = "유형별 상황 수 - 유형 탭용. 유형 필터를 무시하고, 집계된 유형만 담는다(0건 유형은 항목 없음). 건수 내림차순")
        List<TypeCount> byType,

        @Schema(description = "판정 상태별 상황 수 (유형 필터 적용)")
        ReviewCount review,

        @Schema(description = "사용자 응답률 0.0~1.0 = (total - pending) / total. total이 0이면 null", example = "0.7692")
        Double responseRate,

        @Schema(description = "AI 신뢰도 (유형 필터 적용)")
        Accuracy accuracy
) {

    @Schema(description = "유형별 건수")
    public record TypeCount(
            @Schema(description = "감지 유형", example = "FIRE", allowableValues = {"FIRE", "FALL", "WEAPON"})
            DetectedType type,
            @Schema(description = "표시 문구", example = "화재")
            String label,
            @Schema(description = "상황 수", example = "13")
            long count
    ) {
    }

    /**
     * 판정 상태별 건수. 동수(conflicted)를 미판정이나 한쪽에 합치지 않는다 - 보호자끼리 아직 합의하지 않았다는
     * 정보다(응답한 보호자 다수결, 2026-09-21).
     */
    @Schema(description = "판정 상태별 건수")
    public record ReviewCount(
            @Schema(description = "미판정 - 아직 아무 보호자도 응답하지 않음", example = "12") long pending,
            @Schema(description = "위험 - 응답한 보호자 다수가 실제 위험", example = "16") long real,
            @Schema(description = "오탐 - 응답한 보호자 다수가 오탐", example = "24") long falseAlarm,
            @Schema(description = "동수 - 응답이 동수로 갈려 보호자 재확인 대기", example = "0") long conflicted
    ) {
    }

    /**
     * AI 신뢰도 = 위험 / (위험 + 오탐). 판정이 난 건 중 AI 경보가 실제 위험이었던 비율이다.
     *
     * <p>미판정·동수는 분모에서 뺀다 - 아직 모르는 건을 어느 쪽으로도 세지 않는다. 그래서 {@code rate}가 곧 위험 비율이고
     * {@code falseAlarmRate}는 {@code 1 - rate}다. AI가 매 프레임 보내는 {@code confidence}(얼마나 불처럼 보이는가)와는
     * 다른 값이다.</p>
     */
    @Schema(description = "AI 신뢰도 = 위험 / (위험 + 오탐)")
    public record Accuracy(
            @Schema(description = "AI 신뢰도(= 위험 비율) 0.0~1.0. basis가 0이면 null", example = "0.4")
            Double rate,
            @Schema(description = "오탐 비율 0.0~1.0 (= 1 - rate). basis가 0이면 null", example = "0.6")
            Double falseAlarmRate,
            @Schema(description = "분모 = 위험 + 오탐 건수", example = "40")
            long basis
    ) {
    }
}
