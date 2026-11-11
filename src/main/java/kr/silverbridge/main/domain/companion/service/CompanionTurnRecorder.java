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

    /**
     * 동의 행을 {@code FOR SHARE}로 잡고 동의 여부를 돌려준다. <b>턴 기록 트랜잭션에서 대화 행을 바꾸기 전에 부른다</b>
     * (2026-11-12 점검 L-1) - native 쿼리는 실행 전에 영속성 컨텍스트를 전부 flush하므로(Hibernate 7, JPA 부트스트랩),
     * 대화 행을 먼저 바꾼 뒤 부르면 "대화 행 → 동의 행" 순서로 잠겨 역할 변경 정리·탈퇴 purge("동의 행 → 대화 행")와
     * 교착이 생길 수 있다. 순서는 항상 동의 행 → 대화 행.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockConsent(String wardId) {
        return !consentRepository.lockIfConsented(wardId).isEmpty();
    }

    /** 동의 잠금과 저장을 한 번에 - 대화 행을 아직 바꾸지 않은 트랜잭션에서만 쓴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CompanionSession session, int turnNo, TurnContent content, OffsetDateTime now) {
        record(session, turnNo, content, lockConsent(session.getWardId()), now);
    }

    /** {@code consented}는 같은 트랜잭션에서 {@link #lockConsent}로 미리 잡아 둔 값이어야 한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CompanionSession session, int turnNo, TurnContent content, boolean consented,
                       OffsetDateTime now) {
        // 같은 턴 번호가 다시 오면 건너뛴다 - UNIQUE 위반으로 턴 기록 트랜잭션(위험 알림 선점 포함)이 롤백되지 않게.
        // 대화당 처리 자리가 1개라(CompanionTurnSlots) 같은 대화의 턴이 동시에 들어오지 않는다.
        if (turnNo <= 0 || turnRepository.existsBySessionIdAndTurnNo(session.getId(), turnNo)) {
            return;
        }
        TurnContent c = content == null ? TurnContent.NONE : content;
        String bot = consented ? blankToNull(c.botText()) : null;
        String answer = consented ? blankToNull(c.answerText()) : null;
        turnRepository.save(CompanionTurn.of(session.getId(), turnNo, c.mood(), bot, answer, now));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
