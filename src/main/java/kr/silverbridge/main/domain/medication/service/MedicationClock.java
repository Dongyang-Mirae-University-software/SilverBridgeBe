package kr.silverbridge.main.domain.medication.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 복약의 "오늘" 기준 시각. <b>서버·DB 타임존과 무관하게 항상 KST</b>다.
 *
 * <p>복약은 날짜 경계가 곧 기능이다 — 22:00 취침 전 약을 체크한 뒤 자정을 넘기면 "어제 것"이 되어야 하고,
 * 서버가 UTC로 돌면 09:00(KST) 이전 체크가 전날로 기록돼 "오늘 0/3"이 되돌아간다. 그래서 날짜 판정을
 * 한 곳에 모아 두고 모든 조회·체크가 이걸 쓴다.</p>
 */
public final class MedicationClock {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private MedicationClock() {
    }

    /** 복약 기준 오늘 날짜(KST). */
    public static LocalDate today() {
        return LocalDate.now(KST);
    }

    /** 기록용 현재 시각(KST 오프셋). */
    public static OffsetDateTime now() {
        return OffsetDateTime.now(KST);
    }
}
