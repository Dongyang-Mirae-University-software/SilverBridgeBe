package kr.silverbridge.main.domain.user.service;

import kr.silverbridge.main.domain.user.dto.UserProfileResponse;
import kr.silverbridge.main.domain.user.dto.UserUpdateRequest;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.port.PhoneVerificationPort;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.client.FileServerClient;
import kr.silverbridge.main.global.enums.Gender;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private FileServerClient fileServerClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PhoneVerificationPort phoneVerificationPort;
    @Mock private ProfileImagePersister profileImagePersister;

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

    // ─── deleteProfileImage ─────────────────────────────────────────────────

    private static final String IMAGE_URL = "https://files.example.com/file/abc.png";

    @Test
    @DisplayName("프로필 이미지가 있는 사용자 삭제 → true 반환 + DB NULL + 파일 서버 삭제 호출")
    void deleteProfileImage_이미지있음_삭제성공() {
        User user = localUser();
        user.updateProfileImage(IMAGE_URL);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        boolean deleted = userService.deleteProfileImage(USER_ID);

        assertThat(deleted).isTrue(); // 실제 삭제 → 안내 메시지 분기용
        assertThat(user.getProfileImage()).isNull();
        verify(fileServerClient).delete(IMAGE_URL);
    }

    @Test
    @DisplayName("프로필 이미지가 없는 사용자 삭제 → false 반환(멱등 no-op), 파일 서버 미호출")
    void deleteProfileImage_이미지없음_멱등() {
        User user = localUser(); // profileImage == null
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        boolean deleted = userService.deleteProfileImage(USER_ID);

        assertThat(deleted).isFalse(); // 삭제 대상 없음 → "기본 이미지 사용 중" 안내용
        assertThat(user.getProfileImage()).isNull();
        verifyNoInteractions(fileServerClient);
    }

    @Test
    @DisplayName("파일 서버 삭제 결과와 무관하게 DB는 NULL 처리 (예외 비전파)")
    void deleteProfileImage_파일서버삭제무관_DB는NULL() {
        User user = localUser();
        user.updateProfileImage(IMAGE_URL);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        // FileServerClient.delete 는 내부에서 모든 예외를 삼키고 WARN 로깅만 하는 계약(throw 안 함).
        // DB는 delete 호출 전에 이미 NULL 로 비워지므로, 파일 서버 실패 여부와 무관하게 NULL 이 유지된다.

        assertThatCode(() -> userService.deleteProfileImage(USER_ID)).doesNotThrowAnyException();

        assertThat(user.getProfileImage()).isNull();
        verify(fileServerClient).delete(IMAGE_URL);
    }

    @Test
    @DisplayName("존재하지 않는 사용자 삭제 → USER_NOT_FOUND")
    void deleteProfileImage_사용자없음_USER_NOT_FOUND() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.deleteProfileImage(USER_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        verifyNoInteractions(fileServerClient);
    }

    // ─── changePassword 성공 (F-USER-1) ──────────────────────────────────────

    @Test
    @DisplayName("비밀번호 변경 성공 → 새 비밀번호 인코딩 교체 + PasswordChangedEvent 발행")
    void changePassword_성공_이벤트발행() {
        User user = localUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("currentPass", "encodedPassword")).thenReturn(true);
        when(passwordEncoder.matches("NewPass1!", "encodedPassword")).thenReturn(false);
        when(passwordEncoder.encode("NewPass1!")).thenReturn("encodedNew");

        userService.changePassword(USER_ID, "currentPass", "NewPass1!");

        assertThat(user.getPassword()).isEqualTo("encodedNew");
        ArgumentCaptor<PasswordChangedEvent> captor = ArgumentCaptor.forClass(PasswordChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
    }

    // ─── getMyProfile (F-USER-5) ─────────────────────────────────────────────

    @Test
    @DisplayName("내 정보 조회 성공 → 프로필 응답 매핑")
    void getMyProfile_성공() {
        User user = localUserWithPhone("01011112222");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        UserProfileResponse res = userService.getMyProfile(USER_ID);

        assertThat(res.getId()).isEqualTo(USER_ID);
        assertThat(res.getEmail()).isEqualTo("local@example.com");
        assertThat(res.getPhone()).isEqualTo("01011112222");
        assertThat(res.getProvider()).isEqualTo("LOCAL");
    }

    @Test
    @DisplayName("내 정보 조회 시 사용자 없음 → USER_NOT_FOUND")
    void getMyProfile_사용자없음_USER_NOT_FOUND() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.getMyProfile(USER_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ─── updateProfile (F-USER-2) ────────────────────────────────────────────

    @Test
    @DisplayName("전화번호 미변경 시 SMS 인증·중복검사 스킵하고 프로필 수정")
    void updateProfile_전화번호미변경_인증스킵() {
        User user = localUserWithPhone("01011112222");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        UserProfileResponse res = userService.updateProfile(USER_ID, updateRequest("01011112222", null));

        assertThat(res.getName()).isEqualTo("새이름");
        assertThat(user.getPhone()).isEqualTo("01011112222");
        verifyNoInteractions(phoneVerificationPort);
        verify(userRepository, never()).existsByPhone(anyString());
    }

    @Test
    @DisplayName("전화번호 변경 시 SMS 인증 소비 + 중복 아니면 변경 성공")
    void updateProfile_전화번호변경_성공() {
        User user = localUserWithPhone("01011112222");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhone("01099998888")).thenReturn(false);

        userService.updateProfile(USER_ID, updateRequest("01099998888", "nonce-123"));

        verify(phoneVerificationPort).consumeVerification("01099998888", "nonce-123");
        assertThat(user.getPhone()).isEqualTo("01099998888");
    }

    @Test
    @DisplayName("전화번호 변경 시 이미 사용 중이면 PHONE_ALREADY_EXISTS (인증은 검사 전에 소비됨)")
    void updateProfile_전화번호중복_PHONE_ALREADY_EXISTS() {
        User user = localUserWithPhone("01011112222");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhone("01099998888")).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.updateProfile(USER_ID, updateRequest("01099998888", "nonce-123")));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PHONE_ALREADY_EXISTS);
        verify(phoneVerificationPort).consumeVerification("01099998888", "nonce-123");
        assertThat(user.getPhone()).isEqualTo("01011112222");
    }

    @Test
    @DisplayName("정보 수정 시 사용자 없음 → USER_NOT_FOUND")
    void updateProfile_사용자없음_USER_NOT_FOUND() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.updateProfile(USER_ID, updateRequest("01011112222", null)));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ─── updateProfileImage (F-USER-3 / A-USER-2) ────────────────────────────

    // 검증(크기·타입·시그니처)은 업로드·영속화보다 먼저 수행되므로, 실패 시 파일 서버·영속화는 호출되지 않는다.

    @Test
    @DisplayName("프로필 이미지 5MB 초과 → FILE_TOO_LARGE, 업로드·영속화 미호출")
    void updateProfileImage_크기초과_FILE_TOO_LARGE() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(6 * 1024 * 1024L);

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.updateProfileImage(USER_ID, file));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE);
        verifyNoInteractions(fileServerClient, profileImagePersister);
    }

    @Test
    @DisplayName("허용되지 않는 Content-Type → INVALID_FILE_TYPE")
    void updateProfileImage_잘못된타입_INVALID_FILE_TYPE() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1024L);
        when(file.getContentType()).thenReturn("application/pdf");

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.updateProfileImage(USER_ID, file));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_FILE_TYPE);
        verifyNoInteractions(fileServerClient, profileImagePersister);
    }

    @Test
    @DisplayName("Content-Type은 image/png이나 실제 시그니처 위조 → INVALID_FILE_TYPE (A-USER-2)")
    void updateProfileImage_시그니처위조_INVALID_FILE_TYPE() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1024L);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("NOT_AN_IMAGE".getBytes()));

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.updateProfileImage(USER_ID, file));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_FILE_TYPE);
        verifyNoInteractions(fileServerClient, profileImagePersister);
    }

    @Test
    @DisplayName("프로필 이미지 변경 성공 → 업로드(트랜잭션 밖) + 영속화 위임 + 기존 파일 삭제")
    void updateProfileImage_성공_업로드_영속화_기존삭제() throws Exception {
        String oldUrl = "https://files.example.com/file/old.png";
        String newUrl = "https://files.example.com/file/new.png";
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(2048L);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(PNG_HEADER));
        when(fileServerClient.upload(file)).thenReturn(newUrl);

        User persisted = localUser();
        persisted.updateProfileImage(newUrl);
        when(profileImagePersister.replace(USER_ID, newUrl))
                .thenReturn(new ProfileImagePersister.Result(oldUrl, UserProfileResponse.from(persisted)));

        UserProfileResponse res = userService.updateProfileImage(USER_ID, file);

        assertThat(res.getProfileImage()).isEqualTo(newUrl);
        verify(fileServerClient).upload(file);                 // 업로드는 트랜잭션 밖에서 수행 (D-USER-1)
        verify(profileImagePersister).replace(USER_ID, newUrl); // 영속화는 별도 트랜잭션에 위임
        verify(fileServerClient).delete(oldUrl);                // 영속화 커밋 후 기존 파일 삭제 (D-USER-2)
    }

    // ─── withdraw LOCAL 성공 (F-USER-4) ──────────────────────────────────────

    @Test
    @DisplayName("일반 계정 비밀번호 일치 → 탈퇴(INACTIVE) + UserWithdrawnEvent 발행")
    void withdraw_일반계정_비밀번호일치_탈퇴() {
        User user = localUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1!", "encodedPassword")).thenReturn(true);

        userService.withdraw(USER_ID, "Password1!", null, "10.0.0.1", "agent");

        assertThat(user.getStatus()).isEqualTo(Status.INACTIVE);
        ArgumentCaptor<UserWithdrawnEvent> captor = ArgumentCaptor.forClass(UserWithdrawnEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().ipAddress()).isEqualTo("10.0.0.1");
    }

    // ─── purgeWithdrawnUser (hard delete, 2단계) ─────────────────────────────

    @Test
    @DisplayName("purgeWithdrawnUser: 사용자 행 hard delete + 업로드 프로필 이미지 파일 삭제")
    void purgeWithdrawnUser_행삭제_및_이미지삭제() {
        User user = User.builder()
                .id(USER_ID)
                .email("local@example.com")
                .password("encodedPassword")
                .name("일반사용자")
                .role(Role.WARD)
                .status(Status.INACTIVE)
                .provider(Provider.LOCAL)
                .profileImage(IMAGE_URL)
                .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        userService.purgeWithdrawnUser(USER_ID);

        verify(userRepository).delete(user);
        // 단위 테스트엔 활성 트랜잭션이 없어 deleteStoredFileAfterCommit 가 즉시 삭제 호출
        verify(fileServerClient).delete(IMAGE_URL);
    }

    @Test
    @DisplayName("purgeWithdrawnUser: 이미 삭제된 사용자면 멱등 종료(삭제 시도 없음)")
    void purgeWithdrawnUser_없으면_멱등() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        userService.purgeWithdrawnUser(USER_ID);

        verify(userRepository, never()).delete(any());
        verifyNoInteractions(fileServerClient);
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

    private User localUserWithPhone(String phone) {
        return User.builder()
                .id(USER_ID)
                .email("local@example.com")
                .password("encodedPassword")
                .name("일반사용자")
                .phone(phone)
                .role(Role.WARD)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .build();
    }

    // UserUpdateRequest 는 setter/builder 가 없어 리플렉션으로 필드 주입 (순수 단위 테스트 유지)
    private UserUpdateRequest updateRequest(String phone, String nonce) {
        UserUpdateRequest req = new UserUpdateRequest();
        ReflectionTestUtils.setField(req, "name", "새이름");
        ReflectionTestUtils.setField(req, "phone", phone);
        ReflectionTestUtils.setField(req, "verificationNonce", nonce);
        ReflectionTestUtils.setField(req, "gender", Gender.MALE);
        ReflectionTestUtils.setField(req, "birthDate", LocalDate.of(1990, 1, 1));
        ReflectionTestUtils.setField(req, "postcode", "06236");
        ReflectionTestUtils.setField(req, "address", "서울특별시 강남구 테헤란로 123");
        ReflectionTestUtils.setField(req, "addressDetail", "101동 202호");
        return req;
    }

    // 유효한 PNG 파일 시그니처 (89 50 4E 47 0D 0A 1A 0A + 패딩) — 12바이트
    private static final byte[] PNG_HEADER = {
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
            0, 0, 0, 0
    };
}
