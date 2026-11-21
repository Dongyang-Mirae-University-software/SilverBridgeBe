package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 진행 중인 말벗 AI 호출의 자리(2026-11-04). 한 턴이 요청 스레드를 최대 90초 붙들므로 상한 없이 열면 SOS·로그인 같은
 * 일반 API까지 멈춘다 - 서버 전체(기본 8, 넘으면 503 {@code COMPANION_BUSY})와 대화 1개(동시에 1건, 넘으면 409
 * {@code COMPANION_TURN_IN_PROGRESS}) 두 단계로 막는다. 대화 단위 자리는 마감 스케줄러도 잡아 턴과 마감이 겹치지 않는다.
 * 상한은 이 인스턴스 기준이다(단일 서버 전제, OCR·챗 중계와 같다).
 */
@Slf4j
@Component
public class CompanionTurnSlots {

    private final Semaphore global;
    private final Set<Long> busySessions = ConcurrentHashMap.newKeySet();

    public CompanionTurnSlots(CompanionProperties properties) {
        this.global = new Semaphore(properties.getMaxConcurrent());
    }

    /** 반드시 {@link Slot#close()}로 돌려줘야 한다(try-with-resources). */
    public Slot acquire(long sessionId) {
        if (!busySessions.add(sessionId)) {
            throw new CustomException(ErrorCode.COMPANION_TURN_IN_PROGRESS);
        }
        if (!global.tryAcquire()) {
            busySessions.remove(sessionId);
            log.warn("[COMPANION-LIMIT] 서버 전체 동시 실행 상한 - 요청 거부: sessionId={}", sessionId);
            throw new CustomException(ErrorCode.COMPANION_BUSY);
        }
        return new Slot(sessionId);
    }

    /** 스케줄러용 - 이미 누가 쓰고 있으면 기다리지 않고 null(다음 주기에 다시 본다). */
    public Slot tryAcquire(long sessionId) {
        try {
            return acquire(sessionId);
        } catch (CustomException e) {
            return null;
        }
    }

    /**
     * 그 대화의 자리가 지금 잡혀 있는가(턴·마치기·마감 스케줄러 진행 중). 이어 가기가 진행 중인 턴과 겹치는지 볼 때만 쓴다
     * (2026-11-21 최종 점검 L-2). 자리를 잡지 않는 순간값이라 확인 직후 바뀔 수 있다 - 쓰기의 근거로 삼지 말 것.
     */
    public boolean isBusy(long sessionId) {
        return busySessions.contains(sessionId);
    }

    int available() {
        return global.availablePermits();
    }

    public final class Slot implements AutoCloseable {
        private final long sessionId;
        private final AtomicBoolean released = new AtomicBoolean();

        private Slot(long sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                global.release();
                busySessions.remove(sessionId);
            }
        }
    }
}
