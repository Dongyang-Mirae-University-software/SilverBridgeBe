package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminUserConnectionState;
import kr.silverbridge.main.domain.admin.dto.AdminUserCountsResponse;
import kr.silverbridge.main.domain.admin.dto.AdminUserDetailResponse;
import kr.silverbridge.main.domain.admin.dto.AdminUserListItem;
import kr.silverbridge.main.domain.admin.dto.AdminUserUpdateRequest;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.event.UserRestrictedEvent;
import kr.silverbridge.main.domain.user.event.UserRoleChangedEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.domain.user.service.UserService;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 회원관리 검증.
 *
 * <p>여기서 고정하는 것은 <b>무엇을 바꿀 수 있고 무엇을 바꿀 수 없는가</b>다. 관리자 계정은 손댈 수 없고,
 * 탈퇴(INACTIVE)는 상태 변경으로 만들 수 없으며(스윕이 계정을 지운다), 역할이 바뀌면 연결이 정리되고,
 * 강제 탈퇴는 감사 로그를 먼저 남긴 뒤 일반 탈퇴와 같은 2단계 경로를 탄다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminUserServiceTest {

    private static final String ADMIN_ID = "AD0001";
    private static final String USER_ID = "EE81BF";
    private static final String WARD_ID = "C82D3E";

    @Mock private UserRepository userRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private ConnectionService connectionService;
    @Mock private CameraService cameraService;
    @Mock private UserService userService;
    @Mock private AdminAuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AdminUserService adminUserService;

    private AdminUserService service() {
        if (adminUserService == null) {
            adminUserService = new AdminUserService(userRepository, connectionRepository,
                    connectionService, cameraService, userService, auditLogService, eventPublisher);
        }
        return adminUserService;
    }

    // ─── 목록·상세 ────────────────────────────────────────────────

    @Nested
    @DisplayName("회원 목록")
    class UserList {

        @Test
        @DisplayName("보호자 행에는 연결 건수와 대표 1명이 실리고, ACTIVE 연결이 있으면 CONNECTED다")
        void 연결_요약이_실린다() {
            User guardian = user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE);
            givenPage(guardian);
            when(connectionRepository.findByParticipantsAndStatusIn(anyCollection(), anyList()))
                    .thenReturn(List.of(
                            connection(1L, USER_ID, WARD_ID, ConnectionStatus.PENDING, "딸"),
                            connection(2L, USER_ID, "OTHER1", ConnectionStatus.ACTIVE, "아들")));
            when(userRepository.findAllById(anyCollection())).thenReturn(List.of(
                    guardian,
                    user(WARD_ID, "박민수", Role.WARD, Status.ACTIVE),
                    user("OTHER1", "성지원", Role.WARD, Status.ACTIVE)));

            PageResponse<AdminUserListItem> result =
                    service().getUsers(null, null, null, null, 0, 20);

            AdminUserListItem item = result.content().get(0);
            assertThat(item.connectionCount()).isEqualTo(2);
            assertThat(item.connectionState()).isEqualTo(AdminUserConnectionState.CONNECTED);
            // ACTIVE가 먼저 오도록 정렬한다 - 대표로 보이는 한 명이 조회마다 바뀌면 안 된다
            assertThat(item.firstConnection().counterpartName()).isEqualTo("성지원");
            assertThat(item.firstConnection().relation()).isEqualTo("아들");
        }

        @Test
        @DisplayName("PENDING만 있으면 PENDING, 연결이 없으면 NONE이다")
        void 연결_상태는_우선순위로_정해진다() {
            User guardian = user(USER_ID, "이영희", Role.GUARDIAN, Status.ACTIVE);
            givenPage(guardian);
            when(connectionRepository.findByParticipantsAndStatusIn(anyCollection(), anyList()))
                    .thenReturn(List.of(connection(1L, USER_ID, WARD_ID, ConnectionStatus.PENDING, "딸")));
            when(userRepository.findAllById(anyCollection())).thenReturn(List.of(
                    guardian, user(WARD_ID, "정수빈", Role.WARD, Status.ACTIVE)));

            assertThat(service().getUsers(null, null, null, null, 0, 20)
                    .content().get(0).connectionState())
                    .isEqualTo(AdminUserConnectionState.PENDING);

            adminUserService = null;
            givenPage(guardian);
            when(connectionRepository.findByParticipantsAndStatusIn(anyCollection(), anyList()))
                    .thenReturn(List.of());

            assertThat(service().getUsers(null, null, null, null, 0, 20)
                    .content().get(0).connectionState())
                    .isEqualTo(AdminUserConnectionState.NONE);
        }

        @Test
        @DisplayName("관리자 계정의 연결 상태는 NONE이 아니라 null이다 - 연결 축이 없는 계정이라 '연결이 끊긴 회원'으로 읽히면 안 된다")
        void 관리자는_연결_상태가_null이다() {
            givenPage(user("AD0002", "김철수", Role.ADMIN, Status.ACTIVE));
            when(connectionRepository.findByParticipantsAndStatusIn(anyCollection(), anyList()))
                    .thenReturn(List.of());

            AdminUserListItem item = service().getUsers(null, null, null, null, 0, 20).content().get(0);

            assertThat(item.connectionState()).isNull();
            assertThat(item.connectionCount()).isZero();
        }

        @Test
        @DisplayName("탈퇴 진행 중(INACTIVE) 계정은 모집단에서 제외하고 조회한다")
        void 탈퇴_진행_계정은_제외한다() {
            givenPage(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));
            when(connectionRepository.findByParticipantsAndStatusIn(anyCollection(), anyList()))
                    .thenReturn(List.of());

            service().getUsers(null, null, null, null, 0, 20);

            verify(userRepository).searchForAdmin(eq(Status.INACTIVE), any(), any(), any(), any(),
                    anyList(), eq(ConnectionStatus.ACTIVE), eq(ConnectionStatus.PENDING), any(Pageable.class));
        }

        @Test
        @DisplayName("상세는 연결 전체 목록을 싣는다")
        void 상세는_연결_전체를_싣는다() {
            User ward = user(WARD_ID, "박민수", Role.WARD, Status.RESTRICTED);
            when(userRepository.findById(WARD_ID)).thenReturn(Optional.of(ward));
            when(connectionRepository.findByParticipantsAndStatusIn(anyCollection(), anyList()))
                    .thenReturn(List.of(connection(1L, USER_ID, WARD_ID, ConnectionStatus.ACTIVE, "아들")));
            when(userRepository.findAllById(anyCollection())).thenReturn(List.of(
                    ward, user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE)));

            AdminUserDetailResponse detail = service().getUser(WARD_ID);

            assertThat(detail.status()).isEqualTo(Status.RESTRICTED);
            assertThat(detail.connections()).hasSize(1);
            // relation은 보호자를 가리키는 라벨이다 - 피보호자를 조회해도 값은 그대로 "아들"이다
            assertThat(detail.connections().get(0).counterpartName()).isEqualTo("홍길동");
            assertThat(detail.connections().get(0).counterpartRole()).isEqualTo(Role.GUARDIAN);
            assertThat(detail.connections().get(0).relation()).isEqualTo("아들");
        }

        @Test
        @DisplayName("없는 회원 상세 조회는 404")
        void 없는_회원은_404() {
            when(userRepository.findById("NOPE12")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().getUser("NOPE12"))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("탈퇴 진행 중(INACTIVE) 계정 상세도 404 - 목록과 같은 모집단이어야 한다")
        void 탈퇴_진행_계정_상세는_404() {
            givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.INACTIVE));

            assertThatThrownBy(() -> service().getUser(USER_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        }

        private void givenPage(User user) {
            when(userRepository.searchForAdmin(any(), any(), any(), any(), any(), anyList(),
                    any(), any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
        }
    }

    @Test
    @DisplayName("탭 건수는 0건인 역할도 키를 남긴다 - 탭은 늘 네 개다")
    void 탭_건수는_0건도_키를_남긴다() {
        when(userRepository.countByRoleExcludingStatus(Status.INACTIVE))
                .thenReturn(List.of(roleCount(Role.GUARDIAN, 7), roleCount(Role.WARD, 10)));

        AdminUserCountsResponse counts = service().getCounts();

        assertThat(counts.guardian()).isEqualTo(7);
        assertThat(counts.ward()).isEqualTo(10);
        assertThat(counts.admin()).isZero();
        assertThat(counts.total()).isEqualTo(17);
    }

    // ─── 정보 수정 ────────────────────────────────────────────────

    @Nested
    @DisplayName("회원 정보 수정")
    class UpdateUser {

        @Test
        @DisplayName("이름만 바꾸면 이름 감사 로그만 남고 역할·상태는 그대로다")
        void 이름_수정() {
            User user = givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));

            service().updateUser(USER_ID, new AdminUserUpdateRequest("홍길순", null, null, null), ADMIN_ID);

            assertThat(user.getName()).isEqualTo("홍길순");
            assertThat(user.getRole()).isEqualTo(Role.GUARDIAN);
            assertThat(user.getStatus()).isEqualTo(Status.ACTIVE);
            verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.USER_NAME_CHANGE), eq(USER_ID),
                    eq("이름 변경: 홍길동 → 홍길순"));
            verify(auditLogService, never()).log(anyString(), eq(AdminAuditAction.USER_ROLE_CHANGE),
                    anyString(), anyString());
        }

        @Test
        @DisplayName("값이 그대로면 감사 로그를 남기지 않는다 - 저장만 눌러도 이력이 쌓이면 진짜 변경이 묻힌다")
        void 같은_값은_기록하지_않는다() {
            givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));

            service().updateUser(USER_ID,
                    new AdminUserUpdateRequest("홍길동", Role.GUARDIAN, Status.ACTIVE, null), ADMIN_ID);

            verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
            verify(connectionService, never()).tearDownConnectionsOnRoleChange(anyString());
        }

        @Test
        @DisplayName("역할이 바뀌면 기존 연결을 정리하고 해제 건수를 감사 로그에 남긴다")
        void 역할_변경은_연결을_정리한다() {
            User user = givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));
            when(connectionService.tearDownConnectionsOnRoleChange(USER_ID)).thenReturn(3);

            service().updateUser(USER_ID, new AdminUserUpdateRequest(null, Role.WARD, null, null), ADMIN_ID);

            assertThat(user.getRole()).isEqualTo(Role.WARD);
            verify(connectionService).tearDownConnectionsOnRoleChange(USER_ID);
            verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.USER_ROLE_CHANGE), eq(USER_ID),
                    eq("역할 변경: 보호자 → 피보호자 (연결 3건 해제)"));
        }

        @Test
        @DisplayName("역할이 바뀌면 옛 역할의 토큰을 끊는 이벤트를 발행한다 - role 클레임이 만료까지 @PreAuthorize를 통과하면 안 된다")
        void 역할_변경은_토큰을_끊는다() {
            givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));

            service().updateUser(USER_ID, new AdminUserUpdateRequest(null, Role.WARD, null, null), ADMIN_ID);

            verify(eventPublisher).publishEvent(new UserRoleChangedEvent(USER_ID));
        }

        @Test
        @DisplayName("피보호자를 보호자로 바꾸면 카메라를 함께 지우고 건수를 감사 로그에 남긴다 - 보호자는 카메라 API를 못 써 고아가 된다")
        void 역할_변경은_카메라를_지운다() {
            User user = givenUser(user(WARD_ID, "박민수", Role.WARD, Status.ACTIVE));
            when(connectionService.tearDownConnectionsOnRoleChange(WARD_ID)).thenReturn(1);
            when(cameraService.deleteAllByWard(WARD_ID)).thenReturn(2);

            service().updateUser(WARD_ID, new AdminUserUpdateRequest(null, Role.GUARDIAN, null, null), ADMIN_ID);

            assertThat(user.getRole()).isEqualTo(Role.GUARDIAN);
            verify(cameraService).deleteAllByWard(WARD_ID);
            verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.USER_ROLE_CHANGE), eq(WARD_ID),
                    eq("역할 변경: 피보호자 → 보호자 (연결 1건 해제, 카메라 2대 삭제)"));
        }

        @Test
        @DisplayName("역할이 그대로면 카메라도 토큰도 건드리지 않는다")
        void 역할_유지면_부수효과_없음() {
            givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));

            service().updateUser(USER_ID, new AdminUserUpdateRequest("홍길순", null, null, null), ADMIN_ID);

            verify(cameraService, never()).deleteAllByWard(anyString());
            verify(eventPublisher, never()).publishEvent(any(UserRoleChangedEvent.class));
        }

        @Test
        @DisplayName("탈퇴 진행 중(INACTIVE) 계정은 수정 대상이 아니다(404) - ACTIVE로 되돌리면 정리된 반쪽 계정이 되살아난다")
        void 탈퇴_진행_계정은_수정할_수_없다() {
            User zombie = givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.INACTIVE));

            assertThatThrownBy(() -> service().updateUser(USER_ID,
                    new AdminUserUpdateRequest(null, null, Status.ACTIVE, null), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);

            assertThat(zombie.getStatus()).isEqualTo(Status.INACTIVE);
            verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("ADMIN 역할로는 바꿀 수 없다 - 회원관리 화면으로 권한을 만들어낼 수 있게 된다")
        void 관리자로_승격할_수_없다() {
            User user = givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));

            assertThatThrownBy(() -> service().updateUser(USER_ID,
                    new AdminUserUpdateRequest(null, Role.ADMIN, null, null), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ROLE);

            assertThat(user.getRole()).isEqualTo(Role.GUARDIAN);
            verify(connectionService, never()).tearDownConnectionsOnRoleChange(anyString());
        }

        @Test
        @DisplayName("이용 제한으로 바꾸면 UserRestrictedEvent로 기존 토큰까지 끊는다")
        void 정지하면_토큰을_끊는다() {
            User user = givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));

            service().updateUser(USER_ID,
                    new AdminUserUpdateRequest(null, null, Status.RESTRICTED, null), ADMIN_ID);

            assertThat(user.getStatus()).isEqualTo(Status.RESTRICTED);
            verify(eventPublisher).publishEvent(new UserRestrictedEvent(USER_ID));
            verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.USER_STATUS_CHANGE), eq(USER_ID),
                    eq("계정 상태 변경: 이용 중 → 이용 제한"));
        }

        @Test
        @DisplayName("정지 사유는 계정에 저장되고 감사 로그에도 함께 남는다")
        void 정지_사유가_저장된다() {
            User user = givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));

            service().updateUser(USER_ID,
                    new AdminUserUpdateRequest(null, null, Status.RESTRICTED, "  본인 신고 - 탈취 의심  "),
                    ADMIN_ID);

            // 정지는 "본인 확인이 될 때까지의 임시 조치"라, 왜 잠갔는지가 없으면 해제 판단을 할 수 없다
            assertThat(user.getStatusReason()).isEqualTo("본인 신고 - 탈취 의심");
            verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.USER_STATUS_CHANGE), eq(USER_ID),
                    eq("계정 상태 변경: 이용 중 → 이용 제한 (사유: 본인 신고 - 탈취 의심)"));
        }

        @Test
        @DisplayName("이용 중으로 되돌리면 정지 사유가 지워진다 - 잠기지 않았는데 잠긴 이유가 남으면 안 된다")
        void 해제하면_사유가_지워진다() {
            User user = user(USER_ID, "홍길동", Role.GUARDIAN, Status.RESTRICTED);
            user.restrict("본인 신고 - 탈취 의심");
            givenUser(user);

            service().updateUser(USER_ID, new AdminUserUpdateRequest(null, null, Status.ACTIVE, null), ADMIN_ID);

            assertThat(user.getStatus()).isEqualTo(Status.ACTIVE);
            assertThat(user.getStatusReason()).isNull();
        }

        @Test
        @DisplayName("피보호자는 이용 제한할 수 없다 - 로그인이 막히면 SOS를 보낼 수 없게 된다")
        void 피보호자는_정지할_수_없다() {
            User ward = givenUser(user(WARD_ID, "박민수", Role.WARD, Status.ACTIVE));

            assertThatThrownBy(() -> service().updateUser(WARD_ID,
                    new AdminUserUpdateRequest(null, null, Status.RESTRICTED, "사유"), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WARD_CANNOT_BE_RESTRICTED);

            assertThat(ward.getStatus()).isEqualTo(Status.ACTIVE);
            verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("정지된 보호자를 피보호자로 바꾸는 것도 막는다 - 두 단계로 우회하면 같은 상태가 된다")
        void 정지상태에서_피보호자로_바꿀_수_없다() {
            User user = user(USER_ID, "홍길동", Role.GUARDIAN, Status.RESTRICTED);
            givenUser(user);

            assertThatThrownBy(() -> service().updateUser(USER_ID,
                    new AdminUserUpdateRequest(null, Role.WARD, null, null), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WARD_CANNOT_BE_RESTRICTED);

            assertThat(user.getRole()).isEqualTo(Role.GUARDIAN);
            verify(connectionService, never()).tearDownConnectionsOnRoleChange(anyString());
        }

        @Test
        @DisplayName("제한을 풀면서 동시에 피보호자로 바꾸는 것은 허용한다 - 결과 조합이 정상이다")
        void 해제와_역할변경을_동시에_하면_통과() {
            User user = user(USER_ID, "홍길동", Role.GUARDIAN, Status.RESTRICTED);
            givenUser(user);

            service().updateUser(USER_ID,
                    new AdminUserUpdateRequest(null, Role.WARD, Status.ACTIVE, null), ADMIN_ID);

            assertThat(user.getRole()).isEqualTo(Role.WARD);
            assertThat(user.getStatus()).isEqualTo(Status.ACTIVE);
        }

        @Test
        @DisplayName("이용 중으로 되돌릴 때는 토큰 무효화 이벤트를 발행하지 않는다")
        void 해제는_이벤트를_발행하지_않는다() {
            User user = givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.RESTRICTED));

            service().updateUser(USER_ID, new AdminUserUpdateRequest(null, null, Status.ACTIVE, null), ADMIN_ID);

            assertThat(user.getStatus()).isEqualTo(Status.ACTIVE);
            verify(eventPublisher, never()).publishEvent(any(UserRestrictedEvent.class));
        }

        @Test
        @DisplayName("INACTIVE로는 바꿀 수 없다 - 탈퇴는 상태 변경이 아니라 삭제이고, 이 값이 되면 스윕이 계정을 지운다")
        void INACTIVE로_바꿀_수_없다() {
            User user = givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));

            assertThatThrownBy(() -> service().updateUser(USER_ID,
                    new AdminUserUpdateRequest(null, null, Status.INACTIVE, null), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_STATUS);

            assertThat(user.getStatus()).isEqualTo(Status.ACTIVE);
            verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("관리자 계정은 수정 대상이 아니다 - 아무것도 바뀌지 않고 감사 로그도 남지 않는다")
        void 관리자_계정은_수정할_수_없다() {
            User admin = givenUser(user("AD0002", "김철수", Role.ADMIN, Status.ACTIVE));

            assertThatThrownBy(() -> service().updateUser("AD0002",
                    new AdminUserUpdateRequest("바뀐이름", null, Status.RESTRICTED, null), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANNOT_MODIFY_ADMIN);

            assertThat(admin.getName()).isEqualTo("김철수");
            assertThat(admin.getStatus()).isEqualTo(Status.ACTIVE);
            verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("빈 이름은 400 - 이름이 지워지면 목록·알림 문구가 빈칸으로 나간다")
        void 빈_이름은_거부한다() {
            User user = givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));

            assertThatThrownBy(() -> service().updateUser(USER_ID,
                    new AdminUserUpdateRequest("   ", null, null, null), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

            assertThat(user.getName()).isEqualTo("홍길동");
        }
    }

    // ─── 강제 탈퇴 ────────────────────────────────────────────────

    @Nested
    @DisplayName("강제 탈퇴")
    class ForceDelete {

        @Test
        @DisplayName("일반 탈퇴와 같은 2단계 경로를 탄다 - 리스너를 건너뛰면 상대 알림·토큰 정리가 유실된다")
        void 탈퇴_파이프라인을_재사용한다() {
            givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));

            service().forceDelete(USER_ID, ADMIN_ID, "1.2.3.4", "UA");

            verify(userService).forceWithdraw(USER_ID, "1.2.3.4", "UA");
            verify(userService).purgeWithdrawnUser(USER_ID);
            verify(userRepository, never()).delete(any(User.class));
        }

        @Test
        @DisplayName("감사 로그를 삭제보다 먼저 남긴다 - 기록이 실패하면 계정만 사라지고 누가 지웠는지 남지 않는다")
        void 감사_로그가_먼저다() {
            givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));
            var order = org.mockito.Mockito.inOrder(auditLogService, userService);

            service().forceDelete(USER_ID, ADMIN_ID, "1.2.3.4", "UA");

            order.verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.USER_FORCE_DELETE),
                    eq(USER_ID), anyString());
            order.verify(userService).forceWithdraw(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("purge 실패 시 1회 재시도하고, 그래도 실패하면 스윕에 맡기고 예외를 던지지 않는다")
        void purge_실패는_재시도_후_스윕에_맡긴다() {
            givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.ACTIVE));
            org.mockito.Mockito.doThrow(new RuntimeException("DB 순단"))
                    .when(userService).purgeWithdrawnUser(USER_ID);

            service().forceDelete(USER_ID, ADMIN_ID, "1.2.3.4", "UA");

            verify(userService, org.mockito.Mockito.times(2)).purgeWithdrawnUser(USER_ID);
        }

        @Test
        @DisplayName("탈퇴 진행 중(INACTIVE) 계정은 다시 탈퇴시킬 수 없다(404) - 탈퇴 이벤트가 중복 발행되면 안 된다")
        void 탈퇴_진행_계정은_다시_지우지_않는다() {
            givenUser(user(USER_ID, "홍길동", Role.GUARDIAN, Status.INACTIVE));

            assertThatThrownBy(() -> service().forceDelete(USER_ID, ADMIN_ID, "1.2.3.4", "UA"))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);

            verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
            verify(userService, never()).forceWithdraw(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("관리자 계정은 삭제할 수 없다 - 감사 로그도 탈퇴 호출도 없다")
        void 관리자_계정은_삭제할_수_없다() {
            givenUser(user("AD0002", "김철수", Role.ADMIN, Status.ACTIVE));

            assertThatThrownBy(() -> service().forceDelete("AD0002", ADMIN_ID, "1.2.3.4", "UA"))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANNOT_MODIFY_ADMIN);

            verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
            verify(userService, never()).forceWithdraw(anyString(), anyString(), anyString());
        }
    }

    // ─── 픽스처 ──────────────────────────────────────────────────

    private User givenUser(User user) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    private static User user(String id, String name, Role role, Status status) {
        return User.builder()
                .id(id)
                .email(id.toLowerCase() + "@example.com")
                .password("encoded")
                .name(name)
                .phone("010-0000-0000")
                .role(role)
                .status(status)
                .provider(Provider.LOCAL)
                .build();
    }

    private static Connection connection(Long id, String guardianId, String wardId,
                                         ConnectionStatus status, String relation) {
        return Connection.builder()
                .id(id).guardianId(guardianId).wardId(wardId)
                .status(status).initiatedBy(guardianId).relation(relation)
                .build();
    }

    private static UserRepository.RoleCount roleCount(Role role, long count) {
        return new UserRepository.RoleCount() {
            @Override public Role getRole() { return role; }
            @Override public long getCnt() { return count; }
        };
    }
}
