package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.dto.CameraRegisterRequest;
import kr.silverbridge.main.domain.camera.dto.CameraResponse;
import kr.silverbridge.main.domain.camera.dto.CameraUpdateRequest;
import kr.silverbridge.main.domain.camera.dto.GuardianCameraView;
import kr.silverbridge.main.domain.camera.entity.Camera;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CameraService 단위 테스트.
 *
 * 핵심 정책 3가지를 검증한다.
 * ① 등록 멱등 — 같은 (wardId, deviceId)면 기존 SessionID 재사용(신규 저장 없음).
 * ② 소유권(IDOR) — 타인 카메라는 존재 노출 없이 CAMERA_NOT_FOUND(404 위장).
 * ③ 보호자 allowlist — ACTIVE 연결된 피보호자의 활성 카메라만, 이름을 배치 조회로 채워 반환.
 */
@ExtendWith(MockitoExtension.class)
class CameraServiceTest {

    @Mock private CameraRepository cameraRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private UserRepository userRepository;
    @Mock private CameraIdentifierFactory identifierFactory;

    @InjectMocks private CameraService cameraService;

    private static final String WARD_ID = "a9cC5f";
    private static final String OTHER_WARD_ID = "zz9Q1x";
    private static final String GUARDIAN_ID = "GRD001";
    private static final int RECOMMENDED_FPS = 5;

    @BeforeEach
    void setUp() {
        // @Value 주입 필드는 단위 테스트에서 채워지지 않으므로 직접 세팅
        ReflectionTestUtils.setField(cameraService, "recommendedFps", RECOMMENDED_FPS);
    }

    private Camera camera(Long id, String wardId, String sessionId, String deviceId, String label) {
        return Camera.builder()
                .id(id).wardId(wardId).registeredBy(wardId)
                .sessionId(sessionId).deviceId(deviceId).label(label)
                .isActive(true)
                .build();
    }

    @Nested
    @DisplayName("카메라 등록")
    class Register {

        @Test
        @DisplayName("최초 등록(deviceId 없음) → SessionID·DeviceID 신규 발급 후 저장, 권장 fps 함께 반환")
        void 최초등록_신규발급() {
            when(identifierFactory.newSessionId(WARD_ID)).thenReturn("ward_a9cC5f_k3m9Q2");
            when(identifierFactory.newDeviceId()).thenReturn("dev_7Qs4Xu9Ld2");
            when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));

            CameraResponse res = cameraService.register(WARD_ID, new CameraRegisterRequest("거실", null));

