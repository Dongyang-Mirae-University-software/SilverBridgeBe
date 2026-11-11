package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionTurn;
import kr.silverbridge.main.domain.companion.repository.CompanionTranscriptConsentRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionTurnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 턴 1행 저장 (2026-11-11). 기분은 항상, 글 두 칸은 피보호자가 동의했을 때만 담는다. 턴 기록 트랜잭션에 합류한다
 * ({@link CompanionSessionWriter#recordTurn}). 글은 로그에 남기지 않는다 - 턴 번호도 이 클래스에서는 찍지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CompanionTurnRecorder {

    private final CompanionTurnRepository turnRepository;
    private final CompanionTranscriptConsentRepository consentRepository;

    /** 턴 내용. 글은 화면 표시용 문자열 그대로(정규화하지 않는다). */
    public record TurnContent(CompanionMood mood, String botText, String answerText) {
        public static final TurnContent NONE = new TurnContent(null, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CompanionSession session, int turnNo, TurnContent content, OffsetDateTime now) {
        // 같은 턴 번호가 다시 오면 건너뛴다 - UNIQUE 위반으로 턴 기록 트랜잭션(위험 알림 선점 포함)이 롤백되지 않게.
        // 대화당 처리 자리가 1개라(CompanionTurnSlots) 같은 대화의 턴이 동시에 들어오지 않는다.
        if (turnNo <= 0 || turnRepository.existsBySessionIdAndTurnNo(session.getId(), turnNo)) {
            return;
        }
        TurnContent c = content == null ? TurnContent.NONE : content;
        boolean consented = !consentRepository.lockIfConsented(session.getWardId()).isEmpty();
        String bot = consented ? blankToNull(c.botText()) : null;
        String answer = consented ? blankToNull(c.answerText()) : null;
        turnRepository.save(CompanionTurn.of(session.getId(), turnNo, c.mood(), bot, answer, now));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
