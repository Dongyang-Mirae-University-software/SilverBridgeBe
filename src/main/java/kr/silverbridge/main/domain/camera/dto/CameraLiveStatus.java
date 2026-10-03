package kr.silverbridge.main.domain.camera.dto;

/**
 * 송출 상태 문자열(FE 계약). AI 값을 그대로 넘기지 않고 세 가지로 좁힌다 - AI가 상태 이름을 바꿔도 화면이 깨지지 않게.
 */
public final class CameraLiveStatus {

    public static final String RUNNING = "running";
    public static final String DISCONNECTED = "disconnected";
    public static final String OFFLINE = "offline";

    private CameraLiveStatus() {
    }

    /** AI 상태(running·disconnected·stopped …) → 계약 값. 모르는 값·종료는 offline. */
    public static String fromAi(String aiStatus) {
        if (aiStatus == null) {
            return OFFLINE;
        }
        return switch (aiStatus.trim().toLowerCase()) {
            case RUNNING -> RUNNING;
            case DISCONNECTED -> DISCONNECTED;
            default -> OFFLINE;
        };
    }
}
