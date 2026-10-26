package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.validation.NoControlChars;
import kr.silverbridge.main.global.validation.VisibleText;

/**
 * 제휴 병원 정보 수정(부분 수정 - null은 변경 안 함, 2026-10-26). 진료과는 진료과 API로 따로 바꾼다.
 * 한 칸도 없으면 400이다.
 */
@Schema(description = "제휴 병원 수정(null = 변경 안 함)")
public record AdminHospitalUpdateRequest(
        @Schema(description = "병원 이름(최대 100자)")
        @VisibleText @NoControlChars @Size(max = 100)
        String name,

        @Schema(description = "지역(최대 30자)")
        @VisibleText @NoControlChars @Size(max = 30)
        String region,

        @Schema(description = "주소(최대 200자)")
        @VisibleText @NoControlChars @Size(max = 200)
        String address,

        @Schema(description = "대표 전화")
        @Pattern(regexp = AdminHospitalRules.PHONE_REGEX, message = AdminHospitalRules.PHONE_MESSAGE)
        String phone,

        @Schema(description = "소개(최대 1000자, 빈 문자열이면 지움)")
        @NoControlChars(allowLineBreaks = true) @Size(max = 1000)
        String description,

        @Schema(description = "진료 시작(HH:mm)")
        @Pattern(regexp = AdminHospitalRules.TIME_REGEX, message = AdminHospitalRules.TIME_MESSAGE)
        String openTime,

        @Schema(description = "진료 종료(HH:mm)")
        @Pattern(regexp = AdminHospitalRules.TIME_REGEX, message = AdminHospitalRules.TIME_MESSAGE)
        String closeTime,

        @Schema(description = "앱 예약 가능 여부")
        Boolean bookingAvailable
) {
    public boolean isEmpty() {
        return name == null && region == null && address == null && phone == null && description == null
                && openTime == null && closeTime == null && bookingAvailable == null;
    }
}
