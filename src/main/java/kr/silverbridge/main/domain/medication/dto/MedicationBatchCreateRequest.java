package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.domain.medication.entity.DoseAmounts;
import kr.silverbridge.main.global.validation.NoControlChars;
import kr.silverbridge.main.global.validation.VisibleText;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 약 일괄 등록 요청(보호자 전용, 2026-10-22). 약 목록 × 복용 시각 목록만큼 약을 만든다(예: 6 × 2 = 12건).
 * 이름·복용량·메모 검증은 단건 등록({@link MedicationCreateRequest})과 같다. 시간대는 서버가 시각으로 정한다.
 */
@Schema(description = "약 일괄 등록 요청 - drugs × times 만큼 등록, 전부 성공 또는 전부 실패")
public record MedicationBatchCreateRequest(

        @NotNull(message = "약 목록은 필수입니다.")
        @Size(min = 1, max = 20, message = "약은 1~20개까지 한 번에 등록할 수 있습니다.")
        @Schema(description = "약 목록(1~20개, 같은 이름 중복 불가)")
        List<@NotNull(message = "약 항목이 비어 있습니다.") @Valid Drug> drugs,

        @NotNull(message = "복용 시각 목록은 필수입니다.")
        @Size(min = 1, max = 6, message = "복용 시각은 1~6개까지 지정할 수 있습니다.")
        @Schema(description = "드시는 시각 목록(1~6개, 같은 시각 중복 불가)")
        List<@NotNull(message = "복용 시각 항목이 비어 있습니다.") @Valid Time> times,

        @NoControlChars(allowLineBreaks = true)
        @Size(max = 100, message = "메모는 100자를 초과할 수 없습니다.")
        @Schema(description = "모든 약에 붙는 복용 안내 메모 (선택, 공백만 보내면 메모 없음)", example = "식후 30분")
        String memo
) {

    @Schema(description = "약 1종")
    public record Drug(
            @NotBlank(message = "약 이름은 필수입니다.")
            @VisibleText(message = "약 이름을 입력해주세요.")
            @NoControlChars
            @Size(max = 100, message = "약 이름은 100자를 초과할 수 없습니다.")
            @Schema(description = "약 이름", example = "타이레놀정")
            String name,

            @DoseAmount
            @Schema(description = "복용량(정). 0.5 단위(반 알 = 0.5), 0.5~99, 생략 시 1", example = "0.5")
            BigDecimal doseAmount
    ) {
        public BigDecimal resolveDoseAmount() {
            return doseAmount != null ? doseAmount : DoseAmounts.DEFAULT;
        }
    }

    @Schema(description = "드시는 시각 1개")
    public record Time(
            @NotNull(message = "복용 시각은 필수입니다.")
            @Schema(description = "복용 시각. 시간대는 서버가 정한다(04~10시 아침 / 11~15시 점심 / 16~20시 저녁 / 그 외 취침 전)",
                    example = "08:00:00", type = "string")
            LocalTime doseTime
    ) {
        /** 초 아래는 버린다(2026-10-22 점검 L-3) - 나노초만 다른 두 시각이 중복 검사를 지나 같은 시각 2건이 되지 않게. */
        public LocalTime resolveDoseTime() {
            return doseTime.truncatedTo(ChronoUnit.SECONDS);
        }
    }
}
