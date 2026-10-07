package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.dto.CameraRegisterRequest;
import kr.silverbridge.main.domain.camera.dto.CameraResponse;
import kr.silverbridge.main.domain.camera.dto.CameraRoomOption;
import kr.silverbridge.main.domain.camera.dto.CameraUpdateRequest;
import kr.silverbridge.main.domain.camera.dto.GuardianCameraView;
import kr.silverbridge.main.domain.camera.entity.Camera;
import kr.silverbridge.main.domain.camera.event.CameraDeletedEvent;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CameraService 단위 테스트.
 *
 * 핵심 정책 3가지를 검증한다.
 * ① 등록 멱등 — 같은 (wardId, deviceId)면 기존 SessionID 재사용(신규 저장 없음).
 * ② 소유권(IDOR) — 타인 카메라 접근은 CAMERA_NOT_AUTHORIZED(403 + 명시 안내, 2026-07-14 정책).
 * ③ 보호자 allowlist — ACTIVE 연결된 피보호자의 활성 카메라만, 이름을 배치 조회로 채워 반환.
 */
@ExtendWith(MockitoExtension.class)
class CameraServiceTest {

    @Mock private CameraRepository cameraRepository;
    @Mock private ConnectionService connectionService;
    @Mock private UserRepository userRepository;
    @Mock private CameraIdentifierFactory identifierFactory;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private CameraService cameraService;

