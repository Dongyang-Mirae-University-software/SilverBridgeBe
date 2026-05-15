package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminUserCountsResponse;
import kr.silverbridge.main.domain.admin.dto.AdminUserListPageResponse;
import kr.silverbridge.main.domain.admin.dto.AdminUserSearchPageResponse;
import kr.silverbridge.main.domain.admin.dto.UserRoleUpdateRequest;
import kr.silverbridge.main.domain.admin.dto.UserStatusUpdateRequest;
import kr.silverbridge.main.domain.admin.repository.AdminUserStatsRepository;
import kr.silverbridge.main.domain.admin.repository.AdminUserStatsRepository.UserRoleCountProjection;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AdminAuditAction;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AdminUserStatsRepository adminUserStatsRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private AdminAuditLogService auditLogService;

    @InjectMocks private AdminUserService service;

    private static final String ADMIN_ID = "ADMIN0";
    private static final String USER_ID  = "user01";

    // ─── getUsers ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getUsers role 미입력 → WARD + GUARDIAN 으로 페이지 조회")
    void getUsers_role미입력_WARD_GUARDIAN() {
        Page<User> page = new PageImpl<>(List.of(userFixture("aaaaaa", Role.WARD)));
        when(userRepository.findByRoleInOrderByCreatedAtDesc(
                eq(List.of(Role.WARD, Role.GUARDIAN)), any(Pageable.class)))
                .thenReturn(page);

        AdminUserListPageResponse result = service.getUsers(null, 0, 10);

        assertThat(result.content()).hasSize(1);
        verify(userRepository).findByRoleInOrderByCreatedAtDesc(
                eq(List.of(Role.WARD, Role.GUARDIAN)), any(Pageable.class));
    }

    @Test
    @DisplayName("getUsers role=WARD → WARD 만 조회")
    void getUsers_roleWARD() {
        Page<User> page = new PageImpl<>(List.<User>of());
        when(userRepository.findByRoleInOrderByCreatedAtDesc(
                eq(List.of(Role.WARD)), any(Pageable.class)))
                .thenReturn(page);

        service.getUsers(Role.WARD, 0, 10);

        verify(userRepository).findByRoleInOrderByCreatedAtDesc(
                eq(List.of(Role.WARD)), any(Pageable.class));
    }

    // ─── searchUsers ────────────────────────────────────────────────────────

    @Test
    @DisplayName("searchUsers 빈/공백 keyword → null 로 변환되어 쿼리에 전달")
    void searchUsers_빈keyword_null전달() {
        Page<User> page = new PageImpl<>(List.<User>of());
        when(userRepository.searchByKeywordAndFilters(eq(null), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        service.searchUsers("   ", null, null, 0, 10);

        verify(userRepository).searchByKeywordAndFilters(eq(null), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("searchUsers 메타문자(%, _, \\) 이스케이프 처리")
    void searchUsers_메타문자_이스케이프() {
        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        Page<User> page = new PageImpl<>(List.<User>of());
        when(userRepository.searchByKeywordAndFilters(keywordCaptor.capture(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        // 입력: 김 % 동 _ \  (5문자)
        service.searchUsers("김%동_\\", null, null, 0, 10);

        // 기대: 김 \% 동 \_ \\  (8문자)  — 백슬래시를 먼저 이스케이프한 뒤 %, _ 이스케이프
        assertThat(keywordCaptor.getValue()).isEqualTo("김\\%동\\_\\\\");
    }

    @Test
    @DisplayName("searchUsers 정상 → 페이지 결과 반환")
    void searchUsers_정상() {
        Page<User> page = new PageImpl<>(List.of(userFixture("aaaaaa", Role.WARD)));
        when(userRepository.searchByKeywordAndFilters(eq("길동"), eq(Role.WARD), eq(Status.ACTIVE), any(Pageable.class)))
                .thenReturn(page);

        AdminUserSearchPageResponse result =
                service.searchUsers("길동", Role.WARD, Status.ACTIVE, 0, 10);

        assertThat(result.content()).hasSize(1);
    }

    // ─── getUserCounts ──────────────────────────────────────────────────────

    @Test
    @DisplayName("getUserCounts → 단일 쿼리 결과를 DTO 로 매핑")
    void getUserCounts_매핑() {
        when(adminUserStatsRepository.countByRole()).thenReturn(roleCounts(100L, 60L, 38L, 2L));

        AdminUserCountsResponse result = service.getUserCounts();

        assertThat(result.total()).isEqualTo(100L);
        assertThat(result.ward()).isEqualTo(60L);
        assertThat(result.guardian()).isEqualTo(38L);
        assertThat(result.admin()).isEqualTo(2L);
    }

    // ─── updateUserStatus ───────────────────────────────────────────────────

    @Test
    @DisplayName("updateUserStatus ADMIN 대상 → CANNOT_MODIFY_ADMIN, 감사 로그 없음")
    void updateUserStatus_ADMIN_차단() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userFixture(USER_ID, Role.ADMIN)));
        UserStatusUpdateRequest req = mock(UserStatusUpdateRequest.class);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.updateUserStatus(USER_ID, req, ADMIN_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CANNOT_MODIFY_ADMIN);
        verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("updateUserStatus 정상 → INACTIVE 전환 + 감사 로그")
    void updateUserStatus_정상_INACTIVE_전환() {
        User user = userFixture(USER_ID, Role.WARD);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        UserStatusUpdateRequest req = mock(UserStatusUpdateRequest.class);
        when(req.getStatus()).thenReturn(Status.INACTIVE);

        service.updateUserStatus(USER_ID, req, ADMIN_ID);

        assertThat(user.getStatus()).isEqualTo(Status.INACTIVE);
        verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.USER_STATUS_CHANGE), eq(USER_ID), anyString());
    }

    // ─── updateUserRole ─────────────────────────────────────────────────────

    @Test
    @DisplayName("updateUserRole ADMIN 으로 격상 시도 → INVALID_ROLE, 감사 로그 없음")
    void updateUserRole_ADMIN격상_INVALID_ROLE() {
        User user = userFixture(USER_ID, Role.WARD);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        UserRoleUpdateRequest req = mock(UserRoleUpdateRequest.class);
        when(req.getRole()).thenReturn(Role.ADMIN);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.updateUserRole(USER_ID, req, ADMIN_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_ROLE);
        verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("updateUserRole 정상 → role 변경 + 연결 cancel + 감사 로그")
    void updateUserRole_정상_연결자동해제() {
        User user = userFixture(USER_ID, Role.WARD);
        Connection c1 = mock(Connection.class);
        Connection c2 = mock(Connection.class);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        UserRoleUpdateRequest req = mock(UserRoleUpdateRequest.class);
        when(req.getRole()).thenReturn(Role.GUARDIAN);
        when(connectionRepository.findActiveByUserId(eq(USER_ID), any())).thenReturn(List.of(c1, c2));

        service.updateUserRole(USER_ID, req, ADMIN_ID);

        assertThat(user.getRole()).isEqualTo(Role.GUARDIAN);
        verify(c1).cancel();
        verify(c2).cancel();
        verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.USER_ROLE_CHANGE), eq(USER_ID), anyString());
    }

    // ─── forceDeleteUser ────────────────────────────────────────────────────

    @Test
    @DisplayName("forceDeleteUser ADMIN 대상 → CANNOT_MODIFY_ADMIN, 삭제·감사 로그 없음")
    void forceDeleteUser_ADMIN_차단() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userFixture(USER_ID, Role.ADMIN)));

        CustomException ex = assertThrows(CustomException.class,
                () -> service.forceDeleteUser(USER_ID, ADMIN_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CANNOT_MODIFY_ADMIN);
        verify(userRepository, never()).delete(any());
        verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("forceDeleteUser 정상 → 삭제 + 감사 로그")
    void forceDeleteUser_정상() {
        User user = userFixture(USER_ID, Role.WARD);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.forceDeleteUser(USER_ID, ADMIN_ID);

        verify(userRepository).delete(user);
        verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.USER_FORCE_DELETE), eq(USER_ID), anyString());
    }

    // ─── getUser ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getUser 존재하지 않는 ID → USER_NOT_FOUND")
    void getUser_없음_USER_NOT_FOUND() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class, () -> service.getUser(USER_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ─── 헬퍼 ──────────────────────────────────────────────────────────────

    private UserRoleCountProjection roleCounts(long total, long ward, long guardian, long admin) {
        return new UserRoleCountProjection() {
            @Override public long getTotal()    { return total;    }
            @Override public long getWard()     { return ward;     }
            @Override public long getGuardian() { return guardian; }
            @Override public long getAdmin()    { return admin;    }
        };
    }

    private User userFixture(String id, Role role) {
        return User.builder()
                .id(id)
                .email(id + "@x.com")
                .name("테스트")
                .role(role)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .build();
    }
}