package kr.silverbridge.main.domain.user.service;

import kr.silverbridge.main.domain.auth.service.SmsService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.client.FileServerClient;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private FileServerClient fileServerClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SmsService smsService;

    @InjectMocks private UserService userService;

    private static final String USER_ID = "user-uuid-1234";

    // ─── changePassword ─────────────────────────────────────────────────────

    @Test
    @DisplayName("카카오 계정은 비밀번호 변경 불가 → SOCIAL_USER_NO_PASSWORD")
    void changePassword_카카오계정_SOCIAL_USER_NO_PASSWORD() {
        User kakaoUser = kakaoUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(kakaoUser));

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.changePassword(USER_ID, "any", "NewPass1!"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SOCIAL_USER_NO_PASSWORD);
    }

    @Test
    @DisplayName("현재 비밀번호 불일치 → INVALID_PASSWORD")
    void changePassword_현재비밀번호불일치_INVALID_PASSWORD() {
        User user = localUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongCurrent", "encodedPassword")).thenReturn(false);

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.changePassword(USER_ID, "wrongCurrent", "NewPass1!"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PASSWORD);
    }

    @Test
    @DisplayName("새 비밀번호가 현재 비밀번호와 동일 → SAME_AS_CURRENT_PASSWORD")
    void changePassword_현재와동일한새비밀번호_SAME_AS_CURRENT_PASSWORD() {
        User user = localUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        // 현재 비밀번호 검증(true) → 통과, 새 비밀번호 == 현재 비밀번호 검증(true) → SAME_AS_CURRENT_PASSWORD
        when(passwordEncoder.matches("currentPass", "encodedPassword")).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.changePassword(USER_ID, "currentPass", "currentPass"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SAME_AS_CURRENT_PASSWORD);
    }

    // ─── withdraw ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("일반 계정 탈퇴 시 비밀번호 불일치 → INVALID_PASSWORD")
    void withdraw_비밀번호불일치_INVALID_PASSWORD() {
        User user = localUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "encodedPassword")).thenReturn(false);

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.withdraw(USER_ID, "wrongPassword", null, "127.0.0.1", "test-agent"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PASSWORD);
    }

    @Test
    @DisplayName("카카오 계정은 confirmation \"탈퇴\" 일치 시 탈퇴 + UserWithdrawnEvent 발행")
    void withdraw_카카오계정_confirmation일치_탈퇴() {
        User kakaoUser = kakaoUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(kakaoUser));

        userService.withdraw(USER_ID, null, "탈퇴", "127.0.0.1", "test-agent");

        assertThat(kakaoUser.getStatus()).isEqualTo(Status.INACTIVE);

        ArgumentCaptor<UserWithdrawnEvent> captor = ArgumentCaptor.forClass(UserWithdrawnEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().ipAddress()).isEqualTo("127.0.0.1");
        assertThat(captor.getValue().userAgent()).isEqualTo("test-agent");
    }

    @Test
    @DisplayName("카카오 계정 탈퇴 시 confirmation 누락 → WITHDRAW_CONFIRMATION_MISMATCH")
    void withdraw_카카오계정_confirmation누락_거부() {
        User kakaoUser = kakaoUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(kakaoUser));

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.withdraw(USER_ID, null, null, "127.0.0.1", "test-agent"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WITHDRAW_CONFIRMATION_MISMATCH);
        assertThat(kakaoUser.getStatus()).isNotEqualTo(Status.INACTIVE);
    }

    @Test
    @DisplayName("카카오 계정 탈퇴 시 confirmation 불일치 → WITHDRAW_CONFIRMATION_MISMATCH")
    void withdraw_카카오계정_confirmation불일치_거부() {
        User kakaoUser = kakaoUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(kakaoUser));

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.withdraw(USER_ID, null, "탈퇴하기", "127.0.0.1", "test-agent"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WITHDRAW_CONFIRMATION_MISMATCH);
    }

    // ─── 헬퍼 메서드 ────────────────────────────────────────────────────────

    private User localUser() {
        return User.builder()
                .id(USER_ID)
                .email("local@example.com")
                .password("encodedPassword")
                .name("일반사용자")
                .role(Role.WARD)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .build();
    }

    private User kakaoUser() {
        return User.builder()
                .id(USER_ID)
                .email("kakao@example.com")
                .name("카카오사용자")
                .role(Role.WARD)
                .status(Status.ACTIVE)
                .provider(Provider.KAKAO)
                .build();
    }
}
