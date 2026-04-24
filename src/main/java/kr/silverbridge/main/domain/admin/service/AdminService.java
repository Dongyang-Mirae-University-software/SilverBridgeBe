package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AccessLogResponse;
import kr.silverbridge.main.domain.admin.dto.AnomalyEventResponse;
import kr.silverbridge.main.domain.admin.dto.GameResultResponse;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import kr.silverbridge.main.domain.game.repository.GameResultRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.GameType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자용 조회 서비스 (리포트/로그 계열)
 * 이상감지 이벤트, 게임 결과, 접속 로그 등 읽기 전용 이력 조회를 담당한다.
 * 사용자/연결/공지 관리는 각 도메인 서비스에 위임.
 *
 * @see AdminUserService
 * @see AdminConnectionService
 * @see AdminAnnouncementService
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final AccessLogRepository accessLogRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final GameResultRepository gameResultRepository;

    // 이상감지 이벤트 조회 (보호자 필터 + 기간 필터, 최신 감지순)
    @Transactional(readOnly = true)
    public List<AnomalyEventResponse> getAnomalyEvents(
            String guardianId,
            OffsetDateTime startDate,
            OffsetDateTime endDate
    ) {
        List<AnomalyEvent> events;

        if (guardianId != null) {
            User guardian = userRepository.findById(guardianId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
            if (guardian.getRole() != Role.GUARDIAN) {
                throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
            }

            List<String> wardIds = anomalyEventRepository.findActiveWardIdsByGuardianId(guardianId);
            if (wardIds.isEmpty()) {
                return Collections.emptyList();
            }
            events = anomalyEventRepository.findByWardIdsAndDateRange(wardIds, startDate, endDate);
        } else {
            events = anomalyEventRepository.findByDateRange(startDate, endDate);
        }

        // 배치 사용자 조회 (N+1 방지)
        Set<String> wardIds = events.stream()
                .map(AnomalyEvent::getWardId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, User> wardMap = userRepository.findAllById(wardIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return events.stream().map(event -> {
            if (event.getWardId() == null) {
                return AnomalyEventResponse.ofDeleted(event);
            }
            User ward = wardMap.get(event.getWardId());
            return ward != null
                    ? AnomalyEventResponse.of(event, ward)
                    : AnomalyEventResponse.ofDeleted(event);
        }).toList();
    }

    // 게임 결과 조회 (사용자 + 게임 유형 + 기간 필터, 최신 플레이순)
    @Transactional(readOnly = true)
    public List<GameResultResponse> getGameResults(
            String userId,
            GameType gameType,
            OffsetDateTime startDate,
            OffsetDateTime endDate
    ) {
        if (userId != null) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
            if (user.getRole() != Role.WARD) {
                throw new CustomException(ErrorCode.INVALID_ROLE);
            }
        }

        // 배치 사용자 조회 (N+1 방지)
        var results = gameResultRepository.findByFilters(userId, gameType, startDate, endDate);
        Set<String> userIds = results.stream()
                .map(r -> r.getUserId())
                .collect(Collectors.toSet());
        Map<String, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return results.stream().map(result -> {
            User user = userMap.get(result.getUserId());
            if (user == null) throw new CustomException(ErrorCode.USER_NOT_FOUND);
            return GameResultResponse.of(result, user);
        }).toList();
    }

    // 접속 로그 조회 (최신 발생순)
    @Transactional(readOnly = true)
    public List<AccessLogResponse> getAccessLogs() {
        return accessLogRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(AccessLogResponse::from)
                .toList();
    }
}
