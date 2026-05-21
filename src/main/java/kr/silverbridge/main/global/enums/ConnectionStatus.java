package kr.silverbridge.main.global.enums;

public enum ConnectionStatus {
    PENDING,      // 수락 대기 (보호자가 보낸 요청)
    ACTIVE,       // 연결됨
    CANCELLED,    // 보호자가 PENDING 요청을 스스로 취소
    REFUSED,      // 피보호자가 PENDING 요청을 거절
    DISCONNECTED  // ACTIVE 연결이 해제됨 (보호자 또는 피보호자)
}
