package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.event.CompanionRiskDetectedEvent;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 안부 대화 행의 짧은 쓰기 모음. AI 호출(최대 90초)을 트랜잭션 밖에 두려고 서비스에서 떼어 냈다 - DB 연결을 AI 응답
 * 대기 동안 붙들지 않는다. 각 메서드는 자기 트랜잭션에서 행을 다시 읽어 고친다.
 *
 * <p>위험 알림은 {@code risk_notified_at} 조건부 UPDATE에 이긴 쪽만 이벤트를 낸다(대화당 1번). 이벤트는 커밋 뒤
 * 비동기 리스너가 보낸다 - 롤백되면 나가지 않는다.</p>
 */
@Component
@RequiredArgsConstructor
public class CompanionSessionWriter {

    private final CompanionSessionRepository sessionRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 자리 선점. 같은 (피보호자, 날짜, 시간대)가 이미 있으면 UNIQUE 위반 예외가 그대로 나간다. */
    @Transactional
    public CompanionSession claim(String wardId, LocalDate date, CompanionSlot slot, Long questionId,
                                  OffsetDateTime now) {
        return sessionRepository.saveAndFlush(CompanionSession.claim(wardId, date, slot, questionId, now));
    }

    @Transactional
    public CompanionSession attachDialogue(long sessionId, String dialogueId, Integer maxTurns, OffsetDateTime now) {
        CompanionSession session = load(sessionId);
        session.attachDialogue(dialogueId, maxTurns, now);
        return session;
    }

    /** AI에 대화를 열지 못했을 때 선점을 풀어 다시 열 수 있게 한다. */
    @Transactional
    public void releaseClaim(long sessionId) {
        sessionRepository.findById(sessionId)
                .filter(session -> session.isOpen() && !session.isDialogueAttached())
                .ifPresent(sessionRepository::delete);
    }

    @Transactional
    public CompanionSession recordTurn(long sessionId, int turnNo, boolean riskSeen, OffsetDateTime now) {
        CompanionSession session = load(sessionId);
        session.recordTurn(turnNo, riskSeen, now);
        if (riskSeen) {
            claimRiskAlert(session, now);
        }
        return session;
    }

    @Transactional
    public CompanionSession finish(long sessionId, CompanionResult result, OffsetDateTime now) {
        CompanionSession session = load(sessionId);
        session.finish(result.endReason(), result.mood(), result.turnCount(), result.answeredCount(),
                result.noResponseCount(), result.riskFlag(), result.urgent(), result.promptVersion(), now);
        // 위험 신호 턴에서 이미 보냈으면 선점에서 진다 - 마감 때는 "URGENT인데 아직 안 보낸" 경우만 남는다(결정 J)
        if (result.needsRiskAlert()) {
            claimRiskAlert(session, now);
        }
        return session;
    }

    @Transactional
    public void markLost(long sessionId, OffsetDateTime now) {
        sessionRepository.findById(sessionId).filter(CompanionSession::isOpen)
                .ifPresent(session -> session.markLost(now));
    }

    private void claimRiskAlert(CompanionSession session, OffsetDateTime now) {
        String wardId = session.getWardId();
        long id = session.getId();
        if (sessionRepository.claimRiskNotification(id, now) == 1) {
            eventPublisher.publishEvent(new CompanionRiskDetectedEvent(wardId, id));
        }
    }

    private CompanionSession load(long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMPANION_SESSION_NOT_FOUND));
    }
}
