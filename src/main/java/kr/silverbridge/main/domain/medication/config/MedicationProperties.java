package kr.silverbridge.main.domain.medication.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

/**
 * 복약 알림 발송 설정 (application.yaml {@code medication.reminder.*}).
 *
 * <p>기본값은 코드와 서버가 같게 유지한다 — 이상감지에서 서버만 환경변수로 덮어써 <b>로컬만 다르게 동작</b>하던
 * 문제(2026-07-28)를 반복하지 않기 위함이다.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "medication.reminder")
public class MedicationProperties {

    /**
     * 복약 알림 발송 전체 ON/OFF(킬 스위치). false면 스케줄러가 아무것도 보내지 않는다.
     *
     * <p>운영에서 문구·빈도 문제가 드러났을 때 배포 없이 즉시 멈출 수 있어야 해서 둔다.
     * 등록·체크·조회(1차 기능)는 이 값과 무관하게 계속 동작한다.</p>
     */
    private boolean enabled = true;

    /**
     * 복용 시각이 지난 뒤 알림을 보낼 수 있는 유예 시간(분).
     *
     * <p>재배포·순단으로 정각을 놓쳤을 때를 덮되, 무제한이면 밤늦게 "아침 약 드세요"가 나간다.
     * 이 창을 벗어난 복용 건은 그냥 건너뛴다(다음 날 정상 발송).</p>
     */
    private int graceMinutes = 30;

    /** 최초 알림 후 체크가 없을 때 재알림까지 기다리는 시간(분). */
    private int retryDelayMinutes = 15;

    /**
     * 재알림 마감(분). 최초 발송으로부터 이 시간이 지나면 재알림을 포기한다.
     *
     * <p>유예 창과 같은 취지 — 서버가 오래 내려갔다 올라왔을 때 한참 지난 재알림이 튀어나오지 않게 한다.</p>
     */
    private int retryDeadlineMinutes = 60;

    /** 미복용 시 보호자에게 보내는 저녁 요약 알림 설정(3차). */
    private MissedAlert missedAlert = new MissedAlert();

    /**
     * 미복용 요약 알림 설정 ({@code medication.reminder.missed-alert.*}).
     *
     * <p>피보호자 알림(위 필드들)과 <b>독립적으로 끌 수 있게</b> 분리했다 — 보호자 쪽 문구·빈도 문제가
     * 드러났을 때 피보호자 복용 알림까지 함께 멈추면 안 된다.</p>
     */
    @Getter
    @Setter
    public static class MissedAlert {

        /** 미복용 요약 알림 ON/OFF(킬 스위치). false면 보호자에게 아무것도 보내지 않는다. */
        private boolean enabled = true;

        /**
         * 요약을 보내는 시각(KST).
         *
         * <p>이 시각까지 복용 시각이 지난 약만 집계한다 — 취침 전 약은 아직 먹을 때가 아니라 제외된다.
         * 자정 직전으로 미루면 보호자가 자고 있어 대응할 수 없으므로 저녁으로 둔다.</p>
         */
        private LocalTime alertTime = LocalTime.of(21, 0);

        /**
         * 발송 마감(분). {@code alertTime}부터 이 시간까지만 보낸다.
         *
         * <p>서버가 늦게 복구됐을 때 자정 직전에 요약이 튀어나오는 걸 막는다.
         * 마감이 자정을 넘기면 그날 23:59:59에서 끊는다(날짜가 바뀌면 요약 대상 자체가 달라지므로).</p>
         */
        private int deadlineMinutes = 120;
    }
}
