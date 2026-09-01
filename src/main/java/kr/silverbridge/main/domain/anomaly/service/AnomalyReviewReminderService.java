package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.DetectedTypeLabel;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 선점된 판정 재촉을 실제로 보호자에게 발송한다.
 *
 * <p><b>문구는 요청이지 통보가 아니다</b> - "확인해 주세요"까지만 말하고 위험 여부를 단정하지 않는다.
 * 서버는 그 감지가 진짜였는지 모르며, 그걸 묻는 것이 이 알림의 목적이다.</p>
 *
 * <p>채널은 FCM뿐이다({@code ANOMALY_REVIEW_REQUIRED}가 허용 채널을 FCM으로 선언한다).
 * 문자는 반복 과금이고, 알림톡은 다발성이라 승인 템플릿 없이 보낼 수 없다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyReviewReminderService {

    private static final DateTimeFormatter DETECTED_AT_FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm");
    /** 카메라가 삭제돼 위치를 모를 때의 폴백. */
    private static final String FALLBACK_LOCATION = "등록된 카메라";

    private final AnomalyReviewReminderPlanner planner;
    private final NotificationDispatcher notificationDispatcher;

    /** 건별 재촉(1차). @return 실제로 발송한 건수 */
    public int sendReminders() {
        List<AnomalyReviewReminderTarget> targets = planner.claimReminders();
        int sent = 0;
        for (AnomalyReviewReminderTarget target : targets) {
            try {
                notificationDispatcher.dispatch(
                        target.guardianId(), NotificationType.ANOMALY_REVIEW_REQUIRED, reminderContent(target));
                sent++;
            } catch (RuntimeException e) {
                // 발송 기록을 이미 선점했으므로 재시도하지 않는다(중복 발송 방지 우선).
                // 이 회차를 놓쳐도 하루 1회 요약이 두 번째 기회다.
                log.error("[ANOMALY-REVIEW] 재촉 발송 실패 guardianId={}, incidentId={}",
                        target.guardianId(), target.incidentId(), e);
            }
        }
        return sent;
    }

    /** 하루 1회 미응답 요약. @return 실제로 발송한 건수 */
    public int sendSummaries() {
        List<AnomalyReviewSummaryTarget> targets = planner.claimSummaries();
        int sent = 0;
        for (AnomalyReviewSummaryTarget target : targets) {
            try {
                notificationDispatcher.dispatch(
                        target.guardianId(), NotificationType.ANOMALY_REVIEW_REQUIRED, summaryContent(target));
                sent++;
            } catch (RuntimeException e) {
                log.error("[ANOMALY-REVIEW] 미응답 요약 발송 실패 guardianId={}", target.guardianId(), e);
            }
        }
        return sent;
    }

    /**
     * 건별 재촉 문구.
     *
     * <p>"화재가 발생했습니다"가 아니라 "화재 감지를 확인해 주세요"다 - 실제 위험이었는지는 아직
     * 아무도 모르고, 그걸 묻는 알림이기 때문이다. 단정하면 지난 일로 놀라게 만든다.</p>
     */
    private NotificationContent reminderContent(AnomalyReviewReminderTarget target) {
        String label = DetectedTypeLabel.of(target.detectedType());
        String location = target.cameraLabel() != null ? target.cameraLabel() : FALLBACK_LOCATION;
        String detectedAt = target.startedAt().atZoneSameInstant(AnomalyReviewClock.KST).format(DETECTED_AT_FORMAT);
        String body = "%s · %s님 %s에서 %s 감지가 있었습니다. 실제 상황이었는지 확인해 주세요."
                .formatted(detectedAt, target.wardName(), location, label);

        return NotificationContent.of("이상감지 확인이 필요해요", body, Map.of(
                "type", "ANOMALY_REVIEW_REQUIRED",
                "incidentId", String.valueOf(target.incidentId()),
                "wardId", target.wardId(),
                "wardName", target.wardName(),
                "detectedType", target.detectedType().name(),
                "detectedTypeLabel", label));
    }

    /** 요약 문구. 단위는 <b>상황</b>이지 감지 이력 건수가 아니다. */
    private NotificationContent summaryContent(AnomalyReviewSummaryTarget target) {
        String body = "확인이 필요한 이상감지가 %d건 있습니다. 실제 상황이었는지 알려주시면 정확도를 높이는 데 도움이 됩니다."
                .formatted(target.pendingCount());

        return NotificationContent.of("이상감지 확인이 필요해요", body, Map.of(
                "type", "ANOMALY_REVIEW_SUMMARY",
                "pendingCount", String.valueOf(target.pendingCount()),
                "summaryDate", target.summaryDate().toString()));
    }
}
