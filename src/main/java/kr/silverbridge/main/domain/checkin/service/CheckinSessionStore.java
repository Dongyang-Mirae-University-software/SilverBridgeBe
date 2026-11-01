package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.entity.CheckinEndReason;
import kr.silverbridge.main.domain.checkin.event.CheckinGuardianAlertEvent;
import kr.silverbridge.main.domain.checkin.entity.CheckinResult;
import kr.silverbridge.main.domain.checkin.entity.CheckinSession;
import kr.silverbridge.main.domain.checkin.entity.CheckinSessionStatus;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.repository.CheckinResultRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinSessionRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 말벗 DB 쓰기. 메서드마다 짧은 트랜잭션이다 - AI 호출(최대 30초)은 트랜잭션 밖에서 하고 결과만 여기서 반영한다
 * (DB 연결을 AI 응답 동안 붙들지 않는다).
 */
@Service
@RequiredArgsConstructor
public class CheckinSessionStore {

    private final CheckinSessionRepository sessionRepository;
    private final CheckinResultRepository resultRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Optional<CheckinSession> find(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    @Transactional(readOnly = true)
    public Optional<CheckinSession> findInProgress(String wardId, LocalDate date, CheckinSlot slot) {
        return sessionRepository.findByWardIdAndCheckDateAndSlotAndStatus(wardId, date, slot,
                CheckinSessionStatus.IN_PROGRESS);
    }

    @Transactional(readOnly = true)
    public boolean hasResult(String wardId, LocalDate date, CheckinSlot slot) {
        return resultRepository.existsByWardIdAndCheckDateAndSlot(wardId, date, slot);
    }

    /** 평소 대비 기준 = 그 날 전 {@code days}일 결과(그 날은 빼고). */
    @Transactional(readOnly = true)
    public List<CheckinResult> baseline(String wardId, LocalDate date, int days) {
        return resultRepository.findByWardIdAndCheckDateBetweenOrderByCheckDateAscSlotAsc(
                wardId, date.minusDays(days), date.minusDays(1));
    }

    @Transactional(readOnly = true)
    public Optional<CheckinResult> findResult(Long sessionPk) {
        return resultRepository.findBySessionPk(sessionPk);
    }

    /** 새 대화. 같은 시각에 진행 중인 대화가 이미 있으면 부분 UNIQUE가 막는다(호출부가 기존 대화로 돌린다). */
    @Transactional
    public CheckinSession create(CheckinSession session) {
        return sessionRepository.saveAndFlush(session);
    }

    @Transactional
    public void expire(Long sessionPk, OffsetDateTime at) {
        sessionRepository.findById(sessionPk).filter(CheckinSession::isInProgress).ifPresent(s -> s.expire(at));
    }

    /**
     * AI가 받아 준 턴을 센다. 그 사이 다른 요청이 턴을 셌거나 대화가 닫혔으면 409다(1인 동시 1건이라 정상 경로에서는 생기지 않는다).
     */
    @Transactional
    public CheckinSession recordTurn(Long sessionPk, int expectedTurnCount, boolean done, boolean emergency,
                                     boolean selfHarm) {
        CheckinSession session = sessionRepository.findById(sessionPk)
                .orElseThrow(() -> new CustomException(ErrorCode.CHECKIN_SESSION_NOT_FOUND));
        if (!session.isInProgress() || session.getTurnCount() != expectedTurnCount) {
            throw new CustomException(ErrorCode.CHECKIN_SESSION_CLOSED);
        }
        boolean hadRisk = session.isRiskEmergency() || session.isRiskSelfHarm();
        session.recordTurn(done, emergency, selfHarm);
        if (!hadRisk && (emergency || selfHarm)) {
            // 위험이 처음 올라간 턴 - 정리를 기다리지 않고 커밋 뒤 보호자에게 알린다
            eventPublisher.publishEvent(new CheckinGuardianAlertEvent(CheckinGuardianAlertEvent.Kind.RISK,
                    session.getWardId(), session.getCheckDate(), session.getSlot()));
        }
        return session;
    }

    /** 결과 저장 + 대화 닫기를 한 트랜잭션으로. 이미 정리된 대화면 저장된 결과를 그대로 돌려준다(재전송 멱등). */
    @Transactional
    public CheckinResult finish(Long sessionPk, CheckinResult result, CheckinEndReason reason, OffsetDateTime at) {
        CheckinSession session = sessionRepository.findById(sessionPk)
                .orElseThrow(() -> new CustomException(ErrorCode.CHECKIN_SESSION_NOT_FOUND));
        if (session.getStatus() == CheckinSessionStatus.FINISHED) {
            return resultRepository.findBySessionPk(sessionPk)
                    .orElseThrow(() -> new CustomException(ErrorCode.CHECKIN_SESSION_CLOSED));
        }
        if (!session.isInProgress()) {
            throw new CustomException(ErrorCode.CHECKIN_SESSION_CLOSED);
        }
        // 즉시 flush - 같은 시각 결과 UNIQUE 위반이 커밋 시점이 아니라 여기서 DataIntegrityViolationException으로 나오게 한다
        CheckinResult saved = resultRepository.saveAndFlush(result);
        boolean hadRisk = session.isRiskEmergency() || session.isRiskSelfHarm();
        boolean risk = result.isRiskEmergency() || result.isRiskSelfHarm();
        session.finish(reason, result.isRiskEmergency(), result.isRiskSelfHarm(), at);
        if (risk && !hadRisk) {
            eventPublisher.publishEvent(new CheckinGuardianAlertEvent(CheckinGuardianAlertEvent.Kind.RISK,
                    saved.getWardId(), saved.getCheckDate(), saved.getSlot()));
        } else if (!risk && saved.isAttention()) {
            // 위험이면 위험 알림이 이미 갔다 - 확인 필요를 겹쳐 보내지 않는다
            eventPublisher.publishEvent(new CheckinGuardianAlertEvent(CheckinGuardianAlertEvent.Kind.ATTENTION,
                    saved.getWardId(), saved.getCheckDate(), saved.getSlot()));
        }
        return saved;
    }
}
