package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyFeedbackItem;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncidentFeedback;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentFeedbackRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
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

    private final AnomalyIncidentRepository incidentRepository;
    private final AnomalyIncidentFeedbackRepository feedbackRepository;
    private final CameraService cameraService;
    private final UserRepository userRepository;

    /**
     * 이상감지 기록 목록(상황 최신순).
     *
     * @param status 판정 상태 필터. null이면 전체
     * @param wardId 특정 피보호자만 볼 때 지정. null·공백이면 전체
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminAnomalyIncidentItem> getIncidents(AnomalyReviewStatus status, String wardId,
                                                              int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        Page<AnomalyIncident> incidents = incidentRepository.searchForAdmin(
                status, StringUtils.hasText(wardId) ? wardId : null, pageable);

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

    private int normalizeSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
