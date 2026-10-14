package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.dto.CameraRegisterRequest;
import kr.silverbridge.main.domain.camera.entity.Camera;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 서비스 전체 카메라 등록 상한(camera.max-total, 2026-10-14). */
@ExtendWith(MockitoExtension.class)
class CameraTotalLimitTest {

    @Mock private CameraRepository cameraRepository;
    @Mock private ConnectionService connectionService;
    @Mock private UserRepository userRepository;
    @Mock private CameraIdentifierFactory identifierFactory;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private CameraService cameraService;

    private static final String WARD_ID = "a9cC5f";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(cameraService, "recommendedFps", 15);
        ReflectionTestUtils.setField(cameraService, "maxTotal", 3);
    }

    @Test
    @DisplayName("전체가 상한이면 새 등록은 CAMERA_LIMIT_REACHED(409), 저장 없음")
    void 상한_도달_새등록_거절() {
        when(cameraRepository.count()).thenReturn(3L);

        assertThatThrownBy(() -> cameraService.register(WARD_ID, new CameraRegisterRequest("거실", null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_LIMIT_REACHED);
        verify(cameraRepository).lockTotalLimit(anyLong());
        verify(cameraRepository, never()).save(any(Camera.class));
    }

    @Test
    @DisplayName("상한 미만이면 등록되고, 검사 전에 어드바이저리 락을 잡는다")
    void 상한_미만_등록() {
        when(cameraRepository.count()).thenReturn(2L);
        when(identifierFactory.newSessionId()).thenReturn("ward_k3m9Q2aZ7pLx01Bc");
        when(identifierFactory.newDeviceId()).thenReturn("dev_7Qs4Xu9Ld2");
        when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(cameraService.register(WARD_ID, new CameraRegisterRequest("거실", null)).sessionId())
                .isEqualTo("ward_k3m9Q2aZ7pLx01Bc");
        verify(cameraRepository).lockTotalLimit(anyLong());
    }

    @Test
    @DisplayName("상한이어도 같은 기기 재등록(멱등)은 통과한다 - 상한 검사·락을 타지 않는다")
    void 상한_도달_재등록_통과() {
        Camera existing = Camera.builder().id(1L).wardId(WARD_ID).registeredBy(WARD_ID)
                .sessionId("s1").deviceId("dev_1").label("거실").isActive(true).build();
        when(cameraRepository.findByWardIdAndDeviceId(WARD_ID, "dev_1")).thenReturn(Optional.of(existing));

        assertThat(cameraService.register(WARD_ID, new CameraRegisterRequest("거실", "dev_1")).sessionId())
                .isEqualTo("s1");
        verify(cameraRepository, never()).count();
        verify(cameraRepository, never()).lockTotalLimit(anyLong());
    }

    @Test
    @DisplayName("0 이하면 상한을 끈다 - 개수·락 확인 없이 등록")
    void 상한_끔() {
        ReflectionTestUtils.setField(cameraService, "maxTotal", 0);
        when(identifierFactory.newSessionId()).thenReturn("ward_k3m9Q2aZ7pLx01Bc");
        when(identifierFactory.newDeviceId()).thenReturn("dev_7Qs4Xu9Ld2");
        when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));

        cameraService.register(WARD_ID, new CameraRegisterRequest("거실", null));

        verify(cameraRepository, never()).count();
        verify(cameraRepository, never()).lockTotalLimit(anyLong());
    }

    @Test
    @DisplayName("잘못된 방 이름은 상한보다 먼저 400이다")
    void 방검증이_먼저() {
        assertThatThrownBy(() -> cameraService.register(WARD_ID, new CameraRegisterRequest("없는방", null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_ROOM_INVALID);
    }
}
