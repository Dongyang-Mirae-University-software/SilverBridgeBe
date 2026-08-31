package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.global.enums.DetectedType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 이상감지 이력을 "상황(incident)" 단위로 묶는다.
 *
 * <p>같은 카메라에서 화재가 이어지면 이력은 쿨다운(1분) 간격으로 계속 쌓인다. 판정을 이력 단위로 두면
 * 보호자가 같은 불을 열 번 판정해야 하므로, 연속된 감지를 한 상황으로 묶어 <b>판정·통계의 단위</b>로 삼는다.</p>
 *
 * <p><b>묶는 기준</b>은 같은 {@code (wardId, sessionId, detectedType)}이고 직전 감지로부터
 * {@code anomaly.incident-merge-minutes}(기본 10분) 이내일 때다. 다른 카메라·다른 유형은 섞지 않는다 -
 * 거실 화재와 주방 연기는 대응이 다른 별개 상황이다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyIncidentService {

    /** 상황의 날짜 소속 판정 기준. 서버·DB 타임존과 무관하게 KST다(복약 MedicationClock과 같은 이유). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AnomalyIncidentRepository anomalyIncidentRepository;
    private final AnomalyProperties properties;

    /**
     * 감지 1건이 속할 상황을 찾거나 새로 연다.
     *
     * <p>승계된 경우 감지 횟수·최고 신뢰도·마지막 감지 시각이 갱신된다(영속 상태라 변경 감지로 반영된다).
     * 판정 상태는 건드리지 않는다.</p>
     *
     * @param detectedAt <b>수신 시각</b>. AI {@code analyzedAt}은 fallback 페이로드에서 null일 수 있어
     *                   묶음 기준으로 쓸 수 없다(이력의 {@code detected_at}과 역할이 다르다).
     */
    public AnomalyIncident resolveIncident(String wardId, String sessionId, DetectedType detectedType,
                                           OffsetDateTime detectedAt, double confidence) {
        long mergeMinutes = properties.getIncidentMergeMinutes();

        return anomalyIncidentRepository
                .findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(wardId, sessionId, detectedType)
                .filter(previous -> isContinuation(previous.getLastDetectedAt(), detectedAt, mergeMinutes))
                .map(previous -> {
                    previous.addDetection(detectedAt, confidence);
                    log.debug("[ANOMALY] 기존 상황에 승계: incidentId={}, eventCount={}",
                            previous.getId(), previous.getEventCount());
                    return previous;
                })
                .orElseGet(() -> {
                    AnomalyIncident opened = anomalyIncidentRepository.save(AnomalyIncident.builder()
                            .wardId(wardId)
                            .sessionId(sessionId)
                            .detectedType(detectedType)
                            .detectedAt(detectedAt)
                            .confidence(confidence)
                            .build());
                    log.info("[ANOMALY] 새 상황 시작: incidentId={}, wardId={}, sessionId={}, detectedType={}",
                            opened.getId(), wardId, sessionId, detectedType);
                    return opened;
                });
    }

    /**
     * 직전 상황을 이어도 되는지 판정한다. 시각에 의존하지 않는 순수 함수라 경계를 그대로 테스트할 수 있다.
     *
     * <p>세 가지에서 끊는다:</p>
     * <ul>
     *   <li><b>기준 시간 초과</b> - {@code mergeMinutes}를 넘으면 별개 상황. 경계값(정확히 10분)은 승계다.</li>
     *   <li><b>KST 자정</b> - 통계가 일자별이라 23:55~00:10 상황이 어느 날짜에 속하는지 모호해진다.
     *       복약 유예 창의 "자정에서 자른다"와 같은 판단이며, 드물게 한 사건이 두 건으로 기록되는 쪽을 택했다.</li>
     *   <li><b>시각 역행</b> - 새 감지가 직전보다 이르면(시계 조정·순서 뒤바뀜) 잇지 않는다.
     *       이으면 {@code lastDetectedAt}이 과거로 되돌아가 이후 승계 판정이 전부 어긋난다.</li>
     * </ul>
     */
    static boolean isContinuation(OffsetDateTime lastDetectedAt, OffsetDateTime detectedAt, long mergeMinutes) {
        if (detectedAt.isBefore(lastDetectedAt)) {
            return false;
        }
        if (!kstDate(lastDetectedAt).equals(kstDate(detectedAt))) {
            return false;
        }
        return Duration.between(lastDetectedAt, detectedAt).compareTo(Duration.ofMinutes(mergeMinutes)) <= 0;
    }

    private static LocalDate kstDate(OffsetDateTime time) {
        return time.atZoneSameInstant(KST).toLocalDate();
    }
}
