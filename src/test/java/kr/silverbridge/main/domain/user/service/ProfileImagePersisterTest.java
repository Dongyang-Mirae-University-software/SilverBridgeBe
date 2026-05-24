package kr.silverbridge.main.domain.user.service;

import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileImagePersisterTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private ProfileImagePersister persister;

    private static final String USER_ID = "user-uuid-1234";
    private static final String OLD_URL = "https://files.example.com/file/old.png";
    private static final String NEW_URL = "https://files.example.com/file/new.png";

    @Test
    @DisplayName("새 URL 영속화 → 엔티티 갱신 + 교체 전 URL·갱신 프로필 반환")
    void replace_성공() {
        User user = localUser();
        user.updateProfileImage(OLD_URL);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ProfileImagePersister.Result result = persister.replace(USER_ID, NEW_URL);

        assertThat(user.getProfileImage()).isEqualTo(NEW_URL);            // dirty checking 대상
        assertThat(result.oldImageUrl()).isEqualTo(OLD_URL);             // 삭제 대상
        assertThat(result.response().getProfileImage()).isEqualTo(NEW_URL);
    }

    @Test
    @DisplayName("사용자 없음 → USER_NOT_FOUND")
    void replace_사용자없음_USER_NOT_FOUND() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> persister.replace(USER_ID, NEW_URL));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    private User localUser() {
        return User.builder()
                .id(USER_ID).email("local@example.com").password("enc").name("일반사용자")
                .role(Role.WARD).status(Status.ACTIVE).provider(Provider.LOCAL)
                .build();
    }
}
