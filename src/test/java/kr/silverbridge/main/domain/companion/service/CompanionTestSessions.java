package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.entity.CompanionEndReason;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** 말벗 테스트용 대화 행(목 저장소에 넣는다). */
final class CompanionTestSessions {

    private static final AtomicLong IDS = new AtomicLong(1000);

    private CompanionTestSessions() {
    }

    static OffsetDateTime at(LocalDate date, CompanionSlot slot, int plusMinutes) {
        return date.atTime(slot.start()).plusMinutes(plusMinutes).atOffset(ZoneOffset.ofHours(9));
    }

    /** 마친 대화. mood null = 판단 불가. answered 0이면 대답 없는 대화. */
    static CompanionSession finished(String wardId, LocalDate date, CompanionSlot slot, CompanionMood mood,
                                     int answered) {
        OffsetDateTime started = at(date, slot, 1);
        CompanionSession session = CompanionSession.claim(wardId, date, slot, 1L, started);
        ReflectionTestUtils.setField(session, "id", IDS.incrementAndGet());
        session.attachDialogue("d-" + session.getId(), 8, started);
        session.finish(CompanionEndReason.MAX_TURNS, mood, 8, answered, 8 - answered, false, false, null,
                at(date, slot, 10));
        return session;
    }

    static CompanionSession open(String wardId, LocalDate date, CompanionSlot slot) {
        OffsetDateTime started = at(date, slot, 1);
        CompanionSession session = CompanionSession.claim(wardId, date, slot, 1L, started);
        ReflectionTestUtils.setField(session, "id", IDS.incrementAndGet());
        session.attachDialogue("d-" + session.getId(), 8, started);
        return session;
    }
}
