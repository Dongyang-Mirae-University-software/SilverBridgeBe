package kr.silverbridge.main.global.enums;

public enum Status {
    ACTIVE,     // 이용 중
    RESTRICTED, // 이용 제한 (관리자가 정지시킨 계정 - 로그인·토큰 재발급 차단)
    INACTIVE    // 비활성(탈퇴) - 탈퇴 경로만 만들 수 있고, 스윕 스케줄러가 영구 삭제한다
}
