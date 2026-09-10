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
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관리자 강제 연결·해제.
 *
 * <p>고객센터 문의를 받아 관리자가 대신 처리해 주는 경로다. 시니어가 수락 버튼을 누르지 못해
 * 가족이 연결하지 못하는 경우가 실제로 있다.</p>
 *
 * <p><b>강제 연결은 이 서비스에서 가장 민감한 조작이다.</b> 일반 연결은 피보호자의 수락이 곧 동의인데,
 * 강제 연결은 그 동의 없이 SOS·카메라·복약·위치 이력을 열어 준다. 그래서 ① 양쪽 모두에게 알리고
 * ② 반드시 감사 로그를 남긴다.</p>
 *
 * <p>상태 전이는 {@link ConnectionService}가 맡고 여기서는 검증과 감사 로그만 한다
 * (연결 도메인이 자기 상태를 소유하도록).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminConnectionService {

    private final UserRepository userRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final AdminAuditLogService auditLogService;

    /**
     * 강제 연결. 수락 대기 중인 요청이 있으면 그것을 승격시키고, 없으면 새로 만들어 바로 연결한다.
     *
     * <p>이용 제한·탈퇴 진행 계정은 연결하지 않는다 - 로그인도 알림도 되지 않는 계정이라
     * 연결해 두어도 아무것도 동작하지 않는다.</p>
     */
    @Transactional
    public AdminConnectionResponse forceConnect(AdminForceConnectRequest request, String adminId) {
        User guardian = getUserOrThrow(request.guardianId());
        User ward = getUserOrThrow(request.wardId());

        if (guardian.getRole() != Role.GUARDIAN || ward.getRole() != Role.WARD) {
            throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
        }
        if (guardian.getStatus() != Status.ACTIVE || ward.getStatus() != Status.ACTIVE) {
            throw new CustomException(ErrorCode.CONNECTION_TARGET_NOT_ACTIVE);
        }
        if (connectionRepository.existsByGuardianIdAndWardIdAndStatusIn(
                guardian.getId(), ward.getId(), List.of(ConnectionStatus.ACTIVE))) {
            throw new CustomException(ErrorCode.CONNECTION_ALREADY_EXISTS);
        }

        Connection connection = connectionService.forceConnect(
                guardian.getId(), ward.getId(), adminId, guardian.getName(), ward.getName());

        auditLogService.log(adminId, AdminAuditAction.FORCE_CONNECT, String.valueOf(connection.getId()),
                String.format("강제 연결: %s(%s) → %s(%s)",
                        guardian.getName(), guardian.getId(), ward.getName(), ward.getId()));

        return AdminConnectionResponse.of(connection, guardian, ward);
    }

    /** 강제 해제. ACTIVE 연결만 대상이며 양쪽 모두에게 해제 알림이 나간다. */
    @Transactional
    public void forceDisconnect(Long connectionId, String adminId) {
        Connection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CONNECTION_NOT_FOUND));

        connectionService.forceDisconnect(connectionId, adminId);

        auditLogService.log(adminId, AdminAuditAction.FORCE_DISCONNECT, String.valueOf(connectionId),
                String.format("강제 연결 해제: guardian=%s, ward=%s",
                        connection.getGuardianId(), connection.getWardId()));
    }

    private User getUserOrThrow(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
