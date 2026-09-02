package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminOperationDashboardResponse;
import kr.silverbridge.main.domain.admin.dto.AdminSafetyDashboardResponse;
import kr.silverbridge.main.domain.anomaly.client.AiLiveStreamSubscriber;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementDraftRepository;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.inquiry.repository.InquiryRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.enums.InquiryStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 관리자 대시보드 집계.
 *
 * <p><b>탭마다 메서드가 따로다</b> - 한쪽 탭만 열어도 다른 쪽 쿼리가 돌지 않게 하기 위함이다.
 * 서버 캐시는 두지 않는다: 집계가 카운트 쿼리 수준이고, 캐시를 넣으면 "방금 처리했는데 안 줄어든다"가
 * 생겨 관리자가 화면을 믿지 못하게 된다.</p>
 *
 * <p>"오늘"은 언제나 KST다({@link AdminDashboardClock}).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDashboardService {

    /**
     * 연결 요청이 이만큼 방치되면 사각지대 신호로 본다(2026-09-02 결정).
     *
     * <p>일주일이면 보호자가 잊었거나 피보호자가 알림을 못 본 것으로 볼 만하다. 더 짧게 잡으면
     * 정상적으로 수락을 기다리는 중인 요청까지 경고로 잡혀 신호가 무뎌진다.</p>
     */
    private static final int STALE_PENDING_DAYS = 7;

    /** 가입 추이 구간(오늘 포함 최근 7일). */
    private static final int SIGNUP_TREND_DAYS = 7;

    private final AiLiveStreamSubscriber aiLiveStreamSubscriber;
    private final AnomalyIncidentRepository anomalyIncidentRepository;
    private final AnnouncementDraftRepository announcementDraftRepository;
    private final CameraRepository cameraRepository;
    private final ConnectionRepository connectionRepository;
    private final InquiryRepository inquiryRepository;
    private final UserRepository userRepository;

    /**
     * 안전 현황 탭.
     *
     * <p>AI가 끊겨 있어도 <b>500을 내지 않는다</b> - 나머지 집계는 정상이고, AI 상태는 그 자체가
     * 관리자가 봐야 할 지표다.</p>
     */
    public AdminSafetyDashboardResponse getSafetyDashboard() {
        OffsetDateTime now = AdminDashboardClock.now();

        boolean aiConnected = aiLiveStreamSubscriber.isConnected();
        long totalCameras = cameraRepository.count();

        // AI가 끊겨 있으면 구독 집합은 "카메라가 없다"가 아니라 "알 수 없다"이다.
        // 이때 0을 내려보내면 우리 수신기의 장애가 현장 카메라 전멸로 표시된다.
        Long streamingCameras = aiConnected ? (long) aiLiveStreamSubscriber.subscribedSessionCount() : null;
        Long disconnectedCameras = streamingCameras == null
                ? null
                : Math.max(0L, totalCameras - streamingCameras);

        var safetyEvents = new AdminSafetyDashboardResponse.SafetyEvents(
                disconnectedCameras,
                userRepository.countWardsWithoutActiveGuardian(Role.WARD, Status.ACTIVE, ConnectionStatus.ACTIVE),
                userRepository.countWardsWithoutCamera(Role.WARD, Status.ACTIVE),
                connectionRepository.countByStatusAndCreatedAtBefore(
                        ConnectionStatus.PENDING, now.minusDays(STALE_PENDING_DAYS)));

        return new AdminSafetyDashboardResponse(
                aiConnected,
                aiConnected ? aiLiveStreamSubscriber.subscribedSessionCount() : 0,
                totalCameras,
                streamingCameras,
                safetyEvents,
                todayAnomaly(AdminDashboardClock.startOfDay(now)));
    }

    /** 운영 현황 탭. */
    public AdminOperationDashboardResponse getOperationDashboard() {
        OffsetDateTime now = AdminDashboardClock.now();
        OffsetDateTime todayStart = AdminDashboardClock.startOfDay(now);

        long pendingConnections = connectionRepository.countByStatus(ConnectionStatus.PENDING);
        long todayInquiries = inquiryRepository.countByCreatedAtGreaterThanEqual(todayStart);

        return new AdminOperationDashboardResponse(
                userRepository.countByStatusAndRoleNot(Status.ACTIVE, Role.ADMIN),
                userRepository.countByStatusAndRoleNotAndCreatedAtGreaterThanEqual(
                        Status.ACTIVE, Role.ADMIN, todayStart),
                new AdminOperationDashboardResponse.MemberComposition(
                        userRepository.countByStatusAndRole(Status.ACTIVE, Role.WARD),
                        userRepository.countByStatusAndRole(Status.ACTIVE, Role.GUARDIAN)),
                new AdminOperationDashboardResponse.Cameras(
                        cameraRepository.count(),
                        cameraRepository.countDistinctWards()),
                pendingConnections,
                unansweredInquiries(now),
                signupTrend(now),
                new AdminOperationDashboardResponse.PendingItems(
                        pendingConnections,
                        todayInquiries,
                        announcementDraftRepository.count()));
    }

    /**
     * 오늘(KST) 이상감지 집계. 단위는 <b>상황(incident)</b>이지 감지 건수가 아니다 - 같은 화재로 이력이
     * 3건 쌓여도 관리자가 봐야 할 것은 "상황 1건"이다.
     */
    private AdminSafetyDashboardResponse.TodayAnomaly todayAnomaly(OffsetDateTime todayStart) {
        List<AnomalyIncident> incidents = anomalyIncidentRepository.findByStartedAtGreaterThanEqual(todayStart);

        // 유형별: 실제로 집계된 것만 담는다. 0건인 유형(낙상·흉기 등)은 항목 자체를 만들지 않는다 -
        // "낙상 0건"은 안전하다는 뜻이 아니라 모델이 없다는 뜻이라, 숫자로 보여주면 오독된다.
        Map<DetectedType, Long> byType = new LinkedHashMap<>();
        Map<AnomalyReviewStatus, Long> byReview = new LinkedHashMap<>();
        for (AnomalyIncident incident : incidents) {
            byType.merge(incident.getDetectedType(), 1L, Long::sum);
            byReview.merge(incident.getReviewStatus(), 1L, Long::sum);
        }

        List<AdminSafetyDashboardResponse.TypeCount> typeCounts = byType.entrySet().stream()
                .map(e -> new AdminSafetyDashboardResponse.TypeCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(AdminSafetyDashboardResponse.TypeCount::count).reversed())
                .toList();

        return new AdminSafetyDashboardResponse.TodayAnomaly(
                incidents.size(),
                typeCounts,
                new AdminSafetyDashboardResponse.ReviewCount(
                        byReview.getOrDefault(AnomalyReviewStatus.PENDING, 0L),
                        byReview.getOrDefault(AnomalyReviewStatus.REAL, 0L),
                        byReview.getOrDefault(AnomalyReviewStatus.FALSE_ALARM, 0L),
                        byReview.getOrDefault(AnomalyReviewStatus.CONFLICTED, 0L)));
    }

    /**
     * 미답변 문의. 대기 건이 없으면 대기 시간은 <b>0이 아니라 null</b>이다 - 0으로 채우면
     * "방금 들어온 문의가 있다"와 구분되지 않는다.
     */
    private AdminOperationDashboardResponse.UnansweredInquiries unansweredInquiries(OffsetDateTime now) {
        long count = inquiryRepository.countByStatus(InquiryStatus.WAITING);
        Optional<OffsetDateTime> oldest = inquiryRepository.findOldestCreatedAtByStatus(InquiryStatus.WAITING);

        Long longestWaitingHours = oldest
                .map(created -> Math.max(0L, Duration.between(created, now).toHours()))
                .orElse(null);

        return new AdminOperationDashboardResponse.UnansweredInquiries(count, longestWaitingHours);
    }

    /**
     * 최근 7일 가입 추이(KST). <b>가입이 0건인 날도 항목을 만든다</b> - 빠뜨리면 프론트 차트에 구멍이
     * 생기고, 조용한 날과 데이터가 없는 날을 구분할 수 없다.
     */
    private List<AdminOperationDashboardResponse.SignupPoint> signupTrend(OffsetDateTime now) {
        LocalDate today = AdminDashboardClock.toDate(now);
        LocalDate from = today.minusDays(SIGNUP_TREND_DAYS - 1L);

        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (OffsetDateTime createdAt : userRepository.findCreatedAtSince(
                Status.ACTIVE, Role.ADMIN, AdminDashboardClock.startOfDay(from))) {
            counts.merge(AdminDashboardClock.toDate(createdAt), 1L, Long::sum);
        }

        List<AdminOperationDashboardResponse.SignupPoint> trend = new ArrayList<>(SIGNUP_TREND_DAYS);
        for (int i = 0; i < SIGNUP_TREND_DAYS; i++) {
            LocalDate date = from.plusDays(i);
            trend.add(new AdminOperationDashboardResponse.SignupPoint(date, counts.getOrDefault(date, 0L)));
        }
        return trend;
    }
}
