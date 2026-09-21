package kr.silverbridge.main.global.enums;

public enum AdminAuditAction {
    USER_STATUS_CHANGE,     // 사용자 상태 변경
    USER_ROLE_CHANGE,       // 사용자 역할 변경 (연결 자동 해제 포함)
    USER_NAME_CHANGE,       // 사용자 이름 수정 (관리자 회원관리 - 이메일·전화번호는 수정 대상이 아니다)
    USER_FORCE_DELETE,      // 사용자 강제 탈퇴
    FORCE_CONNECT,          // 보호자-피보호자 강제 연결
    FORCE_DISCONNECT,       // 보호자-피보호자 강제 연결 해제
    ANNOUNCEMENT_CREATE,        // 공지 생성
    ANNOUNCEMENT_UPDATE,        // 공지 수정
    ANNOUNCEMENT_DELETE,        // 공지 삭제
    ANNOUNCEMENT_DRAFT_CREATE,  // 공지 임시저장 생성
    ANNOUNCEMENT_DRAFT_UPDATE,  // 공지 임시저장 수정
    ANNOUNCEMENT_DRAFT_DELETE,  // 공지 임시저장 삭제
    ANNOUNCEMENT_DRAFT_PUBLISH, // 공지 임시저장 게시
    // 이상감지 판정 정정 - 2026-09-21 관리자 정정 폐지로 새로 기록되지 않는다. 폐지 전 감사 로그 행이
    // 이 값을 담고 있어 enum과 CHECK(V50)에서 지우지 않는다(지우면 CHECK 재정의가 기존 행 때문에 실패한다).
    ANOMALY_REVIEW_RESOLVE,
    INQUIRY_ANSWER              // 문의 답변 (보호자 개인 문의를 열어 답하는 쓰기 조작 - V50 CHECK 재정의)
}
