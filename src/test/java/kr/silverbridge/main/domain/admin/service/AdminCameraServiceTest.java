package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminCameraItem;
import kr.silverbridge.main.domain.admin.dto.AdminCameraLiveStatus;
import kr.silverbridge.main.domain.admin.dto.AdminCameraStatusFilter;
import kr.silverbridge.main.domain.admin.dto.AdminCameraSummaryResponse;
import kr.silverbridge.main.domain.anomaly.client.AiLiveStreamSubscriber;
import kr.silverbridge.main.domain.camera.entity.Camera;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 관리자 디바이스 관리 조회 검증(2026-10-23).
 *
 * <p>고정하는 정책: ① AI 연결을 모르면 상태는 끊김이 아니라 null이고, 정상·오프라인 필터는 빈 목록 대신 503이다
 * ② 상태가 메모리 값이라 필터 뒤 개수·페이지가 맞아야 한다 ③ 탈퇴 처리 중 피보호자의 카메라는 빠진다
 * ④ 등록 상한이 꺼져 있으면 0이 아니라 null이다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminCameraServiceTest {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    @Mock private AiLiveStreamSubscriber aiLiveStreamSubscriber;
    @Mock private CameraRepository cameraRepository;
    @Mock private UserRepository userRepository;

    private AdminCameraService service;

    // 등록 순서: c1(가장 오래됨) < c2 < c3 < c4(가장 최근)
    private final Camera c1 = camera(1L, "W00001", "ward_AAAA", "거실", true, 1);
    private final Camera c2 = camera(2L, "W00001", "ward_BBBB", "침실", false, 2);
    private final Camera c3 = camera(3L, "W00002", "ward_CCCC", "주방", true, 3);
    private final Camera withdrawing = camera(4L, "W00003", "ward_DDDD", "현관", true, 4);

    @BeforeEach
    void setUp() {
        service = new AdminCameraService(aiLiveStreamSubscriber, cameraRepository, userRepository);
        ReflectionTestUtils.setField(service, "maxTotal", 3);
        when(cameraRepository.findAll()).thenReturn(List.of(c1, c2, c3, withdrawing));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                ward("W00001", "김영자", "서울 강남구 역삼로 123", Status.ACTIVE),
                ward("W00002", "최영수", "부산 해운대구 센텀중앙로 55", Status.ACTIVE),
                ward("W00003", "탈퇴중", "서울 어딘가", Status.INACTIVE)));
        when(userRepository.countByStatusAndRole(Status.ACTIVE, Role.WARD)).thenReturn(34L);
        when(userRepository.countWardsWithoutCamera(Role.WARD, Status.ACTIVE)).thenReturn(32L);
    }

    private void aiKnows(String... liveSessions) {
        when(aiLiveStreamSubscriber.liveSessionSnapshot()).thenReturn(Optional.of(Set.of(liveSessions)));
    }

    private void aiUnknown() {
        when(aiLiveStreamSubscriber.liveSessionSnapshot()).thenReturn(Optional.empty());
    }

    private PageResponse<AdminCameraItem> list(AdminCameraStatusFilter status, String q, int page, int size) {
        return service.getCameras("ADM001", status, q, page, size);
    }

    @Nested
    @DisplayName("상태 판정")
    class LiveStatus {

        @Test
        @DisplayName("구독 중인 세션은 LIVE, 그 밖의 등록 카메라는 OFFLINE - 꺼 둔 카메라도 실제 송출 여부대로")
        void liveAndOffline() {
            aiKnows("ward_AAAA", "ward_BBBB");

            List<AdminCameraItem> items = list(null, null, 0, 20).content();

            assertThat(items).extracting(AdminCameraItem::sessionId, AdminCameraItem::liveStatus)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("ward_CCCC", AdminCameraLiveStatus.OFFLINE),
                            org.assertj.core.groups.Tuple.tuple("ward_BBBB", AdminCameraLiveStatus.LIVE),
                            org.assertj.core.groups.Tuple.tuple("ward_AAAA", AdminCameraLiveStatus.LIVE));
        }

        @Test
        @DisplayName("AI 연결을 모르면 상태는 끊김이 아니라 null이고, 전체 목록은 정상 응답한다")
        void unknownIsNull() {
            aiUnknown();

            List<AdminCameraItem> items = list(AdminCameraStatusFilter.ALL, null, 0, 20).content();

            assertThat(items).hasSize(3).allSatisfy(item -> assertThat(item.liveStatus()).isNull());
        }

        @Test
        @DisplayName("AI 연결을 모르면 정상·오프라인 필터는 빈 목록이 아니라 503 CAMERA_LIVE_STATUS_UNKNOWN")
        void unknownFilterIs503() {
            aiUnknown();

            for (AdminCameraStatusFilter filter : List.of(AdminCameraStatusFilter.LIVE, AdminCameraStatusFilter.OFFLINE)) {
                assertThatThrownBy(() -> list(filter, null, 0, 20))
                        .isInstanceOf(CustomException.class)
                        .extracting("errorCode").isEqualTo(ErrorCode.CAMERA_LIVE_STATUS_UNKNOWN);
            }
        }
    }

    @Nested
    @DisplayName("필터·검색·페이지")
    class Filtering {

        @Test
        @DisplayName("탈퇴 처리 중(INACTIVE) 피보호자의 카메라는 목록에서 빠진다")
        void excludesWithdrawingWard() {
            aiKnows();

            assertThat(list(null, null, 0, 20).content())
                    .extracting(AdminCameraItem::cameraId).doesNotContain(4L);
        }

        @Test
        @DisplayName("상태 필터 뒤 전체 개수와 페이지가 필터 결과 기준이다")
        void filterThenPage() {
            aiKnows("ward_AAAA");

            PageResponse<AdminCameraItem> offline = list(AdminCameraStatusFilter.OFFLINE, null, 0, 1);
            assertThat(offline.totalElements()).isEqualTo(2);
            assertThat(offline.totalPages()).isEqualTo(2);
            assertThat(offline.content()).extracting(AdminCameraItem::cameraId).containsExactly(3L);
            assertThat(offline.last()).isFalse();

            PageResponse<AdminCameraItem> second = list(AdminCameraStatusFilter.OFFLINE, null, 1, 1);
            assertThat(second.content()).extracting(AdminCameraItem::cameraId).containsExactly(2L);
            assertThat(second.last()).isTrue();

            PageResponse<AdminCameraItem> live = list(AdminCameraStatusFilter.LIVE, null, 0, 20);
            assertThat(live.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("범위를 넘는 페이지는 빈 내용과 올바른 전체 개수, 크기는 공통 보정(최대 50)")
        void pageOutOfRange() {
            aiKnows();

            PageResponse<AdminCameraItem> page = list(null, null, 9, 500);

            assertThat(page.content()).isEmpty();
            assertThat(page.totalElements()).isEqualTo(3);
            assertThat(page.size()).isEqualTo(50);
        }

        @Test
        @DisplayName("검색: 주소(공백 무시)·설치 위치·이름·세션 ID(대소문자 무시) 부분 일치")
        void keyword() {
            aiKnows();

            assertThat(list(null, "역삼로123", 0, 20).content())
                    .extracting(AdminCameraItem::cameraId).containsExactlyInAnyOrder(1L, 2L);
            assertThat(list(null, "주방", 0, 20).content())
                    .extracting(AdminCameraItem::cameraId).containsExactly(3L);
            assertThat(list(null, "최영", 0, 20).content())
                    .extracting(AdminCameraItem::cameraId).containsExactly(3L);
            assertThat(list(null, "WARD_bbbb", 0, 20).content())
                    .extracting(AdminCameraItem::cameraId).containsExactly(2L);
            assertThat(list(null, "   ", 0, 20).totalElements()).isEqualTo(3);
            assertThat(list(null, "탈퇴중", 0, 20).content()).isEmpty();
        }

        @Test
        @DisplayName("항목: 피보호자 이름·주소(상세 주소 제외)·등록 시각은 KST")
        void itemFields() {
            aiKnows();

            AdminCameraItem item = list(null, "ward_CCCC", 0, 20).content().getFirst();

            assertThat(item.wardName()).isEqualTo("최영수");
            assertThat(item.wardAddress()).isEqualTo("부산 해운대구 센텀중앙로 55");
            assertThat(item.room()).isEqualTo("주방");
            assertThat(item.registeredAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
            assertThat(item.registeredAt().toInstant()).isEqualTo(c3.getCreatedAt().toInstant());
        }
    }

    @Nested
    @DisplayName("요약")
    class Summary {

        @Test
        @DisplayName("live + offline = registered(탈퇴 처리 중 제외), 피보호자 수·카메라 없는 피보호자 수")
        void counts() {
            aiKnows("ward_AAAA", "ward_DDDD");

            AdminCameraSummaryResponse summary = service.getSummary();

            assertThat(summary.aiConnected()).isTrue();
            assertThat(summary.registered()).isEqualTo(3);
            assertThat(summary.live()).isEqualTo(1);
            assertThat(summary.offline()).isEqualTo(2);
            assertThat(summary.maxTotal()).isEqualTo(3);
            assertThat(summary.totalWards()).isEqualTo(34);
            assertThat(summary.wardsWithoutCamera()).isEqualTo(32);
        }

        @Test
        @DisplayName("AI 연결을 모르면 live·offline은 0이 아니라 null")
        void unknownIsNull() {
            aiUnknown();

            AdminCameraSummaryResponse summary = service.getSummary();

            assertThat(summary.aiConnected()).isFalse();
            assertThat(summary.live()).isNull();
            assertThat(summary.offline()).isNull();
            assertThat(summary.registered()).isEqualTo(3);
        }

        @Test
        @DisplayName("등록 상한이 꺼져 있으면(0 이하) maxTotal은 null")
        void noLimit() {
            aiKnows();
            ReflectionTestUtils.setField(service, "maxTotal", 0);

            assertThat(service.getSummary().maxTotal()).isNull();
        }
    }

    private static Camera camera(Long id, String wardId, String sessionId, String label, boolean active, int day) {
        Camera camera = Camera.builder()
                .id(id).wardId(wardId).sessionId(sessionId).deviceId("dev-" + id)
                .label(label).registeredBy(wardId).isActive(active)
                .build();
        ReflectionTestUtils.setField(camera, "createdAt", OffsetDateTime.of(2026, 10, day, 1, 0, 0, 0, UTC));
        return camera;
    }

    private static User ward(String id, String name, String address, Status status) {
        User user = User.builder()
                .id(id)
                .email(id.toLowerCase() + "@example.com")
                .password("encoded")
                .name(name)
                .phone("010-0000-0000")
                .role(Role.WARD)
                .status(status)
                .provider(Provider.LOCAL)
                .build();
        ReflectionTestUtils.setField(user, "address", address);
        ReflectionTestUtils.setField(user, "addressDetail", "101동 1001호");
        return user;
    }
}
