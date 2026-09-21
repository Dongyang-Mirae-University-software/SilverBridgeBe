package kr.silverbridge.main.domain.sos.listener;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.sos.event.SosTriggeredEvent;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.sos.service.SosNotificationCooldown;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 피보호자 SOS 발생 이벤트를 수신해 ACTIVE 보호자 전원에게 긴급 알림을 발송하는 리스너.
 *
 * <p>{@code ConnectionNotificationListener}와 동일 패턴이다: {@code @TransactionalEventListener(AFTER_COMMIT)}로
 * 이력 저장 커밋 후에만 동작하고(롤백 시 미발송), {@code @Async("notificationExecutor")}로 발송 지연이
 * HTTP 응답 시간에 포함되지 않도록 분리한다.</p>
 *
 * <p>발송은 두 갈래다:</p>
 * <ul>
 *   <li><b>WebSocket</b>({@code sos-triggered}) — 채널 추상화 밖, 항상 발송.</li>
 *   <li><b>{@link NotificationDispatcher}</b> + {@link NotificationType#WARD_SOS}(필수 알림) —
 *       보호자의 알림 설정을 무시하고 강제 발송(긴급 알림이므로 끌 수 없음).</li>
 * </ul>
 *
 * <p>보호자가 여러 명이면 각 보호자 발송을 try/catch로 감싸 한 명 발송 실패가 나머지 보호자 발송을 막지 않게
 * 격리한다(실패 격리). 연결된 ACTIVE 보호자가 없으면 이력만 남고 발송 없이 종료한다.</p>
 *
 * <p>연타 시 보호자 알림 폭주를 막기 위해 {@link SosNotificationCooldown}으로 동일 피보호자 알림에 쿨다운을
 * 둔다 — 단 쿨다운은 <b>알림</b>에만 적용되며 이력({@code sos_events})은 항상 보존된다. 쿨다운 인프라 장애 시에는
 * 긴급 우선 원칙에 따라 알림을 발송한다(fail-open).</p>
 *
 * <p><b>반복 횟수 표기</b>: 쿨다운에 막혀 생략된 연타는 보호자에게 보이지 않아, 두 번째 알림이 첫 번째와 문구가
 * 같으면 "새 상황"인지 "같은 상황이 계속되는지" 구분할 수 없다. 그래서 발송 직전 최근 {@code REPEAT_WINDOW}
 * 안의 이력 건수를 세어 2회 이상이면 문구에 횟수를 담는다 — 생략된 연타를 정보로 되살리는 것이 목적이다.
 * 횟수는 <b>사실만</b> 말한다(위급도·원인을 단정하지 않는다). 집계에 실패하면 알림을 막지 않고 기본 문구로
 * 발송한다(쿨다운과 같은 fail-open).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SosNotificationListener {

    /**
     * 반복 횟수 집계 창. 쿨다운(30초)과는 <b>별개 값</b>이다 - 쿨다운은 "얼마나 자주 보낼지",
     * 이 창은 "얼마나 거슬러 세어 한 상황으로 볼지"를 정한다. 이상감지의 상황 병합(10분)과 같은 감각으로 잡았다.
     */
    private static final Duration REPEAT_WINDOW = Duration.ofMinutes(10);

    /** 집계 실패 시 쓰는 값 - 1회로 보아 기본 문구로 발송한다(알림을 막지 않는다). */
    private static final long SINGLE_OCCURRENCE = 1L;

    private final ConnectionService connectionService;
    private final WebSocketEventPublisher webSocketEventPublisher;
    private final NotificationDispatcher notificationDispatcher;
    private final SosNotificationCooldown cooldown;
    private final SosEventRepository sosEventRepository;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSosTriggered(SosTriggeredEvent event) {
        List<String> guardianIds = connectionService.getActiveGuardianIds(event.wardId());
        if (guardianIds.isEmpty()) {
            log.info("SOS 알림 대상 보호자 없음(이력은 보존): wardId={}, sosEventId={}",
                    event.wardId(), event.sosEventId());
            return;
        }

        // 연타 알림 폭주 방지 — 쿨다운 내 재요청은 알림만 생략(이력은 이미 저장됨). 긴급 재요청을 차단(429)하지는 않는다.
        if (!cooldown.tryAcquire(event.wardId())) {
            log.info("SOS 알림 쿨다운 — 직전 발송 후 재요청이라 알림 생략(이력은 보존): wardId={}, sosEventId={}",
                    event.wardId(), event.sosEventId());
            return;
        }

        // 쿨다운을 통과한 뒤에만 센다 - 생략된 연타가 DB를 건드리지 않게 하는 순서다.
        long repeatCount = countRecentOccurrences(event.wardId());
        String body = buildBody(event.wardName(), repeatCount);
        String repeatCountValue = String.valueOf(repeatCount);
        int sent = 0;
        for (String guardianId : guardianIds) {
            try {
                webSocketEventPublisher.sendToUser(guardianId, "sos-triggered",
                        Map.of("wardId", event.wardId(),
                                "wardName", event.wardName(),
                                "sosEventId", String.valueOf(event.sosEventId()),
                                // 기존 키는 그대로 두고 추가만 한다 - 쓰지 않는 프론트는 영향받지 않는다.
                                "repeatCount", repeatCountValue));

                notificationDispatcher.dispatch(guardianId, NotificationType.WARD_SOS,
                        NotificationContent.of("긴급 SOS", body,
                                Map.of("type", "WARD_SOS",
                                        "wardId", event.wardId(),
                                        "sosEventId", String.valueOf(event.sosEventId()),
                                        "repeatCount", repeatCountValue)));
                sent++;
            } catch (Exception e) {
                // 한 보호자 발송 실패가 나머지 보호자 발송을 막지 않도록 격리. 원인 진단을 위해 스택 포함 (L-S2-6)
                log.error("SOS 알림 발송 실패: guardianId={}, sosEventId={}",
                        guardianId, event.sosEventId(), e);
            }
        }
        log.info("SOS 긴급 알림 발송: sosEventId={}, 대상 보호자={}명, 발송={}건, 최근 {}분 내 {}번째",
                event.sosEventId(), guardianIds.size(), sent, REPEAT_WINDOW.toMinutes(), repeatCount);
    }

    /**
     * 최근 집계 창 안의 SOS 이력 건수(현재 건 포함 - 리스너는 커밋 후에 돈다).
     *
     * <p>집계 실패가 긴급 알림을 막아선 안 되므로 예외를 삼키고 1회로 본다(기본 문구로 발송).
     * 쿨다운 확인 실패 시 발송하는 fail-open과 같은 판단이다.</p>
     */
    private long countRecentOccurrences(String wardId) {
        try {
            long count = sosEventRepository.countByWardIdAndCreatedAtGreaterThanEqual(
                    wardId, OffsetDateTime.now().minus(REPEAT_WINDOW));
            return Math.max(count, SINGLE_OCCURRENCE);
        } catch (Exception e) {
            log.warn("SOS 반복 횟수 집계 실패 — 기본 문구로 발송(fail-open): wardId={}, error={}",
                    wardId, e.getMessage());
            return SINGLE_OCCURRENCE;
        }
    }

    /**
     * 알림 본문. 2회 이상이면 "계속 요청 중"임을 횟수와 함께 알린다.
     *
     * <p>단정하지 않는다 - 횟수는 확인된 사실이지만 위급도·원인은 아직 아무도 모른다.
     * 1회일 때 문구는 기존과 <b>글자 그대로 동일</b>하게 유지한다(기존 프론트·테스트 계약).</p>
     */
    private String buildBody(String wardName, long repeatCount) {
        if (repeatCount < 2) {
            return wardName + "님이 긴급 도움을 요청했습니다.";
        }
        return wardName + "님이 계속 도움을 요청하고 있습니다. (최근 "
                + REPEAT_WINDOW.toMinutes() + "분 내 " + repeatCount + "번째)";
    }
}
