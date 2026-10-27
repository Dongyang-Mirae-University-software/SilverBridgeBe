package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.validation.NoControlChars;

/**
 * 병원 운영 상태 수정(부분 수정 - null은 변경 안 함, 2026-10-26). 한 칸도 없으면 400이다.
 * enum 값은 예약 서비스와 같으며 모르는 값은 400이다.
 * 오늘 예약 수는 받지 않는다 - 예약 서비스가 오늘(KST) 예약 행을 세서 응답한다(2026-10-27, 관리자 입력과 자동 증가가 섞여 사실과 어긋났다).
 */
@Schema(description = "병원 운영 상태 수정(null = 변경 안 함)")
public record AdminHospitalStatusRequest(
        @Schema(description = "운영 상태", example = "OPEN")
        OperationStatus operationStatus,
        @Schema(description = "혼잡도", example = "NORMAL")
        CongestionLevel congestionLevel,
        @Schema(description = "대기 인원(0~9999)", example = "3")
        @Min(0) @Max(9999)
        Integer waitingCount,
        @Schema(description = "평균 대기(분, 0~1440)", example = "15")
        @Min(0) @Max(1440)
        Integer averageWaitingMinutes,
        @Schema(description = "환자 접수 중", example = "true")
        Boolean acceptingPatients,
        @Schema(description = "공지(최대 200자, 빈 문자열이면 지움)", example = "오늘 오후 휴진")
        @NoControlChars @Size(max = 200)
        String notice
) {
    public boolean isEmpty() {
        return operationStatus == null && congestionLevel == null && waitingCount == null
                && averageWaitingMinutes == null && acceptingPatients == null
                && notice == null;
    }

    public enum OperationStatus { OPEN, CLOSED, BREAK_TIME, HOLIDAY, INSPECTION }

    public enum CongestionLevel { LOW, NORMAL, BUSY, VERY_BUSY }
}
