package kr.silverbridge.main.domain.connection.dto;

/**
 * 보호자의 ACTIVE 피보호자 한 명 - 이름·관계만(2026-11-11, 챗 예약 대상 선택용).
 * 전화·주소·생년월일을 더하지 말 것: 이 값은 AI 상담 요청 context 로 나간다.
 *
 * @param relation     보호자가 어르신에게 누구인지(아들·딸) - 연결 요청 때 적은 값. 관리자 강제 연결 등으로 비어 있으면 null.
 * @param wardRelation 보호자가 어르신을 부르는 호칭(엄마·아빠, V74, 2026-11-25). AI 예약 대상 선택지 글자에 쓴다
 *                     ("이름 호칭" - relation 을 쓰면 "남궁(아들)"이 "내 아들"로 거꾸로 읽힌다). 기존 연결·강제 연결은 null.
 */
public record ActiveWardSummary(String wardId, String name, String relation, String wardRelation) {
}