            assertThat(res)
                    .extracting(CameraResponse::sessionId, CameraResponse::deviceId,
                            CameraResponse::label, CameraResponse::recommendedFps)
                    .containsExactly("ward_a9cC5f_k3m9Q2", "dev_7Qs4Xu9Ld2", "거실", RECOMMENDED_FPS);
            assertThat(res.isActive()).isTrue();
        }

        @Test
        @DisplayName("같은 기기 재등록 → 기존 SessionID 재사용(신규 저장 없음), 방 이름만 갱신")
        void 재등록_멱등() {
            Camera existing = camera(1L, WARD_ID, "ward_a9cC5f_k3m9Q2", "dev_7Qs4Xu9Ld2", "거실");
            when(cameraRepository.findByWardIdAndDeviceId(WARD_ID, "dev_7Qs4Xu9Ld2"))
                    .thenReturn(Optional.of(existing));

            CameraResponse res = cameraService.register(
                    WARD_ID, new CameraRegisterRequest("안방", "dev_7Qs4Xu9Ld2"));

            assertThat(res.sessionId()).isEqualTo("ward_a9cC5f_k3m9Q2");
            assertThat(res.label()).as("방 이름은 갱신된다").isEqualTo("안방");
            verify(cameraRepository, never()).save(any(Camera.class));
            verify(identifierFactory, never()).newSessionId(any());
        }

        @Test
        @DisplayName("본인 소유가 아닌 deviceId 전송 → 무시하고 신규 발급 (토큰 도용 불가)")
        void 타인_deviceId는_신규발급() {
            when(cameraRepository.findByWardIdAndDeviceId(WARD_ID, "dev_stolen"))
                    .thenReturn(Optional.empty());
            when(identifierFactory.newSessionId(WARD_ID)).thenReturn("ward_a9cC5f_new111");
            when(identifierFactory.newDeviceId()).thenReturn("dev_fresh222");
            when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));

            CameraResponse res = cameraService.register(
                    WARD_ID, new CameraRegisterRequest("방1", "dev_stolen"));

            assertThat(res.deviceId()).as("도용된 토큰이 아니라 새로 발급된 토큰").isEqualTo("dev_fresh222");
            assertThat(res.sessionId()).isEqualTo("ward_a9cC5f_new111");
        }
    }

    @Nested
    @DisplayName("소유권 검증 (IDOR 차단)")
    class Ownership {

        @Test
        @DisplayName("타인 카메라 수정 시도 → CAMERA_NOT_FOUND (존재 여부 비노출)")
        void 타인카메라_수정_404() {
            Camera others = camera(9L, OTHER_WARD_ID, "ward_zz9Q1x_aaa", "dev_bbb", "거실");
            when(cameraRepository.findById(9L)).thenReturn(Optional.of(others));

            assertThatThrownBy(() -> cameraService.update(WARD_ID, 9L, new CameraUpdateRequest("안방", null)))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_NOT_FOUND);
        }

        @Test
        @DisplayName("타인 카메라 삭제 시도 → CAMERA_NOT_FOUND, 삭제 미수행")
        void 타인카메라_삭제_404() {
            Camera others = camera(9L, OTHER_WARD_ID, "ward_zz9Q1x_aaa", "dev_bbb", "거실");
            when(cameraRepository.findById(9L)).thenReturn(Optional.of(others));

            assertThatThrownBy(() -> cameraService.delete(WARD_ID, 9L))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_NOT_FOUND);
            verify(cameraRepository, never()).delete(any(Camera.class));
        }

        @Test
        @DisplayName("존재하지 않는 카메라 → CAMERA_NOT_FOUND")
        void 없는카메라_404() {
            when(cameraRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cameraService.delete(WARD_ID, 404L))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("카메라 수정")
    class Update {

        @Test
        @DisplayName("본인 카메라 → 방 이름 변경 + 사용 중지 토글 반영")
        void 본인카메라_수정() {
            Camera mine = camera(1L, WARD_ID, "ward_a9cC5f_k3m", "dev_abc", "거실");
            when(cameraRepository.findById(1L)).thenReturn(Optional.of(mine));

            CameraResponse res = cameraService.update(WARD_ID, 1L, new CameraUpdateRequest("방2", false));

            assertThat(res.label()).isEqualTo("방2");
            assertThat(res.isActive()).isFalse();
        }

        @Test
        @DisplayName("null 필드는 미변경 (부분 수정)")
        void null필드_미변경() {
            Camera mine = camera(1L, WARD_ID, "ward_a9cC5f_k3m", "dev_abc", "거실");
            when(cameraRepository.findById(1L)).thenReturn(Optional.of(mine));

            CameraResponse res = cameraService.update(WARD_ID, 1L, new CameraUpdateRequest(null, null));

            assertThat(res.label()).isEqualTo("거실");
            assertThat(res.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("보호자 allowlist 조회")
    class GuardianAllowlist {

        @Test
        @DisplayName("ACTIVE 연결 피보호자들의 활성 카메라만 방별로 반환 (피보호자 이름 포함)")
        void 연결된_피보호자_카메라만_반환() {
            Connection conn = Connection.builder()
                    .guardianId(GUARDIAN_ID).wardId(WARD_ID).status(ConnectionStatus.ACTIVE).build();
            when(connectionRepository.findByGuardianIdAndStatusInOrderByCreatedAtDesc(eq(GUARDIAN_ID), anyList()))
                    .thenReturn(List.of(conn));

            User ward = User.builder().id(WARD_ID).name("남궁명진").role(Role.WARD).build();
            when(userRepository.findAllById(anyList())).thenReturn(List.of(ward));

            when(cameraRepository.findByWardIdInAndIsActiveTrue(anyCollection())).thenReturn(List.of(
                    camera(1L, WARD_ID, "ward_a9cC5f_living", "dev_1", "거실"),
                    camera(2L, WARD_ID, "ward_a9cC5f_room1", "dev_2", "방1")));

            List<GuardianCameraView> views = cameraService.getConnectedWardCameras(GUARDIAN_ID);

            assertThat(views)
                    .hasSize(2)
                    .extracting(GuardianCameraView::sessionId, GuardianCameraView::wardName, GuardianCameraView::label)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("ward_a9cC5f_living", "남궁명진", "거실"),
                            org.assertj.core.groups.Tuple.tuple("ward_a9cC5f_room1", "남궁명진", "방1"));
        }

        @Test
        @DisplayName("ACTIVE 연결이 없으면 빈 목록 — 카메라 조회조차 하지 않음")
        void 연결없으면_빈목록() {
            when(connectionRepository.findByGuardianIdAndStatusInOrderByCreatedAtDesc(eq(GUARDIAN_ID), anyList()))
                    .thenReturn(List.of());

            List<GuardianCameraView> views = cameraService.getConnectedWardCameras(GUARDIAN_ID);

            assertThat(views).isEmpty();
            verify(cameraRepository, never()).findByWardIdInAndIsActiveTrue(anyCollection());
            verify(userRepository, never()).findAllById(anyList());
        }
    }
}
