package kr.silverbridge.main.domain.medication.service;

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
 * 선점된 미복용 요약을 실제로 보호자에게 발송한다.
 *
 * <p><b>문구는 단정하지 않는다</b> — "약을 안 드셨습니다"가 아니라 "체크되지 않았습니다"로 적는다.
 * 실제로는 복용하고 체크만 안 한 경우가 흔하고, 제3자(보호자)에게 사실이 아닌 통보를 하면
 * 불필요한 걱정과 전화를 만든다. 이 원칙을 문구 수정 시에도 유지할 것.</p>
 *
 * <p>채널은 FCM·문자뿐이다({@code SETTINGS_ONLY} + 알림톡 템플릿 매핑 없음).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationMissedAlertService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final MedicationMissedAlertPlanner planner;
    private final NotificationDispatcher notificationDispatcher;

    /** @return 실제로 발송한 건수 */
    public int sendMissedAlerts() {
        List<MedicationMissedAlertTarget> targets = planner.claimMissedAlerts();
        int sent = 0;
        for (MedicationMissedAlertTarget target : targets) {
            try {
                notificationDispatcher.dispatch(
                        target.guardianId(), NotificationType.MEDICATION_MISSED, content(target));
                sent++;
            } catch (RuntimeException e) {
                // 이미 발송 기록을 선점했으므로 재시도하지 않는다(중복 발송 방지 우선, 2차와 동일한 방침).
                log.error("[MEDICATION-MISSED] 발송 실패 guardianId={}, wardId={}",
                        target.guardianId(), target.wardId(), e);
            }
        }
        return sent;
    }

    /**
     * 알림 문구.
     *
     * <p><b>집계 상한(보호자가 지정한 시각)을 본문에 밝힌다</b> - 분모가 "오늘 전체"가 아니라
     * "그 시각까지 예정된" 수라, 시각을 빼면 이후에 먹을 약까지 확인된 것처럼 읽힌다.</p>
     */
    private NotificationContent content(MedicationMissedAlertTarget target) {
        String alertTime = target.alertTime().format(TIME_FORMAT);
        String body = "%s님의 %s까지 예정된 복약 %d건 중 %d건이 아직 체크되지 않았습니다."
                .formatted(target.wardName(), alertTime, target.totalCount(), target.missedCount());

        return NotificationContent.of("복약 확인이 필요해요", body, Map.of(
                "type", "MEDICATION_MISSED",
                "wardId", target.wardId(),
                "doseDate", target.doseDate().toString(),
                "alertTime", alertTime,
                "missedCount", String.valueOf(target.missedCount()),
                "totalCount", String.valueOf(target.totalCount())));
    }
}
