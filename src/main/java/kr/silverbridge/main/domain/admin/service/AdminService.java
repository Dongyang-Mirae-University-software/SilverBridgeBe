package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.*;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementRepository;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.game.repository.GameResultRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.GameType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final AccessLogRepository accessLogRepository;
    private final ConnectionRepository connectionRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final GameResultRepository gameResultRepository;
    private final AnnouncementRepository announcementRepository;

    // =============================================
    // 사용자 관리
    // =============================================

    // 사용자 목록 조회 (페이징, role 필터링)
    // role 미입력 시 WARD + GUARDIAN 전체 조회, ADMIN 제외
    @Transactional(readOnly = true)
    public Page<UserSummaryResponse> getUsers(Role role, Pageable pageable) {
        List<Role> roles = (role != null) ? List.of(role) : List.of(Role.WARD, Role.GUARDIAN);
        return userRepository.findByRoleIn(roles, pageable)
                .map(UserSummaryResponse::from);
    }

    // 사용자 상세 조회
    @Transactional(readOnly = true)
    public UserDetailResponse getUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return UserDetailResponse.from(user);
    }

    // 피보호자/보호자 계정 상태 변경 (활성화 / 비활성화)
    @Transactional
    public void updateUserStatus(String userId, UserStatusUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        validateNotAdmin(user);

        switch (request.getStatus()) {
            case ACTIVE   -> user.activate();
            case INACTIVE -> user.deactivate();
            default       -> throw new CustomException(ErrorCode.INVALID_STATUS);
        }
    }

    // 사용자 역할 변경 (WARD ↔ GUARDIAN)
    @Transactional
    public void updateUserRole(String userId, UserRoleUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        validateNotAdmin(user);

        if (request.getRole() == Role.ADMIN) {
            throw new CustomException(ErrorCode.INVALID_ROLE);
        }

        user.updateRole(request.getRole());
    }

    // 사용자 강제 탈퇴 (계정 영구 삭제)
    @Transactional
    public void forceDeleteUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        validateNotAdmin(user);

        userRepository.delete(user);
    }

    // =============================================
    // 연결 관계 관리
    // =============================================

    // 전체 연결 관계 조회 (페이징)
    @Transactional(readOnly = true)
    public Page<ConnectionResponse> getConnections(Pageable pageable) {
        return connectionRepository.findAll(pageable)
                .map(this::mapToConnectionResponse);
    }

    // 특정 보호자의 피보호자 목록 조회
    @Transactional(readOnly = true)
    public Page<ConnectionResponse> getConnectionsByGuardian(String guardianId, Pageable pageable) {
        User guardian = userRepository.findById(guardianId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (guardian.getRole() != Role.GUARDIAN) {
            throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
        }

        return connectionRepository.findByGuardianId(guardianId, pageable)
                .map(this::mapToConnectionResponse);
    }

    // 관리자 강제 연결 (바로 ACTIVE)
    @Transactional
    public ConnectionResponse forceConnect(AdminForceConnectRequest request, String adminId) {
        User guardian = userRepository.findById(request.getGuardianId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        User ward = userRepository.findById(request.getWardId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (guardian.getRole() != Role.GUARDIAN || ward.getRole() != Role.WARD) {
            throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
        }

        if (guardian.getStatus() == Status.INACTIVE || ward.getStatus() == Status.INACTIVE) {
            throw new CustomException(ErrorCode.INACTIVE_USER);
        }

        if (connectionRepository.existsByGuardianIdAndWardIdAndStatusNot(
                request.getGuardianId(), request.getWardId(), ConnectionStatus.CANCELLED)) {
            throw new CustomException(ErrorCode.CONNECTION_ALREADY_EXISTS);
        }

        Connection connection = Connection.builder()
                .guardianId(request.getGuardianId())
                .wardId(request.getWardId())
                .status(ConnectionStatus.PENDING)
                .initiatedBy(adminId)
                .build();
        connection.activate();

        return ConnectionResponse.of(connectionRepository.save(connection), guardian, ward);
    }

    // 관리자 강제 연결 해제
    @Transactional
    public void forceDisconnect(Long connectionId) {
        Connection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CONNECTION_NOT_FOUND));

        connection.cancel();
    }

    // =============================================
    // 이상감지 이벤트 조회
    // =============================================

    // guardianId 미입력 시 전체 조회, 입력 시 해당 보호자의 피보호자 이벤트만 조회
    @Transactional(readOnly = true)
    public Page<AnomalyEventResponse> getAnomalyEvents(
            String guardianId,
            OffsetDateTime startDate,
            OffsetDateTime endDate,
            Pageable pageable
    ) {
        Page<AnomalyEvent> events;

        if (guardianId != null) {
            User guardian = userRepository.findById(guardianId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
            if (guardian.getRole() != Role.GUARDIAN) {
                throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
            }

            List<String> wardIds = anomalyEventRepository.findActiveWardIdsByGuardianId(guardianId);
            if (wardIds.isEmpty()) {
                return Page.empty(pageable);
            }
            events = anomalyEventRepository.findByWardIdsAndDateRange(wardIds, startDate, endDate, pageable);
        } else {
            events = anomalyEventRepository.findByDateRange(startDate, endDate, pageable);
        }

        return events.map(event -> {
            if (event.getWardId() == null) {
                return AnomalyEventResponse.ofDeleted(event);
            }
            User ward = userRepository.findById(event.getWardId()).orElse(null);
            return ward != null
                    ? AnomalyEventResponse.of(event, ward)
                    : AnomalyEventResponse.ofDeleted(event);
        });
    }

    // =============================================
    // 게임 결과 조회
    // =============================================

    @Transactional(readOnly = true)
    public Page<GameResultResponse> getGameResults(
            String userId,
            GameType gameType,
            OffsetDateTime startDate,
            OffsetDateTime endDate,
            Pageable pageable
    ) {
        if (userId != null) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
            if (user.getRole() != Role.WARD) {
                throw new CustomException(ErrorCode.INVALID_ROLE);
            }
        }

        return gameResultRepository.findByFilters(userId, gameType, startDate, endDate, pageable)
                .map(result -> {
                    User user = userRepository.findById(result.getUserId())
                            .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
                    return GameResultResponse.of(result, user);
                });
    }

    // =============================================
    // 공지 관리
    // =============================================

    // 공지 목록 조회 (isPublished 필터, 미입력 시 전체)
    @Transactional(readOnly = true)
    public Page<AnnouncementResponse> getAnnouncements(Boolean isPublished, Pageable pageable) {
        Page<Announcement> announcements = (isPublished != null)
                ? announcementRepository.findByIsPublished(isPublished, pageable)
                : announcementRepository.findAll(pageable);

        return announcements.map(a -> AnnouncementResponse.of(a, findAuthor(a.getAuthorId())));
    }

    // 공지 상세 조회
    @Transactional(readOnly = true)
    public AnnouncementResponse getAnnouncement(Long id) {
        Announcement announcement = findAnnouncement(id);
        return AnnouncementResponse.of(announcement, findAuthor(announcement.getAuthorId()));
    }

    // 공지 생성 (작성자 = 현재 로그인한 관리자)
    @Transactional
    public AnnouncementResponse createAnnouncement(AnnouncementCreateRequest request, String adminId) {
        Announcement announcement = Announcement.builder()
                .authorId(adminId)
                .title(request.getTitle())
                .content(request.getContent())
                .isPublished(false)
                .build();

        Announcement saved = announcementRepository.save(announcement);
        return AnnouncementResponse.of(saved, findAuthor(adminId));
    }

    // 공지 수정 (제목 + 내용)
    @Transactional
    public AnnouncementResponse updateAnnouncement(Long id, AnnouncementUpdateRequest request) {
        Announcement announcement = findAnnouncement(id);
        announcement.update(request.getTitle(), request.getContent());
        return AnnouncementResponse.of(announcement, findAuthor(announcement.getAuthorId()));
    }

    // 공지 발행 토글 (미발행 → 발행, 발행 → 취소)
    @Transactional
    public AnnouncementResponse togglePublish(Long id) {
        Announcement announcement = findAnnouncement(id);
        announcement.togglePublish();
        return AnnouncementResponse.of(announcement, findAuthor(announcement.getAuthorId()));
    }

    // 공지 삭제
    @Transactional
    public void deleteAnnouncement(Long id) {
        announcementRepository.delete(findAnnouncement(id));
    }

    // =============================================
    // 접속 로그 조회
    // =============================================

    @Transactional(readOnly = true)
    public Page<AccessLogResponse> getAccessLogs(Pageable pageable) {
        return accessLogRepository.findAll(pageable)
                .map(AccessLogResponse::from);
    }

    // =============================================
    // private 헬퍼
    // =============================================

    // ADMIN 계정 수정/삭제 차단
    private void validateNotAdmin(User user) {
        if (user.getRole() == Role.ADMIN) {
            throw new CustomException(ErrorCode.CANNOT_MODIFY_ADMIN);
        }
    }

    // Connection → ConnectionResponse 변환 (guardian/ward 조회 포함)
    private ConnectionResponse mapToConnectionResponse(Connection conn) {
        User g = userRepository.findById(conn.getGuardianId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        User w = userRepository.findById(conn.getWardId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return ConnectionResponse.of(conn, g, w);
    }

    // 공지 조회 (없으면 예외)
    private Announcement findAnnouncement(Long id) {
        return announcementRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
    }

    // 공지 작성자 조회 (탈퇴 시 null 반환)
    private User findAuthor(String authorId) {
        if (authorId == null) return null;
        return userRepository.findById(authorId).orElse(null);
    }
}
