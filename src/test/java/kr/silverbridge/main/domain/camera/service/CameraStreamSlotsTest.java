package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시 시청 상한 - 영상 1건이 요청 스레드 1개를 시청 내내 붙들어, 상한이 없으면 일반 API까지 멈춘다.
 */
class CameraStreamSlotsTest {

    private static CameraStreamSlots slots(int global, int perUser) {
        CameraStreamProperties properties = new CameraStreamProperties();
        properties.setMaxConcurrentStreams(global);
        properties.setMaxStreamsPerUser(perUser);
        return new CameraStreamSlots(properties);
    }

    private static ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (CustomException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("예외가 나지 않았다");
    }

    @Test
    @DisplayName("보호자 1명은 2개까지 - 3번째는 429, 하나 닫으면 다시 열 수 있다")
    void 사용자별_상한() {
        CameraStreamSlots slots = slots(20, 2);
        CameraStreamSlots.Slot first = slots.acquire("G1", "S1");
        slots.acquire("G1", "S2");

        assertThat(errorOf(() -> slots.acquire("G1", "S3"))).isEqualTo(ErrorCode.CAMERA_STREAM_LIMIT_EXCEEDED);

        first.close();
        assertThat(slots.acquire("G1", "S4")).isNotNull();
    }

    @Test
    @DisplayName("서버 전체 상한 - 다른 사람이어도 넘치면 429이고, 거부된 시도는 그 사람의 자리를 차지하지 않는다")
    void 전체_상한() {
        CameraStreamSlots slots = slots(2, 2);
        slots.acquire("G1", "S5");
        CameraStreamSlots.Slot g2 = slots.acquire("G2", "S6");

        assertThat(errorOf(() -> slots.acquire("G3", "S7"))).isEqualTo(ErrorCode.CAMERA_STREAM_LIMIT_EXCEEDED);

        g2.close();
        slots.acquire("G3", "S8");
        assertThat(slots.activeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("닫기는 여러 번 불러도 자리를 한 번만 돌려준다(이중 반납으로 상한이 늘지 않는다)")
    void 이중반납_방지() {
        CameraStreamSlots slots = slots(1, 1);
        CameraStreamSlots.Slot slot = slots.acquire("G1", "S9");
        slot.close();
        slot.close();

        slots.acquire("G1", "S10");
        assertThat(errorOf(() -> slots.acquire("G2", "S11"))).isEqualTo(ErrorCode.CAMERA_STREAM_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("닫으면 붙여 둔 AI 연결도 닫는다(시청자가 떠나도 AI 연결이 남지 않는다)")
    void 닫기_AI연결정리() {
        CameraStreamSlots slots = slots(20, 2);
        AtomicInteger closed = new AtomicInteger();
        CameraStreamSlots.Slot slot = slots.acquire("G1", "S12");
        slot.attach(closed::incrementAndGet);

        slot.close();

        assertThat(closed).hasValue(1);
        assertThat(slots.activeCount()).isZero();
    }

    @Test
    @DisplayName("서버 종료: 열린 영상의 AI 연결을 모두 닫고 새 영상은 503으로 거부한다(우아한 종료가 영상에 묶이지 않게)")
    void 종료() {
        CameraStreamSlots slots = slots(20, 2);
        List<Integer> closed = new ArrayList<>();
        slots.acquire("G1", "S13").attach(() -> closed.add(1));
        slots.acquire("G2", "S14").attach(() -> closed.add(2));

        slots.stop();

        assertThat(closed).containsExactlyInAnyOrder(1, 2);
        assertThat(slots.isAccepting()).isFalse();
        assertThat(errorOf(() -> slots.acquire("G3", "S15"))).isEqualTo(ErrorCode.CAMERA_STREAM_UNAVAILABLE);
        assertThat(slots.getPhase()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("종료 뒤에 붙인 연결은 즉시 닫는다")
    void 종료후_attach() {
        CameraStreamSlots slots = slots(20, 2);
        CameraStreamSlots.Slot slot = slots.acquire("G1", "S16");
        slots.stop();
        AtomicInteger closed = new AtomicInteger();

        slot.attach(closed::incrementAndGet);

        assertThat(closed).hasValue(1);
    }

    @Test
    @DisplayName("같은 보호자가 동시에 10번 열어도 상한(2)을 넘지 않는다")
    void 동시요청_상한유지() throws Exception {
        CameraStreamSlots slots = slots(20, 2);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            int n = i;
            pool.submit(() -> {
                start.await();
                try {
                    slots.acquire("G1", "S" + n);
                    acquired.incrementAndGet();
                } catch (CustomException ignored) {
                    // 상한 초과
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(acquired).hasValue(2);
    }

    @Test
    @DisplayName("같은 보호자가 같은 카메라를 다시 열면 이전 중계를 닫고 자리를 넘긴다(상한에 걸려 거절되지 않는다)")
    void 같은카메라_재시청_대체() {
        CameraStreamSlots slots = slots(20, 2);
        AtomicInteger livingClosed = new AtomicInteger();
        AtomicInteger bedroomClosed = new AtomicInteger();
        slots.acquire("G1", "living").attach(livingClosed::incrementAndGet);
        slots.acquire("G1", "bedroom").attach(bedroomClosed::incrementAndGet);

        CameraStreamSlots.Slot reopened = slots.acquire("G1", "living");

        assertThat(reopened).isNotNull();
        assertThat(livingClosed).hasValue(1);
        assertThat(bedroomClosed).hasValue(0);   // 다른 카메라는 건드리지 않는다
        assertThat(slots.activeCount()).isEqualTo(2);
        // 상한 정책은 그대로 - 다른 카메라를 새로 열면 여전히 429
        assertThat(errorOf(() -> slots.acquire("G1", "kitchen"))).isEqualTo(ErrorCode.CAMERA_STREAM_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("다른 보호자가 같은 카메라를 열어도 기존 보호자의 중계는 닫지 않는다")
    void 다른보호자_같은카메라() {
        CameraStreamSlots slots = slots(20, 2);
        AtomicInteger g1Closed = new AtomicInteger();
        slots.acquire("G1", "living").attach(g1Closed::incrementAndGet);

        slots.acquire("G2", "living");

        assertThat(g1Closed).hasValue(0);
        assertThat(slots.activeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("AI 연결을 붙이기 전에 같은 카메라 다시 열기로 대체되면, 뒤늦게 붙인 연결은 즉시 닫힌다 (2026-10 점검 H-1)")
    void 대체후_attach() {
        CameraStreamSlots slots = slots(20, 1);
        CameraStreamSlots.Slot previous = slots.acquire("G1", "living");
        slots.acquire("G1", "living");   // 이전 슬롯은 연결이 아직 없어 자리만 반납된다
        AtomicInteger closed = new AtomicInteger();

        previous.attach(closed::incrementAndGet);

        assertThat(closed).hasValue(1);
        assertThat(previous.isReleased()).isTrue();
        assertThat(slots.activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("대체되지 않은 자리에 붙인 연결은 닫지 않는다")
    void 정상_attach() {
        CameraStreamSlots slots = slots(20, 2);
        CameraStreamSlots.Slot slot = slots.acquire("G1", "living");
        AtomicInteger closed = new AtomicInteger();

        slot.attach(closed::incrementAndGet);

        assertThat(closed).hasValue(0);
        assertThat(slot.isReleased()).isFalse();
        slot.close();
        assertThat(closed).hasValue(1);
    }

    @Test
    @DisplayName("대체된 이전 중계가 나중에 스스로 닫혀도 자리를 두 번 돌려주지 않는다")
    void 대체후_이전슬롯_닫기() {
        CameraStreamSlots slots = slots(20, 1);
        CameraStreamSlots.Slot previous = slots.acquire("G1", "living");
        slots.acquire("G1", "living");

        previous.close();   // 중계 루프가 빠져나오며 try-with-resources로 다시 닫는 경우

        assertThat(slots.activeCount()).isEqualTo(1);
        assertThat(errorOf(() -> slots.acquire("G1", "bedroom"))).isEqualTo(ErrorCode.CAMERA_STREAM_LIMIT_EXCEEDED);
    }
}
