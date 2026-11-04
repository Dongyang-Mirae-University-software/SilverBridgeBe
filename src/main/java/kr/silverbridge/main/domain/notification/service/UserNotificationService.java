package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.admin.support.AdminPaging;
import kr.silverbridge.main.domain.notification.config.NotificationLogProperties;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.dto.UserNotificationCategory;
import kr.silverbridge.main.domain.notification.dto.UserNotificationItem;
import kr.silverbridge.main.domain.notification.dto.UserNotificationReadAllResponse;
import kr.silverbridge.main.domain.notification.dto.UserNotificationUnreadCountResponse;
import kr.silverbridge.main.domain.notification.entity.NotificationLog;
import kr.silverbridge.main.domain.notification.repository.NotificationLogRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 내 알림 기록(종 아이콘) - 보호자·피보호자가 그동안 받은 알림을 다시 보고 읽음 처리한다 (2026-11-04).
 *
 * <p><b>보이는 것</b>: 최근 {@code notification.log.retention-days}(90일) 동안 <b>실제로 보낸</b> 알림
 * ({@code DELIVERED}·{@code SMS_FALLBACK}·{@code FAILED}). {@code NOT_SENT}(채널을 꺼 둠·정지 차단)는 보내지 않은
 * 알림이라 제외한다. {@code FAILED}는 기기에 닿지 않았을 수 있는 알림이라 앱 안에서라도 보여 준다.</p>
 *
 * <p><b>연결이 끝난 피보호자의 알림도 보인다</b> - 보호자 이력의 "지금 연결 이후만" 규칙(D-1)은 피보호자 기록을 열람할
 * 권한이고, 이 목록은 내 휴대폰에 이미 도착한 메시지의 사본이다. 피보호자가 탈퇴하면 FK CASCADE로 행이 함께 지워진다.</p>
 *
 * <p>사용자 ID는 토큰에서만 받는다. 로그에 제목·본문을 남기지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNotificationService {

    private final NotificationLogRepository notificationLogRepository;
    private final UserRepository userRepository;
    private final NotificationLogProperties logProperties;

    @Transactional(readOnly = true)
    public PageResponse<UserNotificationItem> getMyNotifications(String userId, UserNotificationCategory category,
                                                                 int page, int size) {
        Page<NotificationLog> logs = notificationLogRepository.searchForRecipient(
                userId, lowerBound(), category != null,
                // 빈 IN 절을 피하려고 묶음이 없을 때도 전 종류를 넘긴다(관리자 조회와 같은 방식).
                category == null ? EnumSet.allOf(NotificationType.class) : category.types(),
                AdminPaging.of(page, size));

        // 피보호자 이름은 한 번에 읽는다(행마다 조회하면 페이지 크기만큼 쿼리가 늘어난다). 본인에 관한 알림은 이름이 필요 없다.
        Set<String> wardIds = logs.getContent().stream()
                .map(NotificationLog::getWardId)
                .filter(wardId -> wardId != null && !wardId.equals(userId))
                .collect(Collectors.toSet());
        Map<String, String> names = wardIds.isEmpty() ? Map.of()
                : userRepository.findAllById(wardIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));

        return PageResponse.of(logs.map(log -> UserNotificationItem.of(log, names.get(log.getWardId()))));
    }

    @Transactional(readOnly = true)
    public UserNotificationUnreadCountResponse countUnread(String userId) {
        return new UserNotificationUnreadCountResponse(notificationLogRepository.countUnread(userId, lowerBound()));
    }

    /**
     * 한 건 읽음(멱등). 남의 알림은 403 + {@code [IDOR-ATTEMPT]}, 없는 알림·보내지 않은 알림은 404.
     */
    public void markRead(String userId, Long notificationId) {
        if (notificationLogRepository.markRead(notificationId, userId, OffsetDateTime.now()) > 0) {
            return;
        }
        Optional<String> owner = notificationLogRepository.findRecipientIdById(notificationId);
        if (owner.isPresent() && !owner.get().equals(userId)) {
            log.warn("[IDOR-ATTEMPT] 다른 사용자의 알림 읽음 시도: userId={}, notificationId={}", userId, notificationId);
            throw new CustomException(ErrorCode.NOTIFICATION_NOT_OWNED);
        }
        // 행이 없거나, 본인 것이지만 보내지 않은(NOT_SENT) 알림 - 목록에 없는 알림이라 없는 것과 같다.
        throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    public UserNotificationReadAllResponse markAllRead(String userId) {
        return new UserNotificationReadAllResponse(notificationLogRepository.markAllRead(userId, OffsetDateTime.now()));
    }

    /** 보관 정리(매일 04:30)가 돌기 전까지 남아 있는 기간 밖 행을 목록·안 읽은 수에서 뺀다. */
    private OffsetDateTime lowerBound() {
        return OffsetDateTime.now().minusDays(logProperties.getRetentionDays());
    }
}
