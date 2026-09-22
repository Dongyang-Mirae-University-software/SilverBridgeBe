package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationCategory;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationItem;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationPeriod;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationSummaryResponse;
import kr.silverbridge.main.domain.notification.entity.NotificationLog;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
import kr.silverbridge.main.domain.notification.repository.NotificationLogRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 알림 이력 조회(보기 전용). 조회라서 감사 로그는 남기지 않는다(관리자 조회 공통 규칙).
 *
 * <p>연결 여부로 좁히지 않고 전체를 본다 - 운영 현황 화면이다.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    private static final int MAX_PAGE_SIZE = 50;

    /** 검색 결과가 없는 쪽 IN 절에 넣는 값. 사용자 ID는 빈 문자열일 수 없다. */
    private static final List<String> NO_MATCH = List.of("");

    private final NotificationLogRepository notificationLogRepository;
    private final UserRepository userRepository;
    private final ConnectionRepository connectionRepository;

    /**
     * 알림 이력 목록(최신순).
     *
     * @param category 탭. null이면 전체
     * @param result   결과. null이면 전체("전송 실패" 탭 = FAILED)
     * @param period   기간(발송 시각 기준, KST). null이면 최근 7일
     * @param keyword  피보호자·수신자 이름 부분일치. null·공백이면 무시
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminNotificationItem> getLogs(AdminNotificationCategory category,
                                                       NotificationLogResult result,
                                                       AdminNotificationPeriod period, String keyword,
                                                       int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        TypeScope types = TypeScope.of(category);
        KeywordScope scope = resolveKeyword(keyword);
        Page<NotificationLog> logs = notificationLogRepository.searchForAdmin(
                lowerBound(period), types.applied(), types.types(), result,
                scope.applied(), scope.userIds(), pageable);

        List<NotificationLog> content = logs.getContent();

        // 이름·역할은 한 번의 조회로 채운다(행마다 조회하면 페이지 크기만큼 쿼리가 늘어난다).
        Set<String> userIds = new HashSet<>();
        content.forEach(log -> {
            userIds.add(log.getRecipientId());
            if (log.getWardId() != null) {
                userIds.add(log.getWardId());
            }
        });
        Map<String, User> users = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        Map<Pair, String> relations = resolveRelations(content);

        return PageResponse.of(logs.map(log -> {
            User recipient = users.get(log.getRecipientId());
            User ward = log.getWardId() == null ? null : users.get(log.getWardId());
            return AdminNotificationItem.of(log,
                    ward == null ? null : ward.getName(),
                    recipient == null ? null : recipient.getName(),
                    recipient == null ? null : recipient.getRole(),
                    relations.get(new Pair(log.getRecipientId(), log.getWardId())));
        }));
    }

    /**
     * 요약 카드. 목록과 같은 기간·탭·검색 조건을 받고, 결과 필터는 받지 않는다(카드가 결과별 숫자다).
     */
    @Transactional(readOnly = true)
    public AdminNotificationSummaryResponse getSummary(AdminNotificationCategory category,
                                                       AdminNotificationPeriod period, String keyword) {
        TypeScope types = TypeScope.of(category);
        KeywordScope scope = resolveKeyword(keyword);
        Map<NotificationLogResult, Long> counts = new EnumMap<>(NotificationLogResult.class);
        notificationLogRepository.countByResult(lowerBound(period), types.applied(), types.types(),
                        scope.applied(), scope.userIds())
                .forEach(row -> counts.put(row.getResult(), row.getTotal()));

        long plainDelivered = counts.getOrDefault(NotificationLogResult.DELIVERED, 0L);
        long smsFallback = counts.getOrDefault(NotificationLogResult.SMS_FALLBACK, 0L);
        long failed = counts.getOrDefault(NotificationLogResult.FAILED, 0L);
        long notSent = counts.getOrDefault(NotificationLogResult.NOT_SENT, 0L);
        long delivered = plainDelivered + smsFallback;

        return new AdminNotificationSummaryResponse(
                AdminNotificationPeriod.orDefault(period),
                delivered + failed + notSent,
                delivered,
                smsFallback,
                failed,
                notSent);
    }

    /**
     * (수신자, 피보호자) 쌍의 관계 라벨. 수신자가 보호자이고 피보호자와 <b>지금</b> ACTIVE 연결일 때만 있다.
     *
     * <p>관계는 "보호자가 피보호자에게 어떤 사람인가" 한 방향뿐이라(예: 아들) 수신자가 피보호자 본인인 행에는 없다.
     * 연결이 끝난 뒤의 과거 이력에는 라벨이 비어 보인다 - 발송 당시 값을 따로 저장하지 않았다.</p>
     */
    private Map<Pair, String> resolveRelations(List<NotificationLog> logs) {
        Set<String> guardianIds = new HashSet<>();
        Set<String> wardIds = new HashSet<>();
        for (NotificationLog log : logs) {
            if (log.getWardId() != null && !log.getWardId().equals(log.getRecipientId())) {
                guardianIds.add(log.getRecipientId());
                wardIds.add(log.getWardId());
            }
        }
        if (guardianIds.isEmpty()) {
            return Map.of();
        }
        return connectionRepository
                .findByGuardianIdInAndWardIdInAndStatus(guardianIds, wardIds, ConnectionStatus.ACTIVE).stream()
                .filter(connection -> StringUtils.hasText(connection.getRelation()))
                .collect(Collectors.toMap(
                        connection -> new Pair(connection.getGuardianId(), connection.getWardId()),
                        Connection::getRelation,
                        (a, b) -> a));
    }

    /** 기간 하한(발송 시각 기준). */
    private OffsetDateTime lowerBound(AdminNotificationPeriod period) {
        return AdminNotificationPeriod.orDefault(period)
                .startFrom(OffsetDateTime.now(AdminNotificationPeriod.KST));
    }

    /**
     * 검색어 → 사용자 ID 목록(피보호자든 수신자든 이름이 맞으면). 이력 행에는 이름이 없어 먼저 바꿔 둔다.
     * 탈퇴한 회원은 이력도 함께 지워지므로 검색에서 빠지는 것이 맞다.
     */
    private KeywordScope resolveKeyword(String keyword) {
        String escaped = normalizeKeyword(keyword);
        if (escaped == null) {
            return new KeywordScope(false, NO_MATCH);
        }
        List<String> userIds = userRepository.findIdsByNameContaining(escaped);
        return new KeywordScope(true, userIds.isEmpty() ? NO_MATCH : userIds);
    }

    /**
     * LIKE 메타문자 이스케이프 + 소문자화. JPQL의 {@code escape '\'} 절과 짝을 이룬다
     * (회원·문의·이상감지 관리자 검색과 같은 규칙).
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
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /** 카테고리 적용 여부와 그 종류 목록. 미적용이어도 빈 IN 절을 피하려고 전 종류를 넣는다. */
    private record TypeScope(boolean applied, Collection<NotificationType> types) {
        static TypeScope of(AdminNotificationCategory category) {
            return category == null
                    ? new TypeScope(false, EnumSet.allOf(NotificationType.class))
                    : new TypeScope(true, category.types());
        }
    }

    private record KeywordScope(boolean applied, List<String> userIds) {
    }

    /** (보호자, 피보호자). 관계 라벨 조회 키. */
    private record Pair(String guardianId, String wardId) {
        Pair {
            Objects.requireNonNull(guardianId);
        }
    }
}
