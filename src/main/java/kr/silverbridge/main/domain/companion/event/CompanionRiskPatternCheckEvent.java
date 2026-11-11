package kr.silverbridge.main.domain.companion.event;

import java.time.LocalDate;

/**
 * 안부 대화 턴에서 위험 확률이 누적 기준(기본 0.97) 이상으로 나왔다(2026-11-11). 커밋 뒤 리스너가 최근 며칠을 다시 본다.
 * 확률 값·대답 내용은 싣지 않는다.
 */
public record CompanionRiskPatternCheckEvent(String wardId, LocalDate sessionDate) {
}
