package kr.silverbridge.main.domain.game.service;

import kr.silverbridge.main.domain.game.config.GameRelayProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 게임 기록 중계 동시 상한(2026-11-21 최종 점검 M-4) - 기본 서버 4·보호자당 2, 넘치면 503 GAME_UNAVAILABLE. */
class GameRelaySlotsTest {

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GAME_UNAVAILABLE);
    }

    @Test
    @DisplayName("기본값: 보호자 1명은 동시에 2건까지 - 3번째는 503, 하나를 돌려주면 다시 얻는다")
    void 보호자당_상한() {
        GameRelaySlots slots = new GameRelaySlots(new GameRelayProperties());

        GameRelaySlots.Slot first = slots.acquire("GRD001");
        GameRelaySlots.Slot second = slots.acquire("GRD001");
        assertUnavailable(() -> slots.acquire("GRD001"));
        assertThat(slots.available()).as("거절된 요청은 서버 전체 자리를 쓰지 않는다").isEqualTo(2);

        first.close();
        GameRelaySlots.Slot again = slots.acquire("GRD001");

        again.close();
        second.close();
        assertThat(slots.activeUsers()).isZero();
        assertThat(slots.available()).isEqualTo(4);
    }

    @Test
    @DisplayName("기본값: 서버 전체는 동시에 4건까지 - 5번째 보호자는 503이고 보호자별 수도 되돌린다")
    void 서버_전체_상한() {
        GameRelaySlots slots = new GameRelaySlots(new GameRelayProperties());
        List<GameRelaySlots.Slot> held = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            held.add(slots.acquire("GRD00" + i));
        }

        assertUnavailable(() -> slots.acquire("GRD999"));
        assertThat(slots.activeUsers()).as("거절된 보호자의 동시 수가 남지 않는다").isEqualTo(4);

        held.forEach(GameRelaySlots.Slot::close);
        assertThat(slots.activeUsers()).isZero();
        assertThat(slots.available()).isEqualTo(4);
    }

    @Test
    @DisplayName("같은 자리를 두 번 닫아도 한 번만 돌려준다")
    void 중복_반환_무시() {
        GameRelaySlots slots = new GameRelaySlots(new GameRelayProperties());

        GameRelaySlots.Slot slot = slots.acquire("GRD001");
        slot.close();
        slot.close();

        assertThat(slots.available()).isEqualTo(4);
        assertThat(slots.activeUsers()).isZero();
    }

    @Test
    @DisplayName("보호자 40명이 동시에 시도해도 서버 전체 상한(4)만 성공한다")
    void 동시_획득_전체_상한() throws Exception {
        GameRelaySlots slots = new GameRelaySlots(new GameRelayProperties());
        int users = 40;
        ExecutorService pool = Executors.newFixedThreadPool(users);
        CountDownLatch ready = new CountDownLatch(users);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(users);
        AtomicInteger rejected = new AtomicInteger();
        List<GameRelaySlots.Slot> held = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < users; i++) {
                String id = "GRD" + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        held.add(slots.acquire(id));
                    } catch (CustomException e) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finish.countDown();
                    }
                });
            }
            ready.await();
            go.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(held).hasSize(4);
            assertThat(rejected.get()).isEqualTo(users - 4);
            assertThat(slots.activeUsers()).isEqualTo(4);

            held.forEach(GameRelaySlots.Slot::close);
            assertThat(slots.activeUsers()).isZero();
            assertThat(slots.available()).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }
}
