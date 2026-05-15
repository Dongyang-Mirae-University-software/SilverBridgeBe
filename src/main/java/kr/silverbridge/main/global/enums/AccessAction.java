package kr.silverbridge.main.global.enums;

public enum AccessAction {
    LOGIN, LOGOUT, KAKAO_LOGIN, TOKEN_ISSUE, PASSWORD_RESET, WITHDRAW,
    /** Refresh token 재사용(도난 신호) 감지 시 사용자의 모든 token 강제 폐기와 함께 기록 */
    TOKEN_REUSE_DETECTED
}
