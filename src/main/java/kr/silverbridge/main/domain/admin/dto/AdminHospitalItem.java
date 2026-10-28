package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자 제휴 병원 목록 항목(예약 서비스 관리자 API 중계, 2026-10-26).
 *
 * <p>예약 서비스 응답을 그대로 넘기지 않고 이 칸들만 담는다(허용 목록). 병원 이메일은 예약 서비스에 칸이 없어 만들지 않았고,
 * 우리 사용자 예약 수는 환자 정보가 든 예약 전체를 받아야 셀 수 있어 싣지 않는다.</p>
 */
@Schema(description = "제휴 병원")
public record AdminHospitalItem(
        @Schema(description = "병원 ID(예약 서비스)", example = "12")
        long id,
        @Schema(description = "병원 이름", example = "서울튼튼정형외과")
        String name,
        @Schema(description = "지역(자유 문자열)", example = "서울 강남구")
        String region,
        @Schema(description = "주소", example = "서울 강남구 테헤란로 123")
        String address,
        @Schema(description = "대표 전화", example = "02-2001-0001")
        String phone,
        @Schema(description = "소개(없으면 빈 문자열)", example = "관절·척추 진료")
        String description,
        @Schema(description = "진료 시작(HH:mm)", example = "09:00")
        String openTime,
        @Schema(description = "진료 종료(HH:mm)", example = "18:00")
        String closeTime,
        @Schema(description = "점심시간 시작(HH:mm). 없으면 null", example = "12:30", nullable = true)
        String breakStart,
        @Schema(description = "점심시간 끝(HH:mm). 없으면 null", example = "13:30", nullable = true)
        String breakEnd,
        @Schema(description = "휴진 요일(0=일 … 6=토). 없으면 빈 배열", example = "[0]")
        List<Integer> closedWeekdays,
        @Schema(description = "앱 예약 가능 여부", example = "true")
        boolean bookingAvailable,
        @Schema(description = "진료과(사용 중인 것만)")
        List<Department> departments,
        @Schema(description = "운영 상태. 예약 서비스에 상태 행이 없으면 null", nullable = true)
        Status status,
        @Schema(description = "등록 시각(KST). 알 수 없으면 null", example = "2026-10-20T13:05:00+09:00", nullable = true)
        OffsetDateTime registeredAt,
        @Schema(description = "마지막 수정 시각(KST). 알 수 없으면 null", example = "2026-10-25T09:00:00+09:00", nullable = true)
        OffsetDateTime updatedAt
) {

    @Schema(description = "진료과")
    public record Department(
            @Schema(description = "진료과 ID(예약 서비스)", example = "31")
            long id,
            @Schema(description = "진료과 이름", example = "정형외과")
            String name
    ) {
    }

    @Schema(description = "병원 운영 상태(관리자가 입력하는 값)")
    public record Status(
            @Schema(description = "OPEN · CLOSED · BREAK_TIME · HOLIDAY · INSPECTION", example = "OPEN")
            String operationStatus,
            @Schema(description = "LOW · NORMAL · BUSY · VERY_BUSY", example = "NORMAL")
            String congestionLevel,
            @Schema(description = "대기 인원", example = "3")
            Integer waitingCount,
            @Schema(description = "평균 대기(분)", example = "15")
            Integer averageWaitingMinutes,
            @Schema(description = "오늘(KST) 예약 수 - 예약 서비스가 예약 행을 센 값(취소·거절 제외)", example = "0")
            Integer todayReservationCount,
            @Schema(description = "환자 접수 중", example = "true")
            Boolean acceptingPatients,
            @Schema(description = "공지(없으면 null 또는 빈 문자열)", example = "오늘 오후 휴진", nullable = true)
            String notice,
            @Schema(description = "상태 수정 시각(KST)", nullable = true)
            OffsetDateTime updatedAt
    ) {
    }
}
