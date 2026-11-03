package kr.silverbridge.main.domain.companion.entity;

/** 안부 대화 상태. 값을 추가하면 V65 {@code chk_companion_session_status}를 새 마이그레이션으로 재정의한다. */
public enum CompanionSessionStatus {
    /** 진행 중. {@code dialogueId}가 비어 있으면 AI에 대화를 여는 중(선점)이다. */
    OPEN,
    /** AI에게서 결과를 받아 저장했다. */
    FINISHED,
    /** AI 쪽 대화가 사라졌다(AI 재시작 등 - 대화는 메모리에만 있다). 결과 없이 닫는다. */
    LOST
}
