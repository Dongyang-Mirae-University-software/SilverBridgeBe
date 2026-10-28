package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.validation.NoControlChars;
import kr.silverbridge.main.global.validation.VisibleText;

import java.util.List;

/**
 * 제휴 병원 등록(2026-10-26). 예약 서비스는 모양만 검사하므로(예: {@code 99:99} 통과, 전화 형식 없음) BE가 먼저 거른다.
 * 병원 이메일 칸은 없다(예약 서비스에 저장할 곳이 없다).
 */
@Schema(description = "제휴 병원 등록")
public record AdminHospitalCreateRequest(
        @Schema(description = "병원 이름(최대 100자)", example = "서울튼튼정형외과")
        @NotBlank @VisibleText @NoControlChars @Size(max = 100)
        String name,

        @Schema(description = "지역(최대 30자)", example = "서울 강남구")
        @NotBlank @VisibleText @NoControlChars @Size(max = 30)
        String region,

        @Schema(description = "주소(최대 200자)", example = "서울 강남구 테헤란로 123")
        @NotBlank @VisibleText @NoControlChars @Size(max = 200)
        String address,

        @Schema(description = "대표 전화 - 지역번호형(02-123-4567) 또는 대표번호형(1588-1234), 하이픈 선택", example = "02-2001-0001")
        @NotBlank @Pattern(regexp = AdminHospitalRules.PHONE_REGEX, message = AdminHospitalRules.PHONE_MESSAGE)
        String phone,

        @Schema(description = "소개(선택, 최대 1000자, 줄바꿈 허용)", example = "관절·척추 진료")
        @NoControlChars(allowLineBreaks = true) @Size(max = 1000)
        String description,

        @Schema(description = "진료 시작(HH:mm, 종료보다 빨라야 함)", example = "09:00")
        @NotBlank @Pattern(regexp = AdminHospitalRules.TIME_REGEX, message = AdminHospitalRules.TIME_MESSAGE)
        String openTime,

        @Schema(description = "진료 종료(HH:mm)", example = "18:00")
        @NotBlank @Pattern(regexp = AdminHospitalRules.TIME_REGEX, message = AdminHospitalRules.TIME_MESSAGE)
        String closeTime,

        @Schema(description = "앱 예약 가능 여부(생략 시 true)", example = "true")
        Boolean bookingAvailable,

        // 원소의 보이는 글자·제어문자 검사는 서비스가 한다(@VisibleText·@NoControlChars는 타입 인자에 붙지 않는다)
        @Schema(description = "진료과 이름 1~20개(각 최대 30자, 중복 불가)", example = "[\"정형외과\", \"재활의학과\"]")
        @NotEmpty @Size(max = 20)
        List<@NotBlank @Size(max = 30) String> departments,

        @Schema(description = "점심시간 시작(HH:mm, 선택 - 끝과 함께, 진료 시간 안). 빈 문자열이면 없음", example = "12:30", nullable = true)
        @Pattern(regexp = AdminHospitalRules.TIME_OR_EMPTY_REGEX, message = AdminHospitalRules.TIME_MESSAGE)
        String breakStart,

        @Schema(description = "점심시간 끝(HH:mm)", example = "13:30", nullable = true)
        @Pattern(regexp = AdminHospitalRules.TIME_OR_EMPTY_REGEX, message = AdminHospitalRules.TIME_MESSAGE)
        String breakEnd,

        @Schema(description = "휴진 요일(0=일 … 6=토, 중복 불가). 빈 배열이면 휴진 없음", example = "[0]", nullable = true)
        @Size(max = 7)
        List<@NotNull @Min(0) @Max(6) Integer> closedWeekdays,

        @Schema(description = "요일별 진료 시간(그 요일만 기본 진료 시간을 덮어씀, 요일당 하나). 빈 배열이면 없음", nullable = true)
        @Size(max = 7)
        List<@NotNull @Valid AdminHospitalWeekdayHours> weekdayHours
) {
    /** 점심시간·휴진 요일 없이 만드는 기존 형태(2026-10-28 이전 호출부 호환). */
    public AdminHospitalCreateRequest(String name, String region, String address, String phone, String description,
                                      String openTime, String closeTime, Boolean bookingAvailable, List<String> departments) {
        this(name, region, address, phone, description, openTime, closeTime, bookingAvailable, departments, null, null, null, null);
    }
}
