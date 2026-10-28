package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 그 요일만 기본 진료 시간을 덮어쓰는 진료 시간(예: 토요일 09:00~13:00 반일, 2026-10-28). 요청·응답이 같은 모양이다. */
@Schema(description = "요일별 진료 시간")
public record AdminHospitalWeekdayHours(
        @Schema(description = "요일(0=일 … 6=토)", example = "6")
        @NotNull @Min(0) @Max(6)
        Integer weekday,
        @Schema(description = "진료 시작(HH:mm)", example = "09:00")
        @NotBlank @Pattern(regexp = AdminHospitalRules.TIME_REGEX, message = AdminHospitalRules.TIME_MESSAGE)
        String openTime,
        @Schema(description = "진료 종료(HH:mm, 시작보다 늦어야 함)", example = "13:00")
        @NotBlank @Pattern(regexp = AdminHospitalRules.TIME_REGEX, message = AdminHospitalRules.TIME_MESSAGE)
        String closeTime
) {
}
