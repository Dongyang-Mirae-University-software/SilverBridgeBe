package kr.silverbridge.main.domain.anomaly.entity;

/**
 * 이상감지 상황의 판정 상태.
 *
 * <p>AI는 "위험해 보인다"까지만 말할 수 있고 "실제로 위험했다"는 현장을 아는 <b>보호자만</b> 판단할 수 있다.
 * 그래서 상황은 {@link #PENDING}으로 시작해 보호자 응답에 따라 확정된다.</p>
 *
 * <p>판정은 <b>응답한 보호자의 다수결</b>이다(2026-09-21). {@link #CONFLICTED}는 응답이 <b>동수</b>로 갈린
 * 상태로, 보호자들이 다시 응답해 합의해야 풀린다 - 관리자가 대신 정하지 않는다.</p>
 */
public enum AnomalyReviewStatus {

    /** 아직 아무도 응답하지 않음(기본값). */
    PENDING,

    /** 응답한 보호자 다수가 "실제 위험". */
    REAL,

    /** 응답한 보호자 다수가 "오탐". */
    FALSE_ALARM,

    /** 응답이 동수로 갈림 - 보호자 재확인 대기(다시 응답하면 풀린다). */
    CONFLICTED
}
