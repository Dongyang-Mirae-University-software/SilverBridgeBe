package kr.silverbridge.main.domain.game.dto;

import java.util.List;

/**
 * 보호자 게임 관리 - 피보호자 날짜(KST)별 게임 활동량 (AI {@code GET /api/v1/games/activity} 중계).
 * 활동이 있는 날만 들어 있다. 게임 사용자 번호는 싣지 않는다.
 */
public record GuardianGameActivityResponse(String wardId, int days, List<Day> activity) {

    /** {@code date}는 KST 날짜 {@code YYYY-MM-DD}. */
    public record Day(String date, long attempts, long correct, long score) {
    }
}
