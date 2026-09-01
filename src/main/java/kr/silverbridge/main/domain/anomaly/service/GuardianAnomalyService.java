package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalyFeedbackResponse;
import kr.silverbridge.main.domain.anomaly.dto.AnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncidentFeedback;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentFeedbackRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 보호자용 이상감지 이력 조회 + 오탐 응답.
 *
 * <p><b>왜 보호자가 판정하는가</b>: AI는 "얼마나 불꽃처럼 보이는가"(confidence)까지만 답할 수 있고
 * "실제로 불이 났는가"는 현장을 아는 사람만 안다. 그래서 1차 판정 주체는 보호자뿐이며,
 * <b>피보호자 본인·관리자용 1차 판정 API는 만들지 않는다</b>(관리자는 엇갈린 건을 정정만 한다).</p>
 *
 * <p><b>인가 원칙</b>은 SOS 이력·복약과 같다 - <b>요청 시점 ACTIVE 연결</b>인 피보호자의 기록만 보이고,
 * 연결이 해제되면 과거 기록도 즉시 비공개다. 목록 인가는 {@code getActiveWardIds}, 단건 인가는
 * {@code isActiveConnection}만 쓴다({@code getMyWards}는 PENDING이 섞여 수락 전 피보호자의 기록이 샌다).
 * 위반은 403 + {@code [IDOR-ATTEMPT]} WARN.</p>
 *
 * <p><b>판정은 이미 나간 알림을 되돌리지 않는다</b> - 오탐으로 표시해도 정정 알림을 발송하지 않는다.
 * "아까 그건 아니었습니다"를 다시 푸시하면 알림이 두 배가 되고, 정작 다음 진짜 경보의 신뢰만 깎인다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianAnomalyService {

    /** 페이지 크기 상한 - 과대 요청으로 전체 이력을 한 번에 끌어가는 것을 막는다(SOS 이력과 동일). */
    private static final int MAX_PAGE_SIZE = 50;

    private final AnomalyIncidentRepository anomalyIncidentRepository;
    private final AnomalyIncidentFeedbackRepository feedbackRepository;
    private final ConnectionService connectionService;
    private final CameraService cameraService;
    private final UserRepository userRepository;

    /**
     * 이상감지 이력 조회(상황 최신순).
     *
     * @param wardId 특정 피보호자만 볼 때 지정. {@code null}·공백이면 ACTIVE 연결된 피보호자 전원
     * @throws CustomException {@code ANOMALY_NOT_AUTHORIZED} - wardId를 지정했으나 ACTIVE 연결이 아닐 때
     */
    @Transactional(readOnly = true)
    public PageResponse<AnomalyIncidentItem> getHistory(String guardianId, String wardId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        List<String> wardIds = resolveVisibleWardIds(guardianId, wardId);
        if (wardIds.isEmpty()) {
            return PageResponse.of(Page.empty(pageable));
        }

        Page<AnomalyIncident> incidents = anomalyIncidentRepository.findByWardIdInOrderByStartedAtDesc(wardIds, pageable);
        List<AnomalyIncident> content = incidents.getContent();

        Map<String, String> wardNames = resolveWardNames(content);
        Map<String, String> cameraLabels = cameraService.findLabelsBySessionIds(
                content.stream().map(AnomalyIncident::getSessionId).collect(Collectors.toSet()));
        Map<Long, AnomalyVerdict> myVerdicts = resolveMyVerdicts(guardianId, content);

        return PageResponse.of(incidents.map(incident -> AnomalyIncidentItem.of(
                incident,
                wardNames.get(incident.getWardId()),
                cameraLabels.get(incident.getSessionId()),
                myVerdicts.get(incident.getId()))));
    }

    /**
     * 오탐 응답. 같은 상황에 다시 호출하면 <b>번복</b>(1인 1표)이며, 그때마다 응답 전체를 다시 집계해
     * 상황의 판정 상태를 재계산한다.
     *
     * @throws CustomException {@code ANOMALY_INCIDENT_NOT_FOUND}(없는 상황) /
     *                         {@code ANOMALY_NOT_AUTHORIZED}(연결되지 않은 피보호자) /
     *                         {@code ANOMALY_ALREADY_RESOLVED}(관리자 확정 건)
     */
    @Transactional
    public AnomalyFeedbackResponse submitFeedback(String guardianId, Long incidentId, AnomalyVerdict verdict) {
        AnomalyIncident incident = anomalyIncidentRepository.findById(incidentId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANOMALY_INCIDENT_NOT_FOUND));

        if (!connectionService.isActiveConnection(guardianId, incident.getWardId())) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 이상감지 응답 시도: guardianId={}, incidentId={}",
                    guardianId, incidentId);
            throw new CustomException(ErrorCode.ANOMALY_NOT_AUTHORIZED);
        }

        // 관리자가 확인을 마친 건은 보호자 응답으로 뒤집지 않는다. 조용히 무시하지 않고 이유를 알린다.
        if (incident.isAdminResolved()) {
            throw new CustomException(ErrorCode.ANOMALY_ALREADY_RESOLVED);
        }

        List<AnomalyIncidentFeedback> feedbacks = feedbackRepository.findByIncidentId(incidentId);
        upsertMyFeedback(feedbacks, incidentId, guardianId, verdict);

        // 저장 반영 순서에 기대지 않도록, 재계산은 "다른 보호자들의 응답 + 내 새 응답"을 메모리에서 조립해 판정한다.
        List<AnomalyVerdict> verdicts = new ArrayList<>(feedbacks.stream()
                .filter(feedback -> !feedback.getGuardianId().equals(guardianId))
                .map(AnomalyIncidentFeedback::getVerdict)
                .toList());
        verdicts.add(verdict);

        AnomalyReviewStatus status = calculateStatus(verdicts);
        incident.applyReviewStatus(status);

        log.info("[ANOMALY] 보호자 오탐 응답: incidentId={}, guardianId={}, verdict={}, reviewStatus={}",
                incidentId, guardianId, verdict, status);

        return new AnomalyFeedbackResponse(incidentId, status, verdict);
    }

    /**
     * 보호자 응답들로 상황의 판정 상태를 정한다. 시각·저장소에 의존하지 않는 순수 함수다.
     *
     * <p><b>다수결이 아니다.</b> 한 명은 실제 화재로, 다른 한 명은 요리 연기로 봤다면 그 불일치 자체가
     * 관리자가 확인해야 할 정보다. 서버가 표를 세어 한쪽으로 정하면 그 정보가 사라진다.</p>
     */
    static AnomalyReviewStatus calculateStatus(Collection<AnomalyVerdict> verdicts) {
        if (verdicts.isEmpty()) {
            return AnomalyReviewStatus.PENDING;
        }
        Set<AnomalyVerdict> distinct = Set.copyOf(verdicts);
        if (distinct.size() > 1) {
            return AnomalyReviewStatus.CONFLICTED;
        }
        return distinct.contains(AnomalyVerdict.REAL)
                ? AnomalyReviewStatus.REAL
                : AnomalyReviewStatus.FALSE_ALARM;
    }

    /** 이미 응답한 적이 있으면 갱신(번복), 없으면 새로 저장한다. */
    private void upsertMyFeedback(List<AnomalyIncidentFeedback> feedbacks, Long incidentId,
                                  String guardianId, AnomalyVerdict verdict) {
        feedbacks.stream()
                .filter(feedback -> feedback.getGuardianId().equals(guardianId))
                .findFirst()
                .ifPresentOrElse(
                        mine -> mine.changeVerdict(verdict),
                        () -> feedbackRepository.save(AnomalyIncidentFeedback.builder()
                                .incidentId(incidentId)
                                .guardianId(guardianId)
                                .verdict(verdict)
                                .build()));
    }

    /** 조회 대상 피보호자 ID 목록을 인가 검증과 함께 결정한다. 이 메서드를 통과한 목록만 쿼리에 넘긴다. */
    private List<String> resolveVisibleWardIds(String guardianId, String wardId) {
        if (!StringUtils.hasText(wardId)) {
            return connectionService.getActiveWardIds(guardianId);
        }
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 이상감지 이력 조회 시도: guardianId={}, wardId={}",
                    guardianId, wardId);
            throw new CustomException(ErrorCode.ANOMALY_NOT_AUTHORIZED);
        }
        return List.of(wardId);
    }

    /** 목록에 등장하는 피보호자 이름을 한 번에 조회한다(건별 조회로 인한 N+1 회피). */
    private Map<String, String> resolveWardNames(List<AnomalyIncident> incidents) {
        Set<String> wardIds = incidents.stream()
                .map(AnomalyIncident::getWardId)
                .collect(Collectors.toSet());
        if (wardIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(wardIds).stream()
                .filter(user -> StringUtils.hasText(user.getName()))
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    /** "내가 이미 응답했는지"를 목록에 붙이기 위한 조회. 응답이 없는 상황은 맵에 없어 null이 된다. */
    private Map<Long, AnomalyVerdict> resolveMyVerdicts(String guardianId, List<AnomalyIncident> incidents) {
        Set<Long> incidentIds = incidents.stream()
                .map(AnomalyIncident::getId)
                .collect(Collectors.toSet());
        if (incidentIds.isEmpty()) {
            return Map.of();
        }
        return feedbackRepository.findByGuardianIdAndIncidentIdIn(guardianId, incidentIds).stream()
                .collect(Collectors.toMap(AnomalyIncidentFeedback::getIncidentId,
                        AnomalyIncidentFeedback::getVerdict, (first, second) -> first));
    }

    private int normalizeSize(int size) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
