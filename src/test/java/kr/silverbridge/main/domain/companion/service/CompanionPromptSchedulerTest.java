package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionPromptLogRepository;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 테스트 안내 푸시(2026-11-10) - 디스패처로 1건, data에 slot·test=true, 선점 기록 없음. */
class CompanionPromptSchedulerTest {

    private static final String TESTER = "TW0001";

    private CompanionPromptLogRepository promptLogRepository;
    private UserRepository userRepository;
    private NotificationDispatcher dispatcher;
    private CompanionPromptScheduler scheduler;

    @BeforeEach
    void setUp() {
        promptLogRepository = mock(CompanionPromptLogRepository.class);
        userRepository = mock(UserRepository.class);
        dispatcher = mock(NotificationDispatcher.class);
        scheduler = new CompanionPromptScheduler(new CompanionProperties(), promptLogRepository, userRepository, dispatcher);
    }

    @Test
    @DisplayName("테스트 안내: 본인에게 COMPANION_PROMPT 1건(data에 slot·test=true), companion_prompt_log는 남기지 않는다")
    void sendsOneWithoutPromptLog() {
        User active = user(Role.WARD, Status.ACTIVE);
        when(userRepository.findById(TESTER)).thenReturn(Optional.of(active));

        scheduler.sendTestPrompt(TESTER, CompanionSlot.S18);

        ArgumentCaptor<NotificationContent> content = ArgumentCaptor.forClass(NotificationContent.class);
        verify(dispatcher).dispatch(org.mockito.ArgumentMatchers.eq(TESTER), org.mockito.ArgumentMatchers.eq(TESTER),
                org.mockito.ArgumentMatchers.eq(NotificationType.COMPANION_PROMPT), content.capture());
        assertThat(content.getValue().data())
                .containsEntry("type", "COMPANION_PROMPT").containsEntry("slot", "18:00").containsEntry("test", "true");
        assertThat(content.getValue().body()).isEqualTo("오후 6시 안부 인사 시간이에요. 오늘 이야기를 들려주세요.");
        verifyNoInteractions(promptLogRepository);
    }

    @Test
    @DisplayName("정각 안내: 선점 기록 후 data는 type·slot뿐이다 - test 키가 없다(점검 M-3)")
    void regularPromptHasNoTestKey() {
        User active = user(Role.WARD, Status.ACTIVE);
        when(userRepository.findById(TESTER)).thenReturn(Optional.of(active));

        scheduler.sendOne(TESTER, CompanionSlot.S18,
                java.time.ZonedDateTime.of(2026, 11, 10, 18, 0, 0, 0, CompanionService.KST));

        ArgumentCaptor<NotificationContent> content = ArgumentCaptor.forClass(NotificationContent.class);
        verify(dispatcher).dispatch(org.mockito.ArgumentMatchers.eq(TESTER), org.mockito.ArgumentMatchers.eq(TESTER),
                org.mockito.ArgumentMatchers.eq(NotificationType.COMPANION_PROMPT), content.capture());
        assertThat(content.getValue().data())
                .isEqualTo(java.util.Map.of("type", "COMPANION_PROMPT", "slot", "18:00"));
        verify(promptLogRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("테스트 안내: 정지 계정이면 보내지 않는다")
    void skipsRestricted() {
        User restricted = user(Role.WARD, Status.RESTRICTED);
        when(userRepository.findById(TESTER)).thenReturn(Optional.of(restricted));

        scheduler.sendTestPrompt(TESTER, CompanionSlot.S18);

        verify(dispatcher, never()).dispatch(any(), any(), any(), any());
    }

    private static User user(Role role, Status status) {
        User user = mock(User.class);
        when(user.getRole()).thenReturn(role);
        when(user.getStatus()).thenReturn(status);
        return user;
    }
}
