package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.admin.service.AdminAuditLogService;
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
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
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
 * 관리자 이상감지 로그 조회 + 판정 정정.
 *
 * <p>보호자 조회({@link GuardianAnomalyService})와 결정적으로 다른 점은 <b>연결 여부로 좁히지
 * 않는다</b>는 것이다. 관리자는 전체를 봐야 엇갈린 판정을 찾아낼 수 있다. 대신 개인 이력을 여는
 * 경로이므로 정정은 감사 로그에 남긴다(집계 숫자만 보는 대시보드는 남기지 않는 것과 대비된다).</p>
 *
 * <p><b>관리자는 1차 판정을 하지 않는다.</b> 여기 있는 것은 보호자 응답을 보고 뒤집는 2차 정정뿐이며,
 * 관리자용 "오탐이다/아니다" 최초 판정 API를 만들어서는 안 된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAnomalyService {

    private static final int MAX_PAGE_SIZE = 50;

    private final AnomalyIncidentRepository incidentRepository;
    private final AnomalyIncidentFeedbackRepository feedbackRepository;
    private final AdminAuditLogService auditLogService;
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

    /**
     * 판정 정정. 보호자 응답이 엇갈렸거나 잘못 판정된 건을 관리자가 확정한다.
     *
     * <p>정정은 <b>상태만 바꾼다</b> - 보호자 응답 원본은 그대로 남는다. 무엇을 근거로 뒤집었는지
     * 확인할 수 없게 되면 정정 자체를 검증할 방법이 사라지기 때문이다.</p>
     *
     * <p><b>정정 알림을 보내지 않는다.</b> "아까 그건 아니었습니다"를 다시 푸시하면 알림이 두 배가 되고
     * 다음 진짜 경보의 신뢰만 깎인다(판정은 이미 나간 알림을 되돌리지 않는다는 규칙).</p>
     *
     * @throws CustomException {@code ANOMALY_INVALID_REVIEW_STATUS}(REAL·FALSE_ALARM 외 지정) /
     *                         {@code ANOMALY_INCIDENT_NOT_FOUND}(없는 상황)
     */
    @Transactional
    public AdminAnomalyIncidentItem resolve(String adminId, Long incidentId,
                                            AnomalyReviewStatus reviewStatus, String note) {
        if (reviewStatus != AnomalyReviewStatus.REAL && reviewStatus != AnomalyReviewStatus.FALSE_ALARM) {
            throw new CustomException(ErrorCode.ANOMALY_INVALID_REVIEW_STATUS);
        }

        AnomalyIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANOMALY_INCIDENT_NOT_FOUND));

        AnomalyReviewStatus before = incident.getReviewStatus();
        incident.resolveByAdmin(reviewStatus, adminId, note, AnomalyReviewClock.now());

        // 개인 이력을 뒤집는 조작이라 반드시 남긴다. 이미 정정한 건을 다시 정정하는 것도 허용하므로,
        // "누가 언제 무엇에서 무엇으로 바꿨는지"가 매번 쌓여야 되돌린 이력을 추적할 수 있다.
        auditLogService.log(adminId, AdminAuditAction.ANOMALY_REVIEW_RESOLVE, String.valueOf(incidentId),
                String.format("이상감지 판정 정정: %s → %s%s",
                        before, reviewStatus, StringUtils.hasText(note) ? " (" + note + ")" : ""));

        List<AnomalyIncidentFeedback> feedbacks = feedbackRepository.findByIncidentId(incidentId);
        Set<String> userIds = feedbacks.stream()
                .map(AnomalyIncidentFeedback::getGuardianId)
                .collect(Collectors.toCollection(HashSet::new));
        userIds.add(incident.getWardId());
        Map<String, String> names = resolveNames(userIds);

        return AdminAnomalyIncidentItem.of(
                incident,
                names.get(incident.getWardId()),
                cameraService.findLabelsBySessionIds(Set.of(incident.getSessionId()))
                        .get(incident.getSessionId()),
                toFeedbackItems(feedbacks, names));
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
