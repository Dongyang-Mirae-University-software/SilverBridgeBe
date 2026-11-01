package kr.silverbridge.main.domain.checkin.service;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 말벗의 "지금"(KST). 날짜·시각 판정은 서버·DB 타임존과 무관하게 이것만 쓴다(복약 {@code MedicationClock}과 같은 기준). */
@Component
public class CheckinClock {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public OffsetDateTime now() {
        return OffsetDateTime.now(KST);
    }

    /** 저장된 시각을 KST 오프셋으로. null이면 null. */
    public static OffsetDateTime atKst(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZoneSameInstant(KST).toOffsetDateTime();
    }
}
