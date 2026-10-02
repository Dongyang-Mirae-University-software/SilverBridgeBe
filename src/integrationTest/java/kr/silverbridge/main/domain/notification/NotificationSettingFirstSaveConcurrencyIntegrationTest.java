package kr.silverbridge.main.domain.notification;

import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.dto.NotificationSettingUpdateRequest;
import kr.silverbridge.main.domain.notification.dto.NotificationSettingUpdateRequest.ChannelSettingUpdate;
import kr.silverbridge.main.domain.notification.repository.UserNotificationSettingRepository;
import kr.silverbridge.main.domain.notification.service.NotificationSettingService;
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
 * 알림 채널 설정을 동시에 처음 켜도 모두 성공하는가 (USER-G12).
 *
 * <p>"조회 후 없으면 save"였을 때는 같은 채널을 동시에 처음 켜면 {@code uq_user_notif_channel} 위반(409)이 났다.
 * 지금은 {@code INSERT ... ON CONFLICT DO NOTHING} 후 0건이면 재조회·갱신한다. 경합은 확률적이라 여러 번 돌린다.</p>
 *
 * <p>테스트 트랜잭션을 끄고 실제로 커밋한다. 회원 삭제(CASCADE)로 설정 행까지 정리한다.</p>
 */
@Import(NotificationSettingService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationSettingFirstSaveConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final String USER_ID = "UNS001";
    private static final int ROUNDS = 20;
    private static final int CONCURRENCY = 3;

    @Autowired private NotificationSettingService notificationSettingService;
    @Autowired private UserNotificationSettingRepository repository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(USER_ID, "보호자", Role.GUARDIAN));
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAllById(List.of(USER_ID));
    }

    @Test
    @DisplayName("같은 채널을 동시에 처음 켜도 모두 성공하고 (사용자, 채널) 행은 하나다")
    void 동시_최초저장은_모두_성공한다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                repository.deleteAll(repository.findByUserId(USER_ID));
                CyclicBarrier start = new CyclicBarrier(CONCURRENCY);

                CompletableFuture<?>[] futures = new CompletableFuture<?>[CONCURRENCY];
                for (int i = 0; i < CONCURRENCY; i++) {
                    futures[i] = CompletableFuture.runAsync(() -> enableEmailAt(start), pool);
                }
                // 어느 쪽이든 예외(409)가 나면 get()이 던진다
                CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);

                assertThat(repository.findByUserId(USER_ID))
                        .as("round %d", round)
                        .singleElement()
                        .satisfies(s -> {
                            assertThat(s.getChannelType()).isEqualTo(NotificationChannelType.EMAIL);
                            assertThat(s.isEnabled()).isTrue();
                        });
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void enableEmailAt(CyclicBarrier start) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        notificationSettingService.updateSettings(USER_ID, new NotificationSettingUpdateRequest(List.of(
                new ChannelSettingUpdate(NotificationChannelType.EMAIL, true))));
    }
}
