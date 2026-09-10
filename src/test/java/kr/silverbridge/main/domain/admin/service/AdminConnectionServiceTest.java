package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminConnectionResponse;
import kr.silverbridge.main.domain.admin.dto.AdminForceConnectRequest;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 강제 연결·해제 검증.
 *
 * <p>강제 연결은 피보호자의 수락(=동의) 없이 SOS·카메라·복약 정보를 여는 조작이라,
 * <b>무엇을 거부하는가</b>와 <b>감사 로그가 남는가</b>를 고정하는 것이 이 테스트의 목적이다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminConnectionServiceTest {

    private static final String ADMIN_ID = "AD0001";
    private static final String GUARDIAN_ID = "EE81BF";
    private static final String WARD_ID = "C82D3E";

    @Mock private UserRepository userRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private ConnectionService connectionService;
    @Mock private AdminAuditLogService auditLogService;

    @InjectMocks private AdminConnectionService adminConnectionService;

    @Nested
    @DisplayName("강제 연결")
    class ForceConnect {

        @Test
        @DisplayName("정상 연결 시 연결 도메인에 위임하고 감사 로그를 남긴다")
        void 정상_연결() {
            givenUsers(Role.GUARDIAN, Status.ACTIVE, Role.WARD, Status.ACTIVE);
            when(connectionService.forceConnect(eq(GUARDIAN_ID), eq(WARD_ID), eq(ADMIN_ID), anyString(), anyString()))
                    .thenReturn(connection(42L, ConnectionStatus.ACTIVE));

            AdminConnectionResponse response =
                    adminConnectionService.forceConnect(request(), ADMIN_ID);

            assertThat(response.connectionId()).isEqualTo(42L);
            assertThat(response.status()).isEqualTo(ConnectionStatus.ACTIVE);
            // 동의 없이 관계를 만드는 조작이라 기록이 반드시 남아야 한다
            verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.FORCE_CONNECT), eq("42"), anyString());
        }

        @Test
        @DisplayName("역할이 맞지 않으면 400이고 연결하지 않는다")
        void 역할_불일치() {
            givenUsers(Role.WARD, Status.ACTIVE, Role.WARD, Status.ACTIVE);

            assertThatThrownBy(() -> adminConnectionService.forceConnect(request(), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CONNECTION_ROLE);

            verify(connectionService, never()).forceConnect(any(), any(), any(), any(), any());
            verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("이용 제한된 보호자는 연결할 수 없다 - 로그인도 알림도 안 되는 계정이다")
        void 정지_계정은_연결_불가() {
            givenUsers(Role.GUARDIAN, Status.RESTRICTED, Role.WARD, Status.ACTIVE);

            assertThatThrownBy(() -> adminConnectionService.forceConnect(request(), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONNECTION_TARGET_NOT_ACTIVE);

            verify(connectionService, never()).forceConnect(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("이미 연결된 관계는 409")
        void 중복_연결() {
            givenUsers(Role.GUARDIAN, Status.ACTIVE, Role.WARD, Status.ACTIVE);
            when(connectionRepository.existsByGuardianIdAndWardIdAndStatusIn(
                    eq(GUARDIAN_ID), eq(WARD_ID), anyList())).thenReturn(true);

            assertThatThrownBy(() -> adminConnectionService.forceConnect(request(), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONNECTION_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("없는 회원이면 404")
        void 없는_회원() {
            when(userRepository.findById(GUARDIAN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminConnectionService.forceConnect(request(), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("강제 해제")
    class ForceDisconnect {

        @Test
        @DisplayName("연결 도메인에 위임하고 감사 로그를 남긴다")
        void 정상_해제() {
            when(connectionRepository.findById(42L))
                    .thenReturn(Optional.of(connection(42L, ConnectionStatus.ACTIVE)));

            adminConnectionService.forceDisconnect(42L, ADMIN_ID);

            verify(connectionService).forceDisconnect(42L, ADMIN_ID);
            verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.FORCE_DISCONNECT), eq("42"), anyString());
        }

        @Test
        @DisplayName("없는 연결이면 404이고 해제도 기록도 하지 않는다")
        void 없는_연결() {
            when(connectionRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminConnectionService.forceDisconnect(99L, ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONNECTION_NOT_FOUND);

            verify(connectionService, never()).forceDisconnect(anyLong(), anyString());
            verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
        }
    }

    // ─── 픽스처 ──────────────────────────────────────────────────

    private void givenUsers(Role guardianRole, Status guardianStatus, Role wardRole, Status wardStatus) {
        when(userRepository.findById(GUARDIAN_ID))
                .thenReturn(Optional.of(user(GUARDIAN_ID, "홍길동", guardianRole, guardianStatus)));
        when(userRepository.findById(WARD_ID))
                .thenReturn(Optional.of(user(WARD_ID, "박민수", wardRole, wardStatus)));
    }

    private static AdminForceConnectRequest request() {
        return new AdminForceConnectRequest(GUARDIAN_ID, WARD_ID);
    }

    private static Connection connection(Long id, ConnectionStatus status) {
        return Connection.builder()
                .id(id).guardianId(GUARDIAN_ID).wardId(WARD_ID)
                .status(status).initiatedBy(ADMIN_ID)
                .build();
    }

    private static User user(String id, String name, Role role, Status status) {
        return User.builder()
                .id(id).email(id.toLowerCase() + "@example.com").password("encoded")
                .name(name).phone("010-0000-0000")
                .role(role).status(status).provider(Provider.LOCAL)
                .build();
    }
}
