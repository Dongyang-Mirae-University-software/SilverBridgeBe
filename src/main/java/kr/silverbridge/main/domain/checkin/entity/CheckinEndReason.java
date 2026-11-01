package kr.silverbridge.main.domain.checkin.entity;

/** 대화가 끝난 이유. 이름이 AI 계약 {@code reason} 값과 같다. BE가 정한다(클라이언트 값을 받지 않음). */
public enum CheckinEndReason {
    TURN_LIMIT,
    TIME_LIMIT,
    USER_END,
    AI_END
}
