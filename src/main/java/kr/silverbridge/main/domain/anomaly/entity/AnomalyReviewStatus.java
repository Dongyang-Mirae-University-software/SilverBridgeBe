package kr.silverbridge.main.domain.anomaly.entity;

/**
 * 이상감지 상황의 판정 상태.
 *
 * <p>AI는 "위험해 보인다"까지만 말할 수 있고 "실제로 위험했다"는 현장을 아는 <b>보호자만</b> 판단할 수 있다.
 * 그래서 상황은 {@link #PENDING}으로 시작해 보호자 응답에 따라 확정된다.</p>
 *
 * <p>{@link #CONFLICTED}는 보호자끼리 답이 갈린 상태다 - 서버가 다수결로 정하지 않는다. 한 명은 실제 화재로,
 * 다른 한 명은 요리 연기로 봤다면 그 불일치 자체가 관리자가 확인해야 할 정보이기 때문이다.</p>
 */
public enum AnomalyReviewStatus {

    /** 아직 아무도 응답하지 않음(기본값). */
    PENDING,

    /** 응답한 보호자 전원이 "실제 위험". */
    REAL,

    /** 응답한 보호자 전원이 "오탐". */
    FALSE_ALARM,

    /** 보호자 응답이 엇갈림 - 관리자 확인 대기. */
    CONFLICTED
}
