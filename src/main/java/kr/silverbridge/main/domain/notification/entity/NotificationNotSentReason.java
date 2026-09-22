package kr.silverbridge.main.domain.notification.entity;

/** 알림을 보내지 않은 사유({@link NotificationLogResult#NOT_SENT}일 때만). */
public enum NotificationNotSentReason {

    /** 수신자가 이용 제한(RESTRICTED) 계정 - 강제 알림도 막는다(2026-09-09 정책). */
    RESTRICTED_ACCOUNT("이용 제한 계정"),
    /** 수신자가 탈퇴 처리 중(INACTIVE)인 계정. */
    WITHDRAWING_ACCOUNT("탈퇴 처리 중인 계정"),
    /** 설정 기반 알림인데 수신자가 이 알림을 받을 채널을 모두 꺼 뒀다. */
    NO_ENABLED_CHANNEL("수신자가 받을 채널을 꺼 둠");

    private final String label;

    NotificationNotSentReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
