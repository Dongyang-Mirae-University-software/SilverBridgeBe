package kr.silverbridge.main.domain.medication.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
}