    private static final String WARD_ID = "a9cC5f";
    private static final String OTHER_WARD_ID = "zz9Q1x";
    private static final String GUARDIAN_ID = "GRD001";
    private static final int RECOMMENDED_FPS = 10;

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
            when(identifierFactory.newSessionId()).thenReturn("ward_k3m9Q2aZ7pLx01Bc");
            when(identifierFactory.newDeviceId()).thenReturn("dev_7Qs4Xu9Ld2");
            when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));

            CameraResponse res = cameraService.register(WARD_ID, new CameraRegisterRequest("거실", null));

            assertThat(res)
                    .extracting(CameraResponse::sessionId, CameraResponse::deviceId,
                            CameraResponse::label, CameraResponse::recommendedFps)
                    .containsExactly("ward_k3m9Q2aZ7pLx01Bc", "dev_7Qs4Xu9Ld2", "거실", RECOMMENDED_FPS);
            assertThat(res.isActive()).isTrue();
        }

        @Test
        @DisplayName("같은 기기 재등록 → 기존 SessionID 재사용(옛 형식 ward_{wardId}_ 포함, 송출 설정을 깨지 않으려 교체하지 않는다 - 2026-10-05), 방 이름만 갱신")
        void 재등록_멱등() {
            Camera existing = camera(1L, WARD_ID, "ward_a9cC5f_k3m9Q2", "dev_7Qs4Xu9Ld2", "거실");
            when(cameraRepository.findByWardIdAndDeviceId(WARD_ID, "dev_7Qs4Xu9Ld2"))
                    .thenReturn(Optional.of(existing));

            CameraResponse res = cameraService.register(
                    WARD_ID, new CameraRegisterRequest("침실", "dev_7Qs4Xu9Ld2"));

            assertThat(res.sessionId()).isEqualTo("ward_a9cC5f_k3m9Q2");
            assertThat(res.label()).as("방 이름은 갱신된다").isEqualTo("침실");
            verify(cameraRepository, never()).save(any(Camera.class));
            verify(identifierFactory, never()).newSessionId();
        }

        @Test
        @DisplayName("본인 소유가 아닌 deviceId 전송 → 무시하고 신규 발급 (토큰 도용 불가)")
        void 타인_deviceId는_신규발급() {
            when(cameraRepository.findByWardIdAndDeviceId(WARD_ID, "dev_stolen"))
                    .thenReturn(Optional.empty());
            when(identifierFactory.newSessionId()).thenReturn("ward_N3w1Q2aZ7pLx01Bc");
            when(identifierFactory.newDeviceId()).thenReturn("dev_fresh222");
            when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));

            CameraResponse res = cameraService.register(
                    WARD_ID, new CameraRegisterRequest("작은방", "dev_stolen"));

            assertThat(res.deviceId()).as("도용된 토큰이 아니라 새로 발급된 토큰").isEqualTo("dev_fresh222");
            assertThat(res.sessionId()).isEqualTo("ward_N3w1Q2aZ7pLx01Bc");
        }
    }

    @Nested
    @DisplayName("카메라 등록 - 방 이름 정리")
    class RegisterLabel {

        @Test
        @DisplayName("방 이름은 정리한 값으로 저장한다 - 앞뒤 공백·제로폭 문자 제거")
        void 등록_방이름_정리후저장() {
            when(identifierFactory.newSessionId()).thenReturn("ward_k3m9Q2aZ7pLx01Bc");
            when(identifierFactory.newDeviceId()).thenReturn("dev_7Qs4Xu9Ld2");
            when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));

            CameraResponse res = cameraService.register(WARD_ID, new CameraRegisterRequest("  거\u200b실  ", null));

            assertThat(res.label()).isEqualTo("거실");
        }

        @Test
        @DisplayName("재등록도 정리한 값으로 갱신한다")
        void 재등록_방이름_정리() {
            Camera existing = camera(1L, WARD_ID, "ward_a9cC5f_k3m9Q2", "dev_7Qs4Xu9Ld2", "거실");
            when(cameraRepository.findByWardIdAndDeviceId(WARD_ID, "dev_7Qs4Xu9Ld2"))
                    .thenReturn(Optional.of(existing));

            CameraResponse res = cameraService.register(
                    WARD_ID, new CameraRegisterRequest(" 침실 ", "dev_7Qs4Xu9Ld2"));

            assertThat(res.label()).isEqualTo("침실");
        }

        @Test
        @DisplayName("정리 후 비는 방 이름 → INVALID_INPUT(400), 저장 없음")
        void 빈방이름_거절() {
            for (String blank : List.of("", "   ", "\u00a0\u3000", "\u200b")) {
                assertThatThrownBy(() -> cameraService.register(WARD_ID, new CameraRegisterRequest(blank, null)))
                        .as("label=[%s]", blank)
                        .isInstanceOf(CustomException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
            }
            verify(cameraRepository, never()).save(any(Camera.class));
        }
    }

    @Nested
    @DisplayName("소유권 검증 (IDOR 차단)")
    class Ownership {

        @Test
        @DisplayName("타인 카메라 수정 시도 → CAMERA_NOT_AUTHORIZED (403 — 본인 것만 사용 가능하다고 안내)")
        void 타인카메라_수정_403() {
            Camera others = camera(9L, OTHER_WARD_ID, "ward_zz9Q1x_aaa", "dev_bbb", "거실");
            when(cameraRepository.findById(9L)).thenReturn(Optional.of(others));

            assertThatThrownBy(() -> cameraService.update(WARD_ID, 9L, new CameraUpdateRequest("침실", null)))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_NOT_AUTHORIZED);
        }

        @Test
        @DisplayName("타인 카메라 삭제 시도 → CAMERA_NOT_AUTHORIZED(403), 삭제 미수행")
        void 타인카메라_삭제_403() {
            Camera others = camera(9L, OTHER_WARD_ID, "ward_zz9Q1x_aaa", "dev_bbb", "거실");
            when(cameraRepository.findById(9L)).thenReturn(Optional.of(others));

            assertThatThrownBy(() -> cameraService.delete(WARD_ID, 9L))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_NOT_AUTHORIZED);
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

            CameraResponse res = cameraService.update(WARD_ID, 1L, new CameraUpdateRequest("작은방2", false));

            assertThat(res.label()).isEqualTo("작은방2");
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

        @Test
        @DisplayName("방 이름은 정리한 값으로 저장한다 - 앞뒤 공백·제로폭 문자 제거 (ANOM-G12)")
        void 방이름_정리후저장() {
            Camera mine = camera(1L, WARD_ID, "ward_a9cC5f_k3m", "dev_abc", "거실");
            when(cameraRepository.findById(1L)).thenReturn(Optional.of(mine));

            CameraResponse res = cameraService.update(WARD_ID, 1L, new CameraUpdateRequest("  침​실  ", null));

            assertThat(res.label()).isEqualTo("침실");
        }

        @Test
        @DisplayName("빈 값·공백·제로폭 문자만 있는 방 이름 → INVALID_INPUT(400), 기존 이름 유지 (ANOM-G12)")
        void 빈방이름_거절() {
            for (String blank : List.of("", "   ", " 　", "​")) {
                Camera mine = camera(1L, WARD_ID, "ward_a9cC5f_k3m", "dev_abc", "거실");
                when(cameraRepository.findById(1L)).thenReturn(Optional.of(mine));

                assertThatThrownBy(() -> cameraService.update(WARD_ID, 1L, new CameraUpdateRequest(blank, false)))
                        .as("label=[%s]", blank)
                        .isInstanceOf(CustomException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
                assertThat(mine.getLabel()).isEqualTo("거실");
                assertThat(mine.isActive()).as("요청 전체를 거절하므로 다른 필드도 바뀌지 않는다").isTrue();
            }
        }
    }

    @Nested
    @DisplayName("보호자 allowlist 조회")
    class GuardianAllowlist {

        @Test
        @DisplayName("ACTIVE 연결 피보호자들의 활성 카메라만 방별로 반환 (피보호자 이름 포함)")
        void 연결된_피보호자_카메라만_반환() {
            // 인가 목록은 connection 도메인의 getActiveWardIds만 쓴다(G-1) - 리포지토리를 직접 읽지 않는다
            when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));

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
            when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of());

            List<GuardianCameraView> views = cameraService.getConnectedWardCameras(GUARDIAN_ID);

            assertThat(views).isEmpty();
            verify(cameraRepository, never()).findByWardIdInAndIsActiveTrue(anyCollection());
            verify(userRepository, never()).findAllById(anyList());
        }
    }

    @Nested
    @DisplayName("보호자 영상 인가 (getViewableCamera / isViewable, 2026-10-03)")
    class ViewableCamera {

        private static final String SESSION_ID = "ward_a9cC5f_live";

        @Test
        @DisplayName("ACTIVE 연결된 피보호자의 활성 카메라 → 주인·방 이름 반환")
        void 연결된_보호자_허용() {
            when(cameraRepository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(camera(1L, WARD_ID, SESSION_ID, "dev", "거실")));
            when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);

            var owner = cameraService.getViewableCamera(GUARDIAN_ID, SESSION_ID);

            assertThat(owner.wardId()).isEqualTo(WARD_ID);
            assertThat(owner.label()).isEqualTo("거실");
            assertThat(cameraService.isViewable(GUARDIAN_ID, SESSION_ID)).isTrue();
        }

        @Test
        @DisplayName("연결 없음·PENDING(isActiveConnection=false) → 403 CAMERA_NOT_CONNECTED")
        void 연결없는_보호자_403() {
            when(cameraRepository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(camera(1L, WARD_ID, SESSION_ID, "dev", "거실")));
            when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(false);

            assertThatThrownBy(() -> cameraService.getViewableCamera(GUARDIAN_ID, SESSION_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CAMERA_NOT_CONNECTED);
            assertThat(cameraService.isViewable(GUARDIAN_ID, SESSION_ID)).isFalse();
        }

        @Test
        @DisplayName("백엔드에 등록되지 않은 세션(AI에는 송출 중이어도) → 404, 연결 조회도 하지 않는다")
        void 미등록세션_404() {
            when(cameraRepository.findBySessionId("stream_001")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cameraService.getViewableCamera(GUARDIAN_ID, "stream_001"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CAMERA_NOT_FOUND);
            verify(connectionService, never()).isActiveConnection(any(), any());
        }

        @Test
        @DisplayName("꺼진(비활성) 카메라 → 404 (보호자 카메라 목록과 같은 모집단)")
        void 비활성카메라_404() {
            Camera inactive = camera(1L, WARD_ID, SESSION_ID, "dev", "거실");
            inactive.deactivate();
            when(cameraRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(inactive));

            assertThatThrownBy(() -> cameraService.getViewableCamera(GUARDIAN_ID, SESSION_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CAMERA_NOT_FOUND);
            assertThat(cameraService.isViewable(GUARDIAN_ID, SESSION_ID)).isFalse();
        }
    
        @Test
        @DisplayName("화면 표시용 주인 조회는 활성 카메라만 - 꺼진 카메라는 빈 값 (점검 L-1)")
        void 활성카메라만_주인조회() {
            Camera inactive = camera(2L, WARD_ID, "ward_off", "dev2", "주방");
            inactive.deactivate();
            when(cameraRepository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(camera(1L, WARD_ID, SESSION_ID, "dev", "거실")));
            when(cameraRepository.findBySessionId("ward_off")).thenReturn(Optional.of(inactive));

            assertThat(cameraService.findActiveOwnerBySessionId(SESSION_ID)).isPresent();
            assertThat(cameraService.findActiveOwnerBySessionId("ward_off")).isEmpty();
            assertThat(cameraService.findOwnerBySessionId("ward_off")).isPresent();   // 감지·알림 경로는 그대로
        }
    }

    @Nested
    @DisplayName("삭제 시 이상감지 클립 정리 이벤트 (2026-10-04)")
    class DeletedEvent {

        @Test
        @DisplayName("본인 카메라 삭제 → 그 세션으로 CameraDeletedEvent 발행")
        void 단건_삭제_이벤트() {
            Camera mine = camera(1L, WARD_ID, "ward_a9cC5f_k3m", "dev_aaa", "거실");
            when(cameraRepository.findById(1L)).thenReturn(Optional.of(mine));

            cameraService.delete(WARD_ID, 1L);

            verify(eventPublisher).publishEvent(new CameraDeletedEvent(WARD_ID, List.of("ward_a9cC5f_k3m")));
        }

        @Test
        @DisplayName("역할 변경 일괄 삭제 → 벌크 삭제 전에 모은 세션 전부로 발행")
        void 일괄_삭제_이벤트() {
            when(cameraRepository.findByWardIdOrderByCreatedAtDesc(WARD_ID)).thenReturn(List.of(
                    camera(1L, WARD_ID, "s-a", "dev_a", "거실"),
                    camera(2L, WARD_ID, "s-b", "dev_b", "안방")));
            when(cameraRepository.deleteByWardId(WARD_ID)).thenReturn(2L);

            assertThat(cameraService.deleteAllByWard(WARD_ID)).isEqualTo(2);

            verify(eventPublisher).publishEvent(new CameraDeletedEvent(WARD_ID, List.of("s-a", "s-b")));
        }

        @Test
        @DisplayName("카메라가 없으면 발행하지 않는다")
        void 카메라_없음() {
            when(cameraRepository.findByWardIdOrderByCreatedAtDesc(WARD_ID)).thenReturn(List.of());

            cameraService.deleteAllByWard(WARD_ID);

            verify(eventPublisher, never()).publishEvent(any(CameraDeletedEvent.class));
        }
    }

    @Nested
    @DisplayName("방 규칙 - 정해진 8개 방, 한 방에 카메라 1대 (2026-10-05)")
    class RoomRules {

        @Test
        @DisplayName("목록에 없는 방 이름은 등록·수정 모두 CAMERA_ROOM_INVALID(400), 저장·변경 없음")
        void 목록밖_방_거절() {
            for (String label : List.of("안방", "서재", "방1", "작은 방", "거실2")) {
                assertThatThrownBy(() -> cameraService.register(WARD_ID, new CameraRegisterRequest(label, null)))
                        .as("register label=[%s]", label)
                        .isInstanceOf(CustomException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_ROOM_INVALID);
            }
            verify(cameraRepository, never()).save(any(Camera.class));

            Camera mine = camera(1L, WARD_ID, "ward_a9cC5f_k3m", "dev_abc", "거실");
            when(cameraRepository.findById(1L)).thenReturn(Optional.of(mine));
            assertThatThrownBy(() -> cameraService.update(WARD_ID, 1L, new CameraUpdateRequest("안방", null)))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_ROOM_INVALID);
            assertThat(mine.getLabel()).isEqualTo("거실");
        }

        @Test
        @DisplayName("8개 방은 모두 등록할 수 있다(앞뒤 공백은 정리 후 통과)")
        void 정해진_방_허용() {
            when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));
            when(identifierFactory.newSessionId()).thenReturn("ward_N3w1Q2aZ7pLx01Bc");
            when(identifierFactory.newDeviceId()).thenReturn("dev_new");

            for (String label : List.of("거실", "침실", "주방", "화장실", "현관", "베란다", "작은방", "작은방2", " 거실 ")) {
                assertThat(cameraService.register(WARD_ID, new CameraRegisterRequest(label, null)).label())
                        .isEqualTo(label.strip());
            }
        }

        @Test
        @DisplayName("새 등록 - 같은 방에 다른 카메라가 있으면 CAMERA_LABEL_DUPLICATED(409), 저장 없음")
        void 새등록_같은방_409() {
            when(cameraRepository.findByWardIdAndLabel(WARD_ID, "거실"))
                    .thenReturn(Optional.of(camera(1L, WARD_ID, "s1", "dev_1", "거실")));

            assertThatThrownBy(() -> cameraService.register(WARD_ID, new CameraRegisterRequest("거실", null)))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_LABEL_DUPLICATED);
            verify(cameraRepository, never()).save(any(Camera.class));
            verify(identifierFactory, never()).newSessionId();
        }

        @Test
        @DisplayName("같은 기기가 같은 방으로 재등록하면 그대로 성공한다(자기 자신은 중복이 아니다)")
        void 재등록_같은방_멱등() {
            Camera existing = camera(1L, WARD_ID, "ward_a9cC5f_k3m9Q2", "dev_7Qs4Xu9Ld2", "거실");
            when(cameraRepository.findByWardIdAndDeviceId(WARD_ID, "dev_7Qs4Xu9Ld2")).thenReturn(Optional.of(existing));
            when(cameraRepository.findByWardIdAndLabel(WARD_ID, "거실")).thenReturn(Optional.of(existing));

            CameraResponse res = cameraService.register(WARD_ID, new CameraRegisterRequest("거실", "dev_7Qs4Xu9Ld2"));

            assertThat(res.sessionId()).isEqualTo("ward_a9cC5f_k3m9Q2");
            assertThat(res.label()).isEqualTo("거실");
        }

        @Test
        @DisplayName("같은 기기라도 다른 카메라가 쓰는 방으로 옮기면 409, 방 이름은 그대로")
        void 재등록_다른카메라방_409() {
            Camera existing = camera(1L, WARD_ID, "s1", "dev_1", "거실");
            when(cameraRepository.findByWardIdAndDeviceId(WARD_ID, "dev_1")).thenReturn(Optional.of(existing));
            when(cameraRepository.findByWardIdAndLabel(WARD_ID, "주방"))
                    .thenReturn(Optional.of(camera(2L, WARD_ID, "s2", "dev_2", "주방")));

            assertThatThrownBy(() -> cameraService.register(WARD_ID, new CameraRegisterRequest("주방", "dev_1")))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_LABEL_DUPLICATED);
            assertThat(existing.getLabel()).isEqualTo("거실");
        }

        @Test
        @DisplayName("방 이름 변경 - 다른 카메라가 쓰는 방이면 409, 같은 방 그대로면 성공")
        void 수정_중복검사() {
            Camera mine = camera(1L, WARD_ID, "s1", "dev_1", "거실");
            when(cameraRepository.findById(1L)).thenReturn(Optional.of(mine));
            when(cameraRepository.findByWardIdAndLabel(WARD_ID, "주방"))
                    .thenReturn(Optional.of(camera(2L, WARD_ID, "s2", "dev_2", "주방")));
            when(cameraRepository.findByWardIdAndLabel(WARD_ID, "거실")).thenReturn(Optional.of(mine));

            assertThatThrownBy(() -> cameraService.update(WARD_ID, 1L, new CameraUpdateRequest("주방", null)))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_LABEL_DUPLICATED);
            assertThat(mine.getLabel()).isEqualTo("거실");

            assertThat(cameraService.update(WARD_ID, 1L, new CameraUpdateRequest("거실", null)).label()).isEqualTo("거실");
        }

        @Test
        @DisplayName("다른 피보호자의 같은 방 이름은 상관없다 - 조회 자체가 본인 wardId로 한정된다")
        void 다른피보호자_같은방_허용() {
            when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));
            when(identifierFactory.newSessionId()).thenReturn("ward_N3w1Q2aZ7pLx01Bc");
            when(identifierFactory.newDeviceId()).thenReturn("dev_new");

            assertThat(cameraService.register(WARD_ID, new CameraRegisterRequest("거실", null)).label()).isEqualTo("거실");
            verify(cameraRepository).findByWardIdAndLabel(WARD_ID, "거실");
            verify(cameraRepository, never()).findByWardIdAndLabel(eq(OTHER_WARD_ID), any());
        }

        @Test
        @DisplayName("새 등록 경합 - id가 IDENTITY라 save()에서 INSERT가 실행돼 제약에 걸려도 같은 409로 바꾼다")
        void 새등록_경합_save에서_제약위반_409() {
            when(identifierFactory.newSessionId()).thenReturn("ward_N3w1Q2aZ7pLx01Bc");
            when(identifierFactory.newDeviceId()).thenReturn("dev_new");
            when(cameraRepository.save(any(Camera.class))).thenThrow(new DataIntegrityViolationException("insert",
                    new RuntimeException("duplicate key value violates unique constraint \"uq_camera_ward_label\"")));

            assertThatThrownBy(() -> cameraService.register(WARD_ID, new CameraRegisterRequest("거실", null)))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_LABEL_DUPLICATED);
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Hibernate가 알려주는 제약 이름으로도 판정한다(메시지 문구에 기대지 않음)")
        void 제약이름으로_판정() {
            when(identifierFactory.newSessionId()).thenReturn("ward_N3w1Q2aZ7pLx01Bc");
            when(identifierFactory.newDeviceId()).thenReturn("dev_new");
            when(cameraRepository.save(any(Camera.class))).thenThrow(new DataIntegrityViolationException("insert",
                    new org.hibernate.exception.ConstraintViolationException("dup", null, "uq_camera_ward_label")));

            assertThatThrownBy(() -> cameraService.register(WARD_ID, new CameraRegisterRequest("거실", null)))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_LABEL_DUPLICATED);
        }

        @Test
        @DisplayName("방 변경 경합 - 변경 감지라 flush()에서 제약에 걸리면 같은 409로 바꾼다")
        void 방변경_경합_flush에서_제약위반_409() {
            Camera mine = camera(1L, WARD_ID, "s1", "dev_1", "거실");
            when(cameraRepository.findById(1L)).thenReturn(Optional.of(mine));
            doThrow(new DataIntegrityViolationException("update",
                    new RuntimeException("duplicate key value violates unique constraint \"uq_camera_ward_label\"")))
                    .when(cameraRepository).flush();

            assertThatThrownBy(() -> cameraService.update(WARD_ID, 1L, new CameraUpdateRequest("주방", null)))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CAMERA_LABEL_DUPLICATED);
        }

        @Test
        @DisplayName("방 제약이 아닌 다른 DB 제약 위반은 바꾸지 않고 그대로 올린다")
        void 다른제약위반_그대로() {
            when(identifierFactory.newSessionId()).thenReturn("ward_N3w1Q2aZ7pLx01Bc");
            when(identifierFactory.newDeviceId()).thenReturn("dev_new");
            when(cameraRepository.save(any(Camera.class))).thenThrow(new DataIntegrityViolationException("insert",
                    new org.hibernate.exception.ConstraintViolationException("dup", null, "uq_cameras_session")));

            assertThatThrownBy(() -> cameraService.register(WARD_ID, new CameraRegisterRequest("거실", null)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("방 선택지 - 8개 방을 화면 순서대로, 이미 카메라가 있는 방만 registered=true")
        void 방선택지() {
            when(cameraRepository.findByWardIdOrderByCreatedAtDesc(WARD_ID)).thenReturn(List.of(
                    camera(1L, WARD_ID, "s1", "dev_1", "거실"),
                    camera(2L, WARD_ID, "s2", "dev_2", "작은방2")));

            List<CameraRoomOption> options = cameraService.getRoomOptions(WARD_ID);

            assertThat(options).extracting(CameraRoomOption::label)
                    .containsExactly("거실", "침실", "주방", "화장실", "현관", "베란다", "작은방", "작은방2");
            assertThat(options).filteredOn(CameraRoomOption::registered).extracting(CameraRoomOption::label)
                    .containsExactly("거실", "작은방2");
        }
    }
}
