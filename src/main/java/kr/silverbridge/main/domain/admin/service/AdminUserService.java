package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminUserConnectionFilter;
import kr.silverbridge.main.domain.admin.dto.AdminUserConnectionItem;
import kr.silverbridge.main.domain.admin.dto.AdminUserCountsResponse;
import kr.silverbridge.main.domain.admin.dto.AdminUserDetailResponse;
import kr.silverbridge.main.domain.admin.dto.AdminUserListItem;
import kr.silverbridge.main.domain.admin.dto.AdminUserStatusFilter;
import kr.silverbridge.main.domain.admin.dto.AdminUserUpdateRequest;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.event.UserRestrictedEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.domain.user.service.UserService;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 회원관리 - 목록·검색·상세·정보 수정·강제 탈퇴.
 *
 * <p>인가는 {@code SecurityConfig}의 {@code /api/admin/**} 경로 규칙과 컨트롤러의 클래스 레벨
 * {@code @PreAuthorize}가 이중으로 담당한다(관리자 대시보드·이상감지와 같은 방식).</p>
 *
 * <p><b>관리자 계정은 조회만 된다.</b> 목록·탭 건수에는 나오지만 수정·삭제 대상이 아니며, 대상이 ADMIN이면
 * 403({@code CANNOT_MODIFY_ADMIN})이다. 관리자가 관리자를 지울 수 있으면 서로를 지워 운영 주체가 사라진다.</p>
 *
 * <p><b>개인 정보를 여는 경로라 쓰기 조작은 모두 감사 로그에 남긴다.</b> 집계 숫자만 보는 대시보드가
 * 남기지 않는 것과 대비되는 지점이다(2026-09-02 정책). 조회는 남기지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 50;

    /** 목록에 표시할 연결. 종료된 이력(CANCELLED·REFUSED·DISCONNECTED)은 "지금 맺고 있는 관계"가 아니라 제외한다. */
    private static final List<ConnectionStatus> LINKED_STATUSES =
            List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING);

    private final UserRepository userRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final UserService userService;
    private final AdminAuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 회원 목록. 키워드는 이름·이메일·전화번호와 <b>연결된 상대의 이름</b>까지 훑는다.
     *
     * @param keyword          검색어. null·공백이면 조건 무시
     * @param role             역할 탭. null이면 전체
     * @param statusFilter     계정 상태 필터. null이면 ALL
     * @param connectionFilter 연결 상태 필터. null이면 ALL
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserListItem> getUsers(String keyword, Role role,
                                                    AdminUserStatusFilter statusFilter,
                                                    AdminUserConnectionFilter connectionFilter,
                                                    int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        var users = userRepository.searchForAdmin(
                Status.INACTIVE,
                role,
                AdminUserStatusFilter.orDefault(statusFilter).toStatus(),
                normalizeKeyword(keyword),
                AdminUserConnectionFilter.orDefault(connectionFilter).toCode(),
                LINKED_STATUSES,
                ConnectionStatus.ACTIVE,
                ConnectionStatus.PENDING,
                pageable);

        // 연결을 회원마다 조회하면 페이지 크기만큼 쿼리가 늘어난다(N+1). 한 번에 읽어 배분한다.
        // 빈 페이지에서 일찍 반환하지 않는 이유는 전체 건수 같은 페이징 정보를 그대로 살리기 위해서다.
        Map<String, List<AdminUserConnectionItem>> connections = resolveConnections(users.getContent());

        return PageResponse.of(users.map(user ->
                AdminUserListItem.of(user, connections.getOrDefault(user.getId(), List.of()))));
    }

    /** 회원 상세. 연결은 전체 목록을 싣는다(목록은 요약만 싣는다). */
    @Transactional(readOnly = true)
    public AdminUserDetailResponse getUser(String userId) {
        User user = getUserOrThrow(userId);
        return AdminUserDetailResponse.of(user,
                resolveConnections(List.of(user)).getOrDefault(userId, List.of()));
    }

    /** 탭별 건수. 목록과 같은 모집단이어야 하므로 여기서도 탈퇴 진행 중인 계정을 제외한다. */
    @Transactional(readOnly = true)
    public AdminUserCountsResponse getCounts() {
        Map<Role, Long> counts = userRepository.countByRoleExcludingStatus(Status.INACTIVE).stream()
                .collect(Collectors.toMap(UserRepository.RoleCount::getRole, UserRepository.RoleCount::getCnt));

        long guardian = counts.getOrDefault(Role.GUARDIAN, 0L);
        long ward = counts.getOrDefault(Role.WARD, 0L);
        long admin = counts.getOrDefault(Role.ADMIN, 0L);
        return new AdminUserCountsResponse(guardian + ward + admin, guardian, ward, admin);
    }

    /**
     * 회원 정보 수정. 세 축(이름·역할·계정 상태)을 한 번에 받고, <b>실제로 바뀐 축마다 따로</b> 감사 로그를 남긴다.
     *
     * <p>화면의 "저장"이 한 번이라 엔드포인트를 쪼개면 프론트가 저장 한 번에 여러 번 호출하고 부분 실패가 생긴다.
     * 반대로 감사 로그까지 하나로 합치면 "무엇이 바뀌었는지"를 나중에 가려낼 수 없다.</p>
     */
    @Transactional
    public void updateUser(String userId, AdminUserUpdateRequest request, String adminId) {
        User user = getUserOrThrow(userId);
        validateNotAdmin(user, adminId);
        validateResultingCombination(user, request);

        applyName(user, request.name(), adminId);
        applyRole(user, request.role(), adminId);
        applyStatus(user, request.status(), request.statusReason(), adminId);
    }

    /**
     * 축별로 따로 검사하지 않고 <b>바뀐 뒤의 조합</b>을 한 번에 본다.
     *
     * <p>피보호자는 이용 제한할 수 없는데, 축별 검사만 두면 "보호자를 정지시킨 뒤 역할을 피보호자로
     * 바꾸는" 두 단계로 그 상태를 만들 수 있다. 반대로 순서를 정해 검사하면
     * "제한을 풀면서 동시에 역할을 바꾸는" 정상 요청이 잘못 거부된다.</p>
     */
    private void validateResultingCombination(User user, AdminUserUpdateRequest request) {
        Role resultingRole = request.role() != null ? request.role() : user.getRole();
        Status resultingStatus = request.status() != null ? request.status() : user.getStatus();

        if (resultingRole == Role.WARD && resultingStatus == Status.RESTRICTED) {
            // 피보호자 계정은 편의 기능이 아니라 안전망 그 자체다 - 로그인이 막히면 SOS를 보낼 수 없다.
            throw new CustomException(ErrorCode.WARD_CANNOT_BE_RESTRICTED);
        }
    }

    /**
     * 강제 탈퇴. 일반 탈퇴와 <b>같은 2단계 경로</b>를 탄다 - 상태 변경 + 이벤트(1단계) 커밋 후 영구 삭제(2단계).
     *
     * <p>{@code userRepository.delete()}로 곧장 지우면 AFTER_COMMIT 리스너를 건너뛰어 연결 상대 알림·
     * FCM 토큰 정리·WITHDRAW 접속로그가 유실된다. 행 정리는 FK CASCADE가 해버려 겉보기엔 성공한 것처럼 보인다.</p>
     *
     * <p>감사 로그를 <b>삭제보다 먼저</b> 남긴다. 뒤에 남기면 로그 기록이 실패했을 때 계정만 사라지고
     * 누가 지웠는지가 남지 않는다(반대로 로그만 남고 삭제가 실패하는 쪽은 스윕이 정리하거나 아무 일도 없다).</p>
     */
    public void forceDelete(String userId, String adminId, String ipAddress, String userAgent) {
        User user = getUserOrThrow(userId);
        validateNotAdmin(user, adminId);

        auditLogService.log(adminId, AdminAuditAction.USER_FORCE_DELETE, userId,
                String.format("강제 탈퇴: %s (%s)", user.getName(), user.getEmail()));

        userService.forceWithdraw(userId, ipAddress, userAgent);

        // purge 실패 시 1회 재시도 - 그래도 실패하면 INACTIVE 행이 남지만
        // WithdrawnUserPurgeScheduler 스윕이 회수한다(일반 탈퇴와 같은 처리).
        try {
            userService.purgeWithdrawnUser(userId);
        } catch (RuntimeException first) {
            try {
                userService.purgeWithdrawnUser(userId);
            } catch (RuntimeException retry) {
                log.error("[ADMIN-FORCE-DELETE] 영구 삭제 실패, 스윕 스케줄러가 회수 예정 userId={}", userId, retry);
            }
        }
    }

    // ─── 수정 축별 처리 ────────────────────────────────────────────

    private void applyName(User user, String name, String adminId) {
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        if (trimmed.equals(user.getName())) {
            return;
        }

        String before = user.getName();
        user.changeName(trimmed);
        auditLogService.log(adminId, AdminAuditAction.USER_NAME_CHANGE, user.getId(),
                String.format("이름 변경: %s → %s", before, trimmed));
    }

    /**
     * 역할 변경. 바뀌면 기존 연결을 정리한다 - 역할이 뒤집히면 보호자-피보호자 방향이 어긋나 관계가 뜻을 잃는다.
     * ACTIVE 연결의 상대에게는 해제 알림이 나가고, 수락 전(PENDING) 요청은 조용히 취소된다.
     */
    private void applyRole(User user, Role role, String adminId) {
        if (role == null || role == user.getRole()) {
            return;
        }
        if (role == Role.ADMIN) {
            // 관리자 승격 경로를 열면 회원관리 화면 하나로 권한을 만들어낼 수 있게 된다.
            throw new CustomException(ErrorCode.INVALID_ROLE);
        }

        Role before = user.getRole();
        user.updateRole(role);
        int clearedConnections = connectionService.tearDownConnectionsOnRoleChange(user.getId());

        auditLogService.log(adminId, AdminAuditAction.USER_ROLE_CHANGE, user.getId(),
                String.format("역할 변경: %s → %s (연결 %d건 해제)",
                        roleLabel(before), roleLabel(role), clearedConnections));
    }

    /**
     * 계정 상태 변경. 이용 중(ACTIVE) ↔ 이용 제한(RESTRICTED)만 오갈 수 있다.
     *
     * <p>제한으로 바꾸면 {@link UserRestrictedEvent}로 토큰까지 끊는다. 상태만 바꾸면 이미 발급된
     * access token이 만료(30분)까지 살아 있어 정지가 즉시 듣지 않는다.</p>
     *
     * <p><b>피보호자는 이용 제한할 수 없다.</b> 로그인이 막히면 SOS를 보낼 수 없게 되는데,
     * 피보호자 계정은 편의 기능이 아니라 안전망 그 자체다. 탈취가 의심되면 정지 대신
     * 비밀번호 재설정으로 세션만 끊는 것이 맞다(안전망은 유지된다).</p>
     */
    private void applyStatus(User user, Status status, String reason, String adminId) {
        if (status == null || status == user.getStatus()) {
            return;
        }
        if (status == Status.INACTIVE) {
            // 탈퇴는 상태 변경이 아니라 삭제다. 여기서 INACTIVE로 바꾸면 정리 리스너를 건너뛴 채
            // 스윕 스케줄러가 계정을 지워버린다(2026-06-11 INACTIVE 불변식).
            throw new CustomException(ErrorCode.INVALID_STATUS);
        }
        Status before = user.getStatus();
        String trimmedReason = StringUtils.hasText(reason) ? reason.trim() : null;
        if (status == Status.RESTRICTED) {
            user.restrict(trimmedReason);
            eventPublisher.publishEvent(new UserRestrictedEvent(user.getId()));
        } else {
            user.activate();
        }

        auditLogService.log(adminId, AdminAuditAction.USER_STATUS_CHANGE, user.getId(),
                String.format("계정 상태 변경: %s → %s%s",
                        statusLabel(before), statusLabel(status),
                        trimmedReason == null ? "" : " (사유: " + trimmedReason + ")"));
    }

    // ─── 내부 헬퍼 ────────────────────────────────────────────────

    /**
     * 회원별 연결 목록. 양쪽 참여자가 모두 조회 대상일 수 있으므로 두 방향을 모두 확인해 배분한다.
     * 정렬은 ACTIVE 먼저, 그 다음 오래된 순 - 대표로 보여줄 한 명이 매 조회마다 바뀌지 않게 한다.
     */
    private Map<String, List<AdminUserConnectionItem>> resolveConnections(List<User> users) {
        if (users.isEmpty()) {
            return Map.of();
        }

        Set<String> userIds = users.stream().map(User::getId).collect(Collectors.toSet());
        List<Connection> connections =
                connectionRepository.findByParticipantsAndStatusIn(userIds, LINKED_STATUSES);
        if (connections.isEmpty()) {
            return Map.of();
        }

        Set<String> counterpartIds = new HashSet<>();
        for (Connection connection : connections) {
            counterpartIds.add(connection.getGuardianId());
            counterpartIds.add(connection.getWardId());
        }
        Map<String, User> counterparts = userRepository.findAllById(counterpartIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<Connection> sorted = new ArrayList<>(connections);
        sorted.sort(Comparator
                .comparing((Connection c) -> c.getStatus() == ConnectionStatus.ACTIVE ? 0 : 1)
                .thenComparing(Connection::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, List<AdminUserConnectionItem>> result = new LinkedHashMap<>();
        for (Connection connection : sorted) {
            if (userIds.contains(connection.getGuardianId())) {
                addConnection(result, connection.getGuardianId(),
                        counterparts.get(connection.getWardId()), connection);
            }
            if (userIds.contains(connection.getWardId())) {
                addConnection(result, connection.getWardId(),
                        counterparts.get(connection.getGuardianId()), connection);
            }
        }
        return result;
    }

    private void addConnection(Map<String, List<AdminUserConnectionItem>> result, String ownerId,
                               User counterpart, Connection connection) {
        if (counterpart == null) {
            return; // 상대가 이미 삭제된 연결 - 화면에 이름 없는 행을 만들지 않는다
        }
        result.computeIfAbsent(ownerId, key -> new ArrayList<>())
                .add(new AdminUserConnectionItem(
                        counterpart.getId(),
                        counterpart.getName(),
                        counterpart.getRole(),
                        connection.getRelation(),
                        connection.getStatus()));
    }

    private User getUserOrThrow(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    /** 관리자 계정은 수정·삭제 대상이 아니다. 시도 자체를 흔적으로 남긴다. */
    private void validateNotAdmin(User target, String adminId) {
        if (target.getRole() == Role.ADMIN) {
            log.warn("[ADMIN-MODIFY-BLOCKED] 관리자 계정 변경 시도 adminId={} targetId={}", adminId, target.getId());
            throw new CustomException(ErrorCode.CANNOT_MODIFY_ADMIN);
        }
    }

    /**
     * LIKE 메타문자 이스케이프 + 소문자화. JPQL의 {@code escape '\'} 절과 짝을 이룬다.
     * 소문자화는 이메일이 대소문자 섞여 저장되기 때문이다(가입 시 정규화하지 않는다).
     */
    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
                .toLowerCase();
    }

    private int normalizeSize(int size) {
        return Math.clamp(size, 1, MAX_PAGE_SIZE);
    }

    private static String roleLabel(Role role) {
        return switch (role) {
            case WARD -> "피보호자";
            case GUARDIAN -> "보호자";
            case ADMIN -> "관리자";
        };
    }

    private static String statusLabel(Status status) {
        return switch (status) {
            case ACTIVE -> "이용 중";
            case RESTRICTED -> "이용 제한";
            case INACTIVE -> "탈퇴 처리 중";
        };
    }
}
