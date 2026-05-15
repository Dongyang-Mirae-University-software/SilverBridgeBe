package kr.silverbridge.main.global.enums;

public enum AdminAuditAction {
    USER_STATUS_CHANGE,     // 사용자 상태 변경
    USER_ROLE_CHANGE,       // 사용자 역할 변경 (연결 자동 해제 포함)
    USER_FORCE_DELETE,      // 사용자 강제 탈퇴
    FORCE_CONNECT,          // 보호자-피보호자 강제 연결
    FORCE_DISCONNECT,       // 보호자-피보호자 강제 연결 해제
    ANNOUNCEMENT_CREATE,        // 공지 생성
    ANNOUNCEMENT_UPDATE,        // 공지 수정
    ANNOUNCEMENT_DELETE,        // 공지 삭제
    ANNOUNCEMENT_DRAFT_CREATE,  // 공지 임시저장 생성
    ANNOUNCEMENT_DRAFT_UPDATE,  // 공지 임시저장 수정
    ANNOUNCEMENT_DRAFT_DELETE,  // 공지 임시저장 삭제
    ANNOUNCEMENT_DRAFT_PUBLISH  // 공지 임시저장 게시
}
