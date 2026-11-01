package kr.silverbridge.main.domain.checkin.event;

import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;

import java.time.LocalDate;

/**
 * 보호자에게 알릴 안부 상황(2026-11-01). 대화 턴·정리를 저장한 트랜잭션 안에서 발행하고, 커밋 뒤 리스너가 보낸다.
 * 내용(받아쓴 글자·요약)은 싣지 않는다 - 누구의 어느 시각인지만.
 */
public record CheckinGuardianAlertEvent(Kind kind, String wardId, LocalDate checkDate, CheckinSlot slot) {

    public enum Kind {
        /** 대화 중 응급·자해 위험 신호 - 긴급 executor, 강제 FCM. */
        RISK,
        /** 평소 대비 확인 필요 - 일반 executor, 설정 기반. */
        ATTENTION
    }
}
