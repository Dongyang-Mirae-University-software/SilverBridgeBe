package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 복약 알림 설정 변경 요청(보호자 화면의 토글).
 *
 * <p><b>두 필드 모두 선택</b>이며 {@code null}은 "변경하지 않음"이다 — 기존 프론트가 보내던
 * {@code {alarmEnabled}}만으로도 그대로 동작하고, 재알림 설정이 의도치 않게 초기화되지 않는다.
 * (2차에서 {@code remindAgainEnabled}를 추가하며 {@code alarmEnabled}의 필수 제약을 뗐다 —
 * 필수로 두면 재알림만 바꾸려는 요청이 알림 ON/OFF까지 함께 보내야 한다.)</p>
 */
@Schema(description = "복약 알림 설정 변경 요청")
public record MedicationSettingUpdateRequest(

        @Schema(description = "복약 알림 사용 여부 (생략 시 기존값 유지)", example = "true")
        Boolean alarmEnabled,

        @Schema(description = "체크하지 않았을 때 한 번 더 알릴지 (생략 시 기존값 유지)", example = "true")
        Boolean remindAgainEnabled
) {}
