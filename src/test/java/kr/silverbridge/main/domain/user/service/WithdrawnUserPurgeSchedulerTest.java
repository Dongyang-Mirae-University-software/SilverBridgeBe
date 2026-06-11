package kr.silverbridge.main.domain.user.service;

import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WithdrawnUserPurgeSchedulerTest {

    @Mock private UserRepository userRepository;
    @Mock private UserService userService;

    @InjectMocks private WithdrawnUserPurgeScheduler scheduler;

    private User stuckUser(String id) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        return user;
    }

    @Test
    @DisplayName("grace 지난 INACTIVE(좀비) 계정 발견 시 purgeWithdrawnUser로 회수 (M-S1-1)")
    void 좀비_계정_스윕_회수() {
        List<User> zombies = List.of(stuckUser("zombie-1"), stuckUser("zombie-2"));
        when(userRepository.findAllByStatusAndUpdatedAtBefore(eq(Status.INACTIVE), any(OffsetDateTime.class)))
                .thenReturn(zombies);

        scheduler.purgeStuckWithdrawnUsers();

        verify(userService).purgeWithdrawnUser("zombie-1");
        verify(userService).purgeWithdrawnUser("zombie-2");
    }

    @Test
    @DisplayName("대상 없음 → purge 미호출 (정상 상태에서 no-op)")
    void 좀비_없으면_noop() {
        when(userRepository.findAllByStatusAndUpdatedAtBefore(eq(Status.INACTIVE), any(OffsetDateTime.class)))
                .thenReturn(List.of());

        scheduler.purgeStuckWithdrawnUsers();

        verify(userService, never()).purgeWithdrawnUser(any());
    }

    @Test
    @DisplayName("한 건 purge 실패해도 예외 미전파 + 나머지 계정은 계속 회수 (다음 주기 재시도 전제)")
    void purge_실패_격리_및_계속_진행() {
        List<User> zombies = List.of(stuckUser("zombie-1"), stuckUser("zombie-2"));
        when(userRepository.findAllByStatusAndUpdatedAtBefore(eq(Status.INACTIVE), any(OffsetDateTime.class)))
                .thenReturn(zombies);
        doThrow(new RuntimeException("DB 순단")).when(userService).purgeWithdrawnUser("zombie-1");

        assertThatCode(() -> scheduler.purgeStuckWithdrawnUsers()).doesNotThrowAnyException();

        verify(userService).purgeWithdrawnUser("zombie-2");
    }
}
