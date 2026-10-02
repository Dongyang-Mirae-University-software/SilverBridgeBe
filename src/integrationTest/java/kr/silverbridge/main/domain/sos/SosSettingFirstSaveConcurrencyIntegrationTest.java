package kr.silverbridge.main.domain.sos;

import kr.silverbridge.main.domain.sos.dto.SosSettingUpdateRequest;
import kr.silverbridge.main.domain.sos.entity.SosAction;
import kr.silverbridge.main.domain.sos.entity.SosSetting;
import kr.silverbridge.main.domain.sos.repository.SosSettingRepository;
import kr.silverbridge.main.domain.sos.service.SosSettingService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOS 설정 최초 저장이 동시에 와도 둘 다 성공하는가 (SOS-G17).
 *
 * <p>"조회 후 없으면 save"였을 때는 두 요청이 모두 "없음"을 보고 INSERT해 {@code uq_sos_setting_user} 위반(409)이
 * 났다. 지금은 {@code INSERT ... ON CONFLICT DO NOTHING} 후 0건이면 재조회·갱신한다. 경합은 확률적이라 여러 번 돌린다.</p>
 *
 * <p>테스트 트랜잭션을 끄고 실제로 커밋한다. 회원 삭제(CASCADE)로 설정 행까지 정리한다.</p>
 */
@Import(SosSettingService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SosSettingFirstSaveConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final String WARD_ID = "WSS001";
    private static final int ROUNDS = 20;

    @Autowired private SosSettingService sosSettingService;
    @Autowired private SosSettingRepository sosSettingRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAllById(List.of(WARD_ID));
    }

    @Test
    @DisplayName("설정 행이 없는 상태에서 동시에 두 번 저장해도 둘 다 성공하고 행은 하나다")
    void 동시_최초저장은_둘다_성공한다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                sosSettingRepository.findByUserId(WARD_ID).ifPresent(sosSettingRepository::delete);
                CyclicBarrier start = new CyclicBarrier(2);

                CompletableFuture<?> a = CompletableFuture.runAsync(
                        () -> saveAt(start, SosAction.CALL_119), pool);
                CompletableFuture<?> b = CompletableFuture.runAsync(
                        () -> saveAt(start, SosAction.NOTIFY_GUARDIAN_FIRST), pool);
                // 어느 쪽이든 예외(409)가 나면 get()이 던진다
                CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);

                assertThat(sosSettingRepository.findByUserId(WARD_ID))
                        .as("round %d", round)
                        .get()
                        .extracting(SosSetting::getSosAction)
                        .isIn(SosAction.CALL_119, SosAction.NOTIFY_GUARDIAN_FIRST);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void saveAt(CyclicBarrier start, SosAction action) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        sosSettingService.updateSetting(WARD_ID, new SosSettingUpdateRequest(action));
    }
}
