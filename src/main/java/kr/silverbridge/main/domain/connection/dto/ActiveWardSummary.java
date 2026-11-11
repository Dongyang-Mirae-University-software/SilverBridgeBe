package kr.silverbridge.main.domain.connection.dto;

/**
 * 보호자의 ACTIVE 피보호자 한 명 - 이름·관계만(2026-11-11, 챗 예약 대상 선택용).
 * 전화·주소·생년월일을 더하지 말 것: 이 값은 AI 상담 요청 context 로 나간다.
 *
 * @param relation 보호자가 연결 요청 때 적은 관계. 관리자 강제 연결 등으로 비어 있으면 null.
 */
public record ActiveWardSummary(String wardId, String name, String relation) {
}
