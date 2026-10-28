package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.validation.NoControlChars;
import kr.silverbridge.main.global.validation.VisibleText;

import java.util.List;

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
        Boolean bookingAvailable,

        @Schema(description = "점심시간 시작(HH:mm, 선택 - 끝과 함께, 진료 시간 안). 빈 문자열이면 지움, null 은 변경 안 함", example = "12:30", nullable = true)
        @Pattern(regexp = AdminHospitalRules.TIME_OR_EMPTY_REGEX, message = AdminHospitalRules.TIME_MESSAGE)
        String breakStart,

        @Schema(description = "점심시간 끝(HH:mm)", example = "13:30", nullable = true)
        @Pattern(regexp = AdminHospitalRules.TIME_OR_EMPTY_REGEX, message = AdminHospitalRules.TIME_MESSAGE)
        String breakEnd,

        @Schema(description = "휴진 요일(0=일 … 6=토, 중복 불가). 빈 배열이면 휴진 없음, null 은 변경 안 함", example = "[0]", nullable = true)
        @Size(max = 7)
        List<@NotNull @Min(0) @Max(6) Integer> closedWeekdays,

        @Schema(description = "요일별 진료 시간(그 요일만 기본 진료 시간을 덮어씀, 요일당 하나). 빈 배열이면 지움, null 은 변경 안 함", nullable = true)
        @Size(max = 7)
        List<@NotNull @Valid AdminHospitalWeekdayHours> weekdayHours
) {
    /** 점심시간·휴진 요일을 건드리지 않는 기존 형태(2026-10-28 이전 호출부 호환). */
    public AdminHospitalUpdateRequest(String name, String region, String address, String phone, String description,
                                      String openTime, String closeTime, Boolean bookingAvailable) {
        this(name, region, address, phone, description, openTime, closeTime, bookingAvailable, null, null, null, null);
    }

    public boolean isEmpty() {
        return name == null && region == null && address == null && phone == null && description == null
                && openTime == null && closeTime == null && bookingAvailable == null
                && breakStart == null && breakEnd == null && closedWeekdays == null && weekdayHours == null;
    }
}
