package kr.silverbridge.main.domain.chat.service;

import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.global.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 동시 상한은 여러 스레드가 한꺼번에 들어와도 넘지 않는다. */
class ChatSlotsTest {

    @Test
    @DisplayName("같은 보호자가 50개 스레드로 동시에 획득을 시도해도 1건만 성공한다")
    void 동시_획득_1인_상한() throws Exception {
        ChatRelayProperties properties = new ChatRelayProperties();   // 1인 1, 전체 10
        ChatSlots slots = new ChatSlots(properties);
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threads);
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<ChatSlots.Slot> held = java.util.Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    held.add(slots.acquire("GRD001"));
                    acquired.incrementAndGet();
                } catch (CustomException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finish.countDown();
                }
            }));
        }
        ready.await();
        go.countDown();
        assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(acquired.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);

        held.forEach(ChatSlots.Slot::close);
        assertThat(slots.activeUsers()).isZero();
        pool.shutdownNow();
    }

    @Test
    @DisplayName("보호자 50명이 동시에 시도하면 전체 상한(10)만 성공하고 반환하면 다시 얻을 수 있다")
    void 동시_획득_전체_상한() throws Exception {
        ChatRelayProperties properties = new ChatRelayProperties();
        ChatSlots slots = new ChatSlots(properties);
        int users = 50;
        ExecutorService pool = Executors.newFixedThreadPool(users);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(users);
        AtomicInteger acquired = new AtomicInteger();
        List<ChatSlots.Slot> held = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < users; i++) {
            String id = "GRD" + i;
            pool.submit(() -> {
                try {
                    go.await();
                    held.add(slots.acquire(id));
                    acquired.incrementAndGet();
                } catch (CustomException e) {
                    // 상한 초과
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finish.countDown();
                }
            });
        }
        go.countDown();
        assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(acquired.get()).isEqualTo(properties.getMaxConcurrent());

        held.forEach(ChatSlots.Slot::close);
        assertThat(slots.activeUsers()).isZero();
        slots.acquire("AFTER").close();   // 반환 뒤 다시 얻을 수 있다
        pool.shutdownNow();
    }
}
