package kr.silverbridge.main.domain.camera;

import kr.silverbridge.main.domain.camera.dto.CameraRegisterRequest;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.camera.service.CameraIdentifierFactory;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서비스 전체 카메라 등록 상한이 동시 등록에도 지켜지는가 (camera.max-total, 2026-10-14).
 *
 * <p>카운트 → INSERT 사이 경합은 어드바이저리 락({@code pg_advisory_xact_lock})으로 한 줄로 세워 막는다. 서로 다른
 * 피보호자 8명이 같은 순간 등록해도 정확히 상한만큼만 성공해야 한다. 락을 빼면 상한을 넘는 회차가 생긴다.</p>
 */
@Import({CameraService.class, CameraIdentifierFactory.class})
@TestPropertySource(properties = "camera.max-total=3")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CameraTotalLimitConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final int WARDS = 8;
    private static final int LIMIT = 3;
    private static final int ROUNDS = 5;

    @Autowired private CameraService cameraService;
    @Autowired private CameraRepository cameraRepository;
    @Autowired private UserRepository userRepository;

    @MockitoBean private ConnectionService connectionService;

    private final List<String> wardIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        for (int i = 0; i < WARDS; i++) {
            String id = "WLM" + String.format("%03d", i);
            wardIds.add(id);
            userRepository.save(TestData.user(id, "피보호자" + i, Role.WARD));
        }
    }

    @AfterEach
    void tearDown() {
        cameraRepository.deleteAll();
        userRepository.deleteAllById(wardIds);
    }

    @Test
    @DisplayName("동시 등록 8건 × 5회 - 매 회차 정확히 상한(3)만큼만 성공, 나머지는 CAMERA_LIMIT_REACHED")
    void 동시_등록이_상한을_넘지_않는다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            cameraRepository.deleteAll();

            AtomicInteger ok = new AtomicInteger();
            AtomicInteger limited = new AtomicInteger();
            CyclicBarrier barrier = new CyclicBarrier(WARDS);
            ExecutorService pool = Executors.newFixedThreadPool(WARDS);
            try {
                List<CompletableFuture<Void>> tasks = new ArrayList<>();
                for (String wardId : wardIds) {
                    tasks.add(CompletableFuture.runAsync(() -> {
                        try {
                            barrier.await(10, TimeUnit.SECONDS);
                            cameraService.register(wardId, new CameraRegisterRequest("거실", null));
                            ok.incrementAndGet();
                        } catch (CustomException e) {
                            if (e.getErrorCode() == ErrorCode.CAMERA_LIMIT_REACHED) {
                                limited.incrementAndGet();
                            } else {
                                throw e;
                            }
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }, pool));
                }
                CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(ok.get()).as("round %d 성공 수", round).isEqualTo(LIMIT);
            assertThat(limited.get()).as("round %d 거절 수", round).isEqualTo(WARDS - LIMIT);
            assertThat(cameraRepository.count()).as("round %d 저장 수", round).isEqualTo(LIMIT);
        }
    }

    @Test
    @DisplayName("상한이어도 같은 기기 재등록은 통과하고, 삭제로 자리가 나면 다시 등록된다")
    void 재등록은_통과하고_삭제하면_자리가_난다() {
        String deviceId = null;
        for (int i = 0; i < LIMIT; i++) {
            var res = cameraService.register(wardIds.get(i), new CameraRegisterRequest("거실", null));
            if (i == 0) {
                deviceId = res.deviceId();
            }
        }

        // 상한 도달 - 새 피보호자는 거절, 첫 피보호자의 같은 기기 재등록(방 이름 그대로)은 통과
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cameraService.register(wardIds.get(LIMIT), new CameraRegisterRequest("거실", null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_LIMIT_REACHED);
        assertThat(cameraService.register(wardIds.get(0), new CameraRegisterRequest("거실", deviceId)).deviceId())
                .isEqualTo(deviceId);

        // 한 대 삭제하면 새 등록이 된다
        Long firstId = cameraRepository.findByWardIdOrderByCreatedAtDesc(wardIds.get(0)).get(0).getId();
        cameraService.delete(wardIds.get(0), firstId);
        assertThat(cameraService.register(wardIds.get(LIMIT), new CameraRegisterRequest("거실", null)).sessionId())
                .isNotBlank();
    }
}
