package kr.silverbridge.main.domain.checkin.entity;

/** 대화 상태. 값을 더하면 V65 {@code chk_checkin_session_status} CHECK 재정의가 필요하다({@code CheckinCheckSyncTest}). */
public enum CheckinSessionStatus {
    IN_PROGRESS,
    FINISHED,
    /** 정리하지 않은 채 시간이 지나 같은 시각에 새 대화가 시작되며 닫힌 대화. */
    EXPIRED
}
