package kr.silverbridge.main.domain.companion.event;

import java.time.LocalDate;

/** 안부 대화가 걱정·우울로 끝났다(PR-B). 주의 알림 리스너가 연속 일수를 다시 계산한다. 기분 코드 외 내용은 싣지 않는다. */
public record CompanionMoodRecordedEvent(String wardId, LocalDate sessionDate) {
}
