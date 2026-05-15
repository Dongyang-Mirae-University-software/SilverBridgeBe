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
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.util.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDashboardServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AdminUserStatsRepository adminUserStatsRepository;
    @Mock private AnomalyEventRepository anomalyEventRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private AnnouncementDraftRepository announcementDraftRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private AdminDashboardService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ─── getSummary ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSummary 캐시 hit → 캐시 값 반환, repository 호출 안 함")
    void getSummary_캐시hit_캐시값반환() throws Exception {
        String cached = "{\"totalUsers\":10}";
        AdminDashboardSummaryResponse fromCache = new AdminDashboardSummaryResponse(
                10L, 5.0, 5L, 3.0, 1L, 0L, 100L, 1L);

        when(valueOperations.get(RedisKeys.ADMIN_DASHBOARD_SUMMARY)).thenReturn(cached);
        when(objectMapper.readValue(cached, AdminDashboardSummaryResponse.class)).thenReturn(fromCache);

        AdminDashboardSummaryResponse result = service.getSummary();

        assertThat(result).isEqualTo(fromCache);
        verify(adminUserStatsRepository, never()).countUserStats(any());
        verify(anomalyEventRepository, never()).countAnomalyStats(any(), any(), any());
    }

    @Test
    @DisplayName("getSummary 캐시 miss → DB 계산 후 캐시 저장")
    void getSummary_캐시miss_DB계산_및_캐시저장() throws Exception {
        when(valueOperations.get(RedisKeys.ADMIN_DASHBOARD_SUMMARY)).thenReturn(null);
        when(adminUserStatsRepository.countUserStats(any()))
                .thenReturn(userStats(100L, 80L, 50L, 40L));
        when(anomalyEventRepository.countAnomalyStats(any(), any(), any()))
                .thenReturn(anomalyStats(5L, 3L, 100L));
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"totalUsers\":100}");

        AdminDashboardSummaryResponse result = service.getSummary();

        assertThat(result.totalUsers()).isEqualTo(100L);
        verify(valueOperations).set(eq(RedisKeys.ADMIN_DASHBOARD_SUMMARY), anyString(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("getSummary 캐시 역직렬화 실패 → DB 재계산, 정상 응답")
    void getSummary_캐시손상_DB재계산() throws Exception {
        String corrupted = "{not-json}";
        when(valueOperations.get(RedisKeys.ADMIN_DASHBOARD_SUMMARY)).thenReturn(corrupted);
        when(objectMapper.readValue(eq(corrupted), eq(AdminDashboardSummaryResponse.class)))
                .thenThrow(new JsonProcessingException("corrupted") {});
        when(adminUserStatsRepository.countUserStats(any()))
                .thenReturn(userStats(50L, 40L, 25L, 20L));
        when(anomalyEventRepository.countAnomalyStats(any(), any(), any()))
                .thenReturn(anomalyStats(2L, 1L, 30L));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        AdminDashboardSummaryResponse result = service.getSummary();

        assertThat(result).isNotNull();
        assertThat(result.totalUsers()).isEqualTo(50L);
    }

    @Test
    @DisplayName("getSummary 캐시 쓰기 실패 → fresh 값 그대로 반환 (예외 안 던짐)")
    void getSummary_캐시쓰기실패_fresh반환() throws Exception {
        when(valueOperations.get(RedisKeys.ADMIN_DASHBOARD_SUMMARY)).thenReturn(null);
        when(adminUserStatsRepository.countUserStats(any()))
                .thenReturn(userStats(20L, 10L, 5L, 4L));
        when(anomalyEventRepository.countAnomalyStats(any(), any(), any()))
                .thenReturn(anomalyStats(1L, 1L, 10L));
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("write fail") {});

        AdminDashboardSummaryResponse result = service.getSummary();

        assertThat(result).isNotNull();
        assertThat(result.totalUsers()).isEqualTo(20L);
    }

    @Test
    @DisplayName("getSummary baseline 0 → 증감률 null")
    void getSummary_baseline0_증감률null() throws Exception {
        when(valueOperations.get(RedisKeys.ADMIN_DASHBOARD_SUMMARY)).thenReturn(null);
        when(adminUserStatsRepository.countUserStats(any()))
                .thenReturn(userStats(100L, 0L, 50L, 0L));
        when(anomalyEventRepository.countAnomalyStats(any(), any(), any()))
                .thenReturn(anomalyStats(5L, 3L, 100L));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        AdminDashboardSummaryResponse result = service.getSummary();

        assertThat(result.userChangeRatePct()).isNull();
        assertThat(result.wardChangeRatePct()).isNull();
    }

    // ─── getRecentUsers ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getRecentUsers → ADMIN 제외 페이지 조회")
    void getRecentUsers_ADMIN제외() {
        User u1 = userFixture("aaaaaa", "user1@x.com", Role.WARD);
        User u2 = userFixture("bbbbbb", "user2@x.com", Role.GUARDIAN);
        when(userRepository.findByRoleNotOrderByCreatedAtDesc(eq(Role.ADMIN), any(Pageable.class)))
                .thenReturn(List.of(u1, u2));

        List<AdminRecentUserResponse> result = service.getRecentUsers(5);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("aaaaaa");
        verify(userRepository).findByRoleNotOrderByCreatedAtDesc(eq(Role.ADMIN), any(Pageable.class));
    }

    // ─── getPendingItems ────────────────────────────────────────────────────

    @Test
    @DisplayName("getPendingItems → PENDING 연결 + 임시저장 + 0 합산")
    void getPendingItems_합산() {
        when(connectionRepository.countByStatus(ConnectionStatus.PENDING)).thenReturn(7L);
        when(announcementDraftRepository.count()).thenReturn(3L);

        AdminPendingItemsResponse result = service.getPendingItems();

        assertThat(result.pendingConnections()).isEqualTo(7L);
        assertThat(result.announcementDrafts()).isEqualTo(3L);
        assertThat(result.unreadInquiries()).isEqualTo(0L);
    }

    // ─── 헬퍼 ──────────────────────────────────────────────────────────────

    private UserStatsProjection userStats(long currentTotal, long baselineTotal,
                                          long currentActiveWard, long baselineActiveWard) {
        return new UserStatsProjection() {
            @Override public long getCurrentTotal()       { return currentTotal;       }
            @Override public long getBaselineTotal()      { return baselineTotal;      }
            @Override public long getCurrentActiveWard()  { return currentActiveWard;  }
            @Override public long getBaselineActiveWard() { return baselineActiveWard; }
        };
    }

    private AnomalyStatsProjection anomalyStats(long today, long yesterday, long total) {
        return new AnomalyStatsProjection() {
            @Override public long getTodayCount()     { return today;     }
            @Override public long getYesterdayCount() { return yesterday; }
            @Override public long getTotalCount()     { return total;     }
        };
    }

    private User userFixture(String id, String email, Role role) {
        return User.builder()
                .id(id)
                .email(email)
                .name("테스트")
                .role(role)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .build();
    }
}
