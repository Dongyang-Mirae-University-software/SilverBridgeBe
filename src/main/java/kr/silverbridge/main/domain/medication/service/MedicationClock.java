package kr.silverbridge.main.domain.medication.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    /** 저장된 시각(오프셋 무관)을 KST 날짜·시각으로 바꾼다 — 등록 시각을 복용 시각과 같은 기준으로 비교할 때 쓴다. */
    public static LocalDateTime toKst(OffsetDateTime dateTime) {
        return dateTime.atZoneSameInstant(KST).toLocalDateTime();
    }

    /** 저장된 시각을 KST 오프셋(+09:00)으로 바꾼다 - 응답에 싣는 시각을 서버 타임존과 무관하게 맞춘다. null이면 null. */
    public static OffsetDateTime atKst(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZoneSameInstant(KST).toOffsetDateTime();
    }

    /**
     * 그날(KST) 등록됐고 등록 시각이 복용 시각보다 늦은지 — 그날은 먹을 기회가 없었으므로 미체크로 세지 않는다(MED-G14).
     * 미복용 요약·보호자 대시보드가 같은 기준을 쓰고, FE 복약 카드도 응답의 {@code createdAt}으로 같은 판정을 한다.
     *
     * <p>등록 시각이 복용 시각과 같으면(정각 등록) 센다. 등록 시각을 모르면 예전처럼 센다. 전날 이전 등록분은 시각과 무관하게 센다.</p>
     */
    public static boolean registeredAfterDose(OffsetDateTime registeredAt, LocalTime doseTime, LocalDate day) {
        if (registeredAt == null) {
            return false;
        }
        LocalDateTime registered = toKst(registeredAt);
        return registered.toLocalDate().equals(day) && registered.toLocalTime().isAfter(doseTime);
    }
}
