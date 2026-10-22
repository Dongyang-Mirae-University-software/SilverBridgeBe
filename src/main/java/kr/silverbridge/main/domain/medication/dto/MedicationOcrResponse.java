package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;

import java.util.List;

/**
 * 약 봉투 OCR 결과 - <b>등록 초안</b>이다(2026-10-22). 저장하지 않으며, 보호자가 확인·수정한 뒤 기존 약 추가 API로 등록한다.
 *
 * <p>AI의 {@code reason}(자유 문장)과 성분명은 내리지 않는다 - 자유 문장에는 사진 속 개인정보가 섞일 수 있고,
 * 성분은 측정에서 확신 high로 틀렸다. 환자·병원·약국·금액 칸은 처음부터 없다.</p>
 *
 * @param readable 약 봉투로 읽혔는지. false면 {@code drugs}는 빈 배열이다
 */
@Schema(description = "약 봉투 OCR 결과(등록 초안)")
public record MedicationOcrResponse(
        @Schema(description = "약 봉투로 읽혔는지. false면 drugs는 빈 배열") boolean readable,
        @Schema(description = "읽은 약 목록(최대 20개)") List<Drug> drugs
) {

    /**
     * @param perDose     1회 복용량(정). 반 알은 0.5 - 등록 API의 {@code doseAmount}(0.5 단위, V62)에 그대로 넣는다
     * @param slots       봉투에 표시된 복용 시간대. 표시가 없으면 빈 배열(지어내지 않는다)
     * @param confidence  {@code high}·{@code low}. 화면 강조용일 뿐 high라도 확인을 건너뛰지 않는다
     */
    @Schema(description = "읽은 약 1종")
    public record Drug(
            @Schema(description = "약 이름(최대 100자). 1글자 오독이 있을 수 있어 보호자 확인 필수") String name,
            @Schema(description = "1회 복용량(정, 반 알은 0.5). 읽지 못하면 null", example = "0.5") Double perDose,
            @Schema(description = "하루 복용 횟수. 읽지 못하면 null") Integer timesPerDay,
            @Schema(description = "복용 일수. 읽지 못하면 null") Integer days,
            @Schema(description = "복용 시간대. 표시가 없으면 빈 배열") List<MedicationTimeSlot> slots,
            @Schema(description = "복용 안내(예: 식후 30분, 최대 50자). 없으면 null") String timing,
            @Schema(description = "high 또는 low. 알 수 없으면 null", allowableValues = {"high", "low"}) String confidence
    ) {
    }
}
