package kr.silverbridge.main.domain.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.admin.dto.AdminDashboardSummaryResponse;
import kr.silverbridge.main.domain.admin.dto.AdminPendingItemsResponse;
import kr.silverbridge.main.domain.admin.dto.AdminRecentUserResponse;
import kr.silverbridge.main.domain.admin.repository.AdminUserStatsRepository;
import kr.silverbridge.main.domain.admin.repository.AdminUserStatsRepository.UserStatsProjection;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementDraftRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository.AnomalyStatsProjection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 관리자 대시보드용 통계·요약 서비스 (읽기 전용)
 * - summary 는 60초 Redis 캐시 적용
 * - 전월/전일 비교 수치는 단일 native 쿼리에서 집계
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long SUMMARY_CACHE_TTL_SECONDS = 60L;

    private final UserRepository userRepository;
    private final AdminUserStatsRepository adminUserStatsRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final ConnectionRepository connectionRepository;
    private final AnnouncementDraftRepository announcementDraftRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // 대시보드 통계 요약 (60초 Redis 캐시)
    // 캐시 hit 경로에는 트랜잭션이 필요 없으므로, 메서드 레벨 @Transactional 을 두지 않는다.
    // miss 경로의 각 카운트 쿼리는 Spring Data JPA 가 자체 트랜잭션을 부여한다.
    public AdminDashboardSummaryResponse getSummary() {
        String cached = redisTemplate.opsForValue().get(RedisKeys.ADMIN_DASHBOARD_SUMMARY);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, AdminDashboardSummaryResponse.class);
            } catch (JsonProcessingException e) {
                // 캐시 포맷 변경/잔류 데이터 추적용 — DB 재계산으로 진행
                log.warn("[AdminDashboard] summary 캐시 역직렬화 실패: {}", e.getMessage());
            }
        }

        AdminDashboardSummaryResponse fresh = computeSummary();
        try {
            redisTemplate.opsForValue().set(
                    RedisKeys.ADMIN_DASHBOARD_SUMMARY,
                    objectMapper.writeValueAsString(fresh),
                    SUMMARY_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            // 캐시 쓰기 실패는 부수효과 — fresh 응답은 정상 반환
            log.warn("[AdminDashboard] summary 캐시 직렬화 실패: {}", e.getMessage());
        }
        return fresh;
    }

    // 최근 가입 회원 (ADMIN 제외, 가입 일시 내림차순)
    // 정렬은 메서드명 OrderByCreatedAtDesc 가 결정 — PageRequest 는 page/size 만 지정
    @Transactional(readOnly = true)
    public List<AdminRecentUserResponse> getRecentUsers(int limit) {
        return userRepository.findByRoleNotOrderByCreatedAtDesc(Role.ADMIN, PageRequest.of(0, limit))
                .stream()
                .map(AdminRecentUserResponse::from)
                .toList();
    }

    // 처리 대기 현황 (PENDING 연결 + 임시저장 + 미확인 문의)
    @Transactional(readOnly = true)
    public AdminPendingItemsResponse getPendingItems() {
        long pendingConnections = connectionRepository.countByStatus(ConnectionStatus.PENDING);
        long announcementDrafts = announcementDraftRepository.count();
        long unreadInquiries = 0L; // 문의 기능 추가 전 자리만 확보

        return new AdminPendingItemsResponse(pendingConnections, announcementDrafts, unreadInquiries);
    }

    // ─────────────────────────────────────────────────────────

    private AdminDashboardSummaryResponse computeSummary() {
        OffsetDateTime now = OffsetDateTime.now(KST);
        OffsetDateTime baseline = now.minusMonths(1);
        OffsetDateTime todayStart = LocalDate.now(KST).atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime tomorrowStart = todayStart.plusDays(1);
        OffsetDateTime yesterdayStart = todayStart.minusDays(1);

        UserStatsProjection userStats = adminUserStatsRepository.countUserStats(baseline);
        AnomalyStatsProjection anomalyStats = anomalyEventRepository.countAnomalyStats(
                todayStart, tomorrowStart, yesterdayStart);

        long anomalyChange = anomalyStats.getTodayCount() - anomalyStats.getYesterdayCount();

        return new AdminDashboardSummaryResponse(
                userStats.getCurrentTotal(),
                changeRatePct(userStats.getCurrentTotal(), userStats.getBaselineTotal()),
                userStats.getCurrentActiveWard(),
                changeRatePct(userStats.getCurrentActiveWard(), userStats.getBaselineActiveWard()),
                anomalyStats.getTodayCount(),
                anomalyChange,
                anomalyStats.getTotalCount(),
                anomalyStats.getTodayCount()
        );
    }

    // 증감률(%) 계산. baseline 이 0 이면 null 반환 (정의 불가)
    private Double changeRatePct(long current, long baseline) {
        if (baseline == 0L) return null;
        return BigDecimal.valueOf(current - baseline)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(baseline), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
