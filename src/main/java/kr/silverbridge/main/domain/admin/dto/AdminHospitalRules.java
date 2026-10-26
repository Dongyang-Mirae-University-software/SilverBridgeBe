package kr.silverbridge.main.domain.admin.dto;

/** 제휴 병원 입력 형식(등록·수정 DTO 공용, 2026-10-26). */
final class AdminHospitalRules {

    /** 지역번호형(02-123-4567, 031-1234-5678) 또는 대표번호형(1588-1234). 하이픈은 선택. */
    static final String PHONE_REGEX = "^(0\\d{1,2}-?\\d{3,4}-?\\d{4}|1\\d{3}-?\\d{4})$";
    static final String PHONE_MESSAGE = "전화번호 형식이 올바르지 않습니다.";
    /** 실제 시각만(00:00~23:59). 예약 서비스는 99:99도 받는다. */
    static final String TIME_REGEX = "^([01]\\d|2[0-3]):[0-5]\\d$";
    static final String TIME_MESSAGE = "시각은 HH:mm 형식이어야 합니다.";

    private AdminHospitalRules() {
    }
}
