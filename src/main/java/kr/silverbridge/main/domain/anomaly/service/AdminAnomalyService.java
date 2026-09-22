package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyFeedbackItem;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyPeriod;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalySummaryResponse;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyTypeFilter;
import kr.silverbridge.main.domain.anomaly.dto.DetectedTypeLabel;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncidentFeedback;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentFeedbackRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자 이상감지 로그 조회(보기 전용).
 *
 * <p>보호자 조회({@link GuardianAnomalyService})와 결정적으로 다른 점은 <b>연결 여부로 좁히지
 * 않는다</b>는 것이다. 운영 현황을 보려면 전체가 필요하다. 조회라서 감사 로그는 남기지 않는다.</p>
 *
 * <p><b>관리자는 판정하지 않는다</b>(2026-09-21 관리자 정정 폐지). 판정은 보호자 응답의 다수결로만
 * 정해지고, 동수(CONFLICTED)는 보호자들이 다시 응답해 푼다. 관리자용 판정·정정 API를 다시 만들지 말 것.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAnomalyService {

    private static final int MAX_PAGE_SIZE = 50;

    /** 기간 "전체"의 하한 - 서비스 이전 시각. */
    private static final OffsetDateTime NO_LOWER_BOUND = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    /** 검색 결과가 없는 쪽 IN 절에 넣는 값. ID·sessionId는 빈 문자열일 수 없다. */
    private static final List<String> NO_MATCH = List.of("");

    private final AnomalyIncidentRepository incidentRepository;
    private final AnomalyIncidentFeedbackRepository feedbackRepository;
    private final CameraService cameraService;
    private final UserRepository userRepository;

    /**
     * 이상감지 기록 목록(상황 최신순).
     *
     * @param status  판정 상태 필터. null이면 전체
     * @param wardId  특정 피보호자만 볼 때 지정. null·공백이면 전체
     * @param period  기간(첫 감지 시각 기준, KST). null이면 전체
     * @param type    감지 유형. null이면 전체
     * @param keyword 피보호자 이름·카메라 위치 부분일치. null·공백이면 무시
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminAnomalyIncidentItem> getIncidents(AnomalyReviewStatus status, String wardId,
                                                              AdminAnomalyPeriod period,
                                                              AdminAnomalyTypeFilter type, String keyword,
                                                              int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        KeywordScope scope = resolveKeyword(keyword);
        Page<AnomalyIncident> incidents = incidentRepository.searchForAdmin(
                status,
                StringUtils.hasText(wardId) ? wardId : null,
                lowerBound(period),
                type == null ? null : type.toDetectedType(),
                scope.applied(), scope.wardIds(), scope.sessionIds(),
                pageable);

        List<AnomalyIncident> content = incidents.getContent();

        // 빈 페이지(마지막 페이지 이후 요청 포함)에서는 조회를 돌리지 않는다.
        // 여기서 일찍 반환하지 않는 이유는 전체 건수·페이지 수 같은 페이징 정보를 그대로 살리기 위해서다.
        Map<String, String> cameraLabels = content.isEmpty() ? Map.of()
                : cameraService.findLabelsBySessionIds(
                        content.stream().map(AnomalyIncident::getSessionId).collect(Collectors.toSet()));

        // 응답 내역을 상황별로 한 번에 읽는다(상황마다 조회하면 페이지 크기만큼 쿼리가 늘어난다).
        Map<Long, List<AnomalyIncidentFeedback>> feedbacksByIncident = content.isEmpty() ? Map.of()
                : feedbackRepository.findByIncidentIdIn(content.stream().map(AnomalyIncident::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(AnomalyIncidentFeedback::getIncidentId));

        // 피보호자와 보호자 이름을 한 번의 조회로 모두 채운다.
        Set<String> userIds = content.stream().map(AnomalyIncident::getWardId)
                .collect(Collectors.toCollection(HashSet::new));
        feedbacksByIncident.values().forEach(list ->
                list.forEach(feedback -> userIds.add(feedback.getGuardianId())));
        Map<String, String> names = resolveNames(userIds);

        return PageResponse.of(incidents.map(incident -> AdminAnomalyIncidentItem.of(
                incident,
                names.get(incident.getWardId()),
                cameraLabels.get(incident.getSessionId()),
                toFeedbackItems(feedbacksByIncident.get(incident.getId()), names))));
    }

    /**
     * 이상감지 로그 화면 집계 - 유형 탭 건수 · 응답률 · 판정별 현황 · AI 신뢰도.
     *
     * <p>DB에서는 (유형, 판정 상태)별 건수·confidence 합계만 받고 나머지는 여기서 합산한다. 유형 탭 건수는 유형 필터를 무시하고,
     * 나머지는 고른 유형으로 좁힌다 - 탭을 골라도 탭 옆 숫자는 그대로여야 하기 때문이다.</p>
     *
     * @param period  기간(첫 감지 시각 기준, KST). null이면 전체
     * @param type    감지 유형. null이면 전체
     * @param keyword 피보호자 이름·카메라 위치 부분일치. null·공백이면 무시
     */
    @Transactional(readOnly = true)
    public AdminAnomalySummaryResponse getSummary(AdminAnomalyPeriod period, AdminAnomalyTypeFilter type,
                                                  String keyword) {
        KeywordScope scope = resolveKeyword(keyword);
        List<AnomalyIncidentRepository.TypeStatusCount> rows = incidentRepository.countForAdminSummary(
                lowerBound(period), scope.applied(), scope.wardIds(), scope.sessionIds());

        // 유형 탭: 유형 필터를 무시하고 집계된 유형만(0건 유형은 항목이 생기지 않는다)
        Map<DetectedType, Long> byType = new EnumMap<>(DetectedType.class);
        Map<AnomalyReviewStatus, Long> byReview = new EnumMap<>(AnomalyReviewStatus.class);
        Map<AnomalyReviewStatus, Double> confidenceSums = new EnumMap<>(AnomalyReviewStatus.class);
        DetectedType selected = type == null ? null : type.toDetectedType();
        for (AnomalyIncidentRepository.TypeStatusCount row : rows) {
            byType.merge(row.getDetectedType(), row.getTotal(), Long::sum);
            if (selected == null || selected == row.getDetectedType()) {
                byReview.merge(row.getReviewStatus(), row.getTotal(), Long::sum);
                confidenceSums.merge(row.getReviewStatus(), row.getConfidenceSum(), Double::sum);
            }
        }

        List<AdminAnomalySummaryResponse.TypeCount> typeCounts = byType.entrySet().stream()
                .map(entry -> new AdminAnomalySummaryResponse.TypeCount(
                        entry.getKey(), DetectedTypeLabel.of(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparingLong(AdminAnomalySummaryResponse.TypeCount::count).reversed())
                .toList();

        long pending = byReview.getOrDefault(AnomalyReviewStatus.PENDING, 0L);
        long real = byReview.getOrDefault(AnomalyReviewStatus.REAL, 0L);
        long falseAlarm = byReview.getOrDefault(AnomalyReviewStatus.FALSE_ALARM, 0L);
        long conflicted = byReview.getOrDefault(AnomalyReviewStatus.CONFLICTED, 0L);
        long total = pending + real + falseAlarm + conflicted;
        long judged = real + falseAlarm;
        double realSum = confidenceSums.getOrDefault(AnomalyReviewStatus.REAL, 0.0);
        double falseAlarmSum = confidenceSums.getOrDefault(AnomalyReviewStatus.FALSE_ALARM, 0.0);

        return new AdminAnomalySummaryResponse(
                AdminAnomalyPeriod.orDefault(period),
                total,
                typeCounts,
                new AdminAnomalySummaryResponse.ReviewCount(pending, real, falseAlarm, conflicted),
                ratio(total - pending, total),
                new AdminAnomalySummaryResponse.AiConfidence(
                        average(realSum + falseAlarmSum, judged), average(realSum, real),
                        average(falseAlarmSum, falseAlarm), judged));
    }

    /** 응답이 없는 상황은 null이 아니라 빈 목록으로 준다(프론트가 존재 여부를 분기하지 않게). */
    private List<AdminAnomalyFeedbackItem> toFeedbackItems(List<AnomalyIncidentFeedback> feedbacks,
                                                           Map<String, String> names) {
        if (feedbacks == null || feedbacks.isEmpty()) {
            return List.of();
        }
        return feedbacks.stream()
                .map(feedback -> AdminAnomalyFeedbackItem.of(feedback, names.get(feedback.getGuardianId())))
                .toList();
    }

    /**
     * 사용자 ID → 이름. 탈퇴한 사용자는 키 자체가 없어 이름이 null이 된다(이력은 남기고 이름만 비운다).
     *
     * <p>비어 있을 때 {@code Map.of()}가 아니라 {@link Collections#emptyMap()}을 쓰는 이유는
     * {@code get(null)}이 NPE를 던지지 않게 하기 위해서다(SOS 이력에서 실제로 500이 났던 지점).</p>
     */
    private Map<String, String> resolveNames(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));
    }

    /**
     * 기간 하한. "전체"는 null 대신 충분히 이른 시각을 쓴다 - null 시각 파라미터는 PostgreSQL에서
     * 타입 추론이 흔들린다. 서비스 이전 시각이라 어떤 상황도 빠지지 않는다.
     */
    private OffsetDateTime lowerBound(AdminAnomalyPeriod period) {
        OffsetDateTime from = AdminAnomalyPeriod.orDefault(period).startFrom(AnomalyReviewClock.now());
        return from != null ? from : NO_LOWER_BOUND;
    }

    /**
     * 검색어 → 피보호자 ID·카메라 sessionId 목록. 상황 행에는 이름·위치가 없어 먼저 바꿔 둔다.
     *
     * <p>한쪽 목록이 비면 매칭 불가능한 값 하나를 넣는다 - 빈 IN 절은 DB·드라이버마다 다르게 처리된다.
     * 탈퇴한 피보호자·삭제된 카메라는 이름·위치를 알 수 없어 검색되지 않는다.</p>
     */
    private KeywordScope resolveKeyword(String keyword) {
        String escaped = normalizeKeyword(keyword);
        if (escaped == null) {
            return new KeywordScope(false, NO_MATCH, NO_MATCH);
        }
        List<String> wardIds = userRepository.findIdsByNameContaining(escaped);
        List<String> sessionIds = cameraService.findSessionIdsByLabelKeyword(escaped);
        return new KeywordScope(true,
                wardIds.isEmpty() ? NO_MATCH : wardIds,
                sessionIds.isEmpty() ? NO_MATCH : sessionIds);
    }

    /**
     * LIKE 메타문자 이스케이프 + 소문자화. JPQL의 {@code escape '\'} 절과 짝을 이룬다
     * (회원·문의 관리자 검색과 같은 규칙).
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

    /** 비율(0.0~1.0, 소수 넷째 자리). 분모가 0이면 null - 모르는 값을 0으로 채우지 않는다. */
    private static Double ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return Math.round((double) numerator / denominator * 10_000) / 10_000.0;
    }

    /** 평균(0.0~1.0, 소수 넷째 자리). 건수가 0이면 null - 모르는 값을 0으로 채우지 않는다. */
    private static Double average(double sum, long count) {
        if (count == 0) {
            return null;
        }
        return Math.round(sum / count * 10_000) / 10_000.0;
    }

    /** 검색어 적용 여부와 그 결과로 좁힐 피보호자·카메라. */
    private record KeywordScope(boolean applied, List<String> wardIds, List<String> sessionIds) {
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
