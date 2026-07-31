package kr.silverbridge.main.domain.connection.service;

import kr.silverbridge.main.domain.connection.dto.ConnectionRequestDto;
import kr.silverbridge.main.domain.connection.dto.ConnectionResponse;
import kr.silverbridge.main.domain.connection.dto.PendingConnectionResponse;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.event.ConnectionAcceptedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionRefusedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionRequestedEvent;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionService {

    private final ConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    // ─── 보호자 API ──────────────────────────────────────────────

    // 보호자: 내 피보호자 목록 조회 (ACTIVE + PENDING, 최신 요청순)
    // — 사이드바 "피보호자 리스트" 화면에서 활용
    @Transactional(readOnly = true)
    public List<ConnectionResponse> getMyWards(String guardianId) {
        List<Connection> connections = connectionRepository
                .findByGuardianIdAndStatusInOrderByCreatedAtDesc(guardianId,
                        List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING));
        return buildResponseFromGuardianView(connections);
    }

    // 보호자: 내 ACTIVE 피보호자 ID 목록 (최신 연결순)
    // — SOS 이력처럼 "연결된 피보호자 전원의 자원"을 모아 보여줄 때 재사용. getMyWards()는 PENDING까지 섞여
    //   있어 인가 목록으로 쓸 수 없다(수락 전 피보호자의 이력이 노출됨).
    @Transactional(readOnly = true)
    public List<String> getActiveWardIds(String guardianId) {
        return connectionRepository
                .findByGuardianIdAndStatusInOrderByCreatedAtDesc(guardianId, List.of(ConnectionStatus.ACTIVE))
                .stream()
                .map(Connection::getWardId)
                .distinct()
                .toList();
    }

    // 보호자-피보호자 쌍이 현재 ACTIVE 연결인지 — 타 도메인(SOS 이력·ACK)의 IDOR 인가 판정용.
    // 연결 판정 로직을 connection 도메인 안에 두어 타 도메인이 상태값을 직접 다루지 않게 한다.
    @Transactional(readOnly = true)
    public boolean isActiveConnection(String guardianId, String wardId) {
        return connectionRepository.existsByGuardianIdAndWardIdAndStatusIn(
                guardianId, wardId, List.of(ConnectionStatus.ACTIVE));
    }

    // 보호자: 본인이 보낸 모든 연결 요청 이력 (PENDING + ACTIVE + CANCELLED, 최신 요청순)
    // — "피보호자 등록" 화면의 "요청 내역" 테이블에서 거절·취소된 이력까지 함께 노출
    @Transactional(readOnly = true)
    public List<ConnectionResponse> getMyConnectionRequests(String guardianId) {
        List<Connection> connections = connectionRepository
                .findByGuardianIdOrderByCreatedAtDesc(guardianId);
        return buildResponseFromGuardianView(connections);
    }

    // 보호자: 피보호자에게 페어링 요청 (보호자 → 피보호자)
    // 알림은 ConnectionNotificationListener가 커밋 후 발송
    @Transactional
    public void requestConnectionAsGuardian(String guardianId, ConnectionRequestDto request) {
        String wardId = request.getTargetId();
        validateConnectionRequest(guardianId, wardId, Role.GUARDIAN, Role.WARD);

        Connection connection = Connection.builder()
                .guardianId(guardianId)
                .wardId(wardId)
                .status(ConnectionStatus.PENDING)
                .initiatedBy(guardianId)
                .relation(request.getRelation())
                .build();
        connectionRepository.save(connection);

        User guardian = requireUser(guardianId);
        eventPublisher.publishEvent(new ConnectionRequestedEvent(
                connection.getId(), guardianId, wardId, guardian.getName(), request.getRelation()
        ));
        log.info("연결 요청 생성: connectionId={}, guardianId={}, wardId={}",
                connection.getId(), guardianId, wardId);
    }

    // 보호자: 페어링 요청 취소 (PENDING만)
    @Transactional
    public void cancelPendingAsGuardian(String guardianId, Long connectionId) {
        Connection connection = getConnectionForGuardian(guardianId, connectionId);
        if (connection.getStatus() != ConnectionStatus.PENDING) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_PENDING);
        }
        connection.cancel();
        log.info("연결 요청 취소(보호자): connectionId={}, guardianId={}", connectionId, guardianId);
    }

    // 보호자: 연결 해제 (ACTIVE만)
    // 알림은 ConnectionNotificationListener가 커밋 후 발송
    @Transactional
    public void disconnectAsGuardian(String guardianId, Long connectionId) {
        Connection connection = getConnectionForGuardian(guardianId, connectionId);
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_ACTIVE);
        }
        String wardId = connection.getWardId();
        connection.disconnect();

        eventPublisher.publishEvent(new ConnectionDisconnectedEvent(
                connectionId, wardId, ConnectionDisconnectedEvent.DisconnectedBy.GUARDIAN
        ));
        log.info("연결 해제(보호자): connectionId={}, guardianId={}, wardId={}",
                connectionId, guardianId, wardId);
    }

    // ─── 피보호자 API ─────────────────────────────────────────────

    // 피보호자: 내 보호자 목록 조회 (ACTIVE만, 연결 오래된 순)
    // — 피보호자웹 "내 보호자 리스트" 카드
    @Transactional(readOnly = true)
    public List<ConnectionResponse> getActiveGuardians(String wardId) {
        List<Connection> connections = connectionRepository
                .findByWardIdAndStatusOrderByCreatedAtAsc(wardId, ConnectionStatus.ACTIVE);
        return buildResponseFromWardView(connections);
    }

    // 피보호자: 내 ACTIVE 보호자 ID 목록 (연결 오래된 순)
    // — 긴급 SOS 등 "ACTIVE 보호자 전원에게 발송"이 필요한 곳에서 재사용. 보호자 조회 로직을 connection 도메인에 둔다.
    @Transactional(readOnly = true)
    public List<String> getActiveGuardianIds(String wardId) {
        return connectionRepository
                .findByWardIdAndStatusOrderByCreatedAtAsc(wardId, ConnectionStatus.ACTIVE)
                .stream().map(Connection::getGuardianId).toList();
    }

    // 피보호자: 보호자가 보낸 PENDING 요청 목록 조회 (요청일 최신순)
    // — 피보호자웹 "요청온 목록" 카드. 전화번호는 마스킹, 주소는 미노출.
    @Transactional(readOnly = true)
    public List<PendingConnectionResponse> getPendingRequests(String wardId) {
        List<Connection> connections = connectionRepository
                .findByWardIdAndStatusOrderByCreatedAtDesc(wardId, ConnectionStatus.PENDING);
        List<String> guardianIds = connections.stream().map(Connection::getGuardianId).toList();
        Map<String, User> guardianMap = userRepository.findAllById(guardianIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return connections.stream()
                .map(c -> PendingConnectionResponse.from(c, guardianMap.get(c.getGuardianId())))
                .toList();
    }

    // 피보호자: 페어링 요청 수락 (보호자가 보낸 요청)
    // 알림은 ConnectionNotificationListener가 커밋 후 발송
    @Transactional
    public void acceptConnectionAsWard(String wardId, Long connectionId) {
        Connection connection = getConnectionForWard(wardId, connectionId);
        if (connection.getStatus() != ConnectionStatus.PENDING) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_PENDING);
        }
        if (wardId.equals(connection.getInitiatedBy())) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_AUTHORIZED);
        }
        connection.activate();

        eventPublisher.publishEvent(new ConnectionAcceptedEvent(
                connectionId, connection.getGuardianId()
        ));
        log.info("연결 수락(피보호자): connectionId={}, wardId={}, guardianId={}",
                connectionId, wardId, connection.getGuardianId());
    }

    // 피보호자: 보호자 요청 거절 (PENDING만)
    @Transactional
    public void refuseConnectionAsWard(String wardId, Long connectionId) {
        Connection connection = getConnectionForWard(wardId, connectionId);
        if (connection.getStatus() != ConnectionStatus.PENDING) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_PENDING);
        }
        connection.refuse();

        eventPublisher.publishEvent(new ConnectionRefusedEvent(
                connectionId, connection.getGuardianId()
        ));
        log.info("연결 거절(피보호자): connectionId={}, wardId={}, guardianId={}",
                connectionId, wardId, connection.getGuardianId());
    }

    // 피보호자: 연결 해제 (ACTIVE만)
    // 알림은 ConnectionNotificationListener가 커밋 후 발송
    @Transactional
    public void disconnectAsWard(String wardId, Long connectionId) {
        Connection connection = getConnectionForWard(wardId, connectionId);
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_ACTIVE);
        }
        String guardianId = connection.getGuardianId();
        connection.disconnect();

        eventPublisher.publishEvent(new ConnectionDisconnectedEvent(
                connectionId, guardianId, ConnectionDisconnectedEvent.DisconnectedBy.WARD
        ));
        log.info("연결 해제(피보호자): connectionId={}, wardId={}, guardianId={}",
                connectionId, wardId, guardianId);
    }

    // ─── 계정 탈퇴 연계 ───────────────────────────────────────────

    // 회원 탈퇴 시 본인이 참여(보호자/피보호자)한 live 연결을 정리한다 (D-USER-3).
    // ACTIVE → DISCONNECTED(상대에게 해제 알림) / PENDING → CANCELLED(무알림, 기존 취소·거절과 동일).
    // UserWithdrawnEvent 를 받은 UserWithdrawalConnectionListener 가 탈퇴 커밋 후 호출한다.
    @Transactional
    public void tearDownConnectionsOnWithdrawal(String withdrawnUserId) {
        List<Connection> connections = connectionRepository.findByParticipantAndStatusIn(
                withdrawnUserId, List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING));

        for (Connection connection : connections) {
            if (connection.getStatus() == ConnectionStatus.ACTIVE) {
                boolean withdrawnIsGuardian = connection.getGuardianId().equals(withdrawnUserId);
                String notifyTargetId = withdrawnIsGuardian ? connection.getWardId() : connection.getGuardianId();
                ConnectionDisconnectedEvent.DisconnectedBy by = withdrawnIsGuardian
                        ? ConnectionDisconnectedEvent.DisconnectedBy.GUARDIAN
                        : ConnectionDisconnectedEvent.DisconnectedBy.WARD;

                connection.disconnect();
                eventPublisher.publishEvent(new ConnectionDisconnectedEvent(
                        connection.getId(), notifyTargetId, by));
            } else { // PENDING — 상대 알림 없이 취소 (기존 cancel/refuse 와 동일)
                connection.cancel();
            }
        }

        if (!connections.isEmpty()) {
            log.info("탈퇴 연결 정리: userId={}, 정리 건수={}", withdrawnUserId, connections.size());
        }
    }

    // ─── 내부 헬퍼 ────────────────────────────────────────────────

    private void validateConnectionRequest(String requesterId, String targetId,
                                           Role requesterRole, Role targetRole) {
        if (requesterId.equals(targetId)) {
            throw new CustomException(ErrorCode.CANNOT_CONNECT_SELF);
        }

        User requester = requireUser(requesterId);
        if (requester.getRole() != requesterRole) {
            throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
        }

        User target = requireUser(targetId);
        // 탈퇴(INACTIVE) 대상은 존재를 드러내지 않고 미존재로 처리 (D-USER-3)
        if (target.getStatus() != Status.ACTIVE) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
        if (target.getRole() != targetRole) {
            throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
        }

        String guardianId = (requesterRole == Role.GUARDIAN) ? requesterId : targetId;
        String wardId     = (requesterRole == Role.WARD)     ? requesterId : targetId;

        // 진행 중(PENDING)·활성(ACTIVE) 연결만 중복으로 차단. CANCELLED/REFUSED/DISCONNECTED는 재요청 허용.
        if (connectionRepository.existsByGuardianIdAndWardIdAndStatusIn(
                guardianId, wardId, List.of(ConnectionStatus.PENDING, ConnectionStatus.ACTIVE))) {
            throw new CustomException(ErrorCode.CONNECTION_ALREADY_EXISTS);
        }
    }

    private Connection getConnectionForGuardian(String guardianId, Long connectionId) {
        Connection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CONNECTION_NOT_FOUND));
        if (!connection.getGuardianId().equals(guardianId)) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_AUTHORIZED);
        }
        return connection;
    }

    private Connection getConnectionForWard(String wardId, Long connectionId) {
        Connection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CONNECTION_NOT_FOUND));
        if (!connection.getWardId().equals(wardId)) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_AUTHORIZED);
        }
        return connection;
    }

    private List<ConnectionResponse> buildResponseFromGuardianView(List<Connection> connections) {
        List<String> wardIds = connections.stream().map(Connection::getWardId).toList();
        Map<String, User> wardMap = userRepository.findAllById(wardIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return connections.stream()
                .map(c -> ConnectionResponse.fromGuardianView(c, wardMap.get(c.getWardId())))
                .toList();
    }

    private List<ConnectionResponse> buildResponseFromWardView(List<Connection> connections) {
        List<String> guardianIds = connections.stream().map(Connection::getGuardianId).toList();
        Map<String, User> guardianMap = userRepository.findAllById(guardianIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return connections.stream()
                .map(c -> ConnectionResponse.fromWardView(c, guardianMap.get(c.getGuardianId())))
                .toList();
    }

    private User requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
