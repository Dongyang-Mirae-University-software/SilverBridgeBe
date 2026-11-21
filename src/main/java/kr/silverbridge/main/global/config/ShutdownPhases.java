package kr.silverbridge.main.global.config;

import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;

/**
 * 우아한 종료 때 우리 빈이 멈추는 단계({@code SmartLifecycle} phase). <b>값이 큰 쪽이 먼저 멈춘다.</b>
 *
 * <p>종료 순서(2026-11-22, 실측 기준 - {@code docs/(2026-11-22) fix-shutdown-order.md}):</p>
 * <pre>
 * MAX            영상 중계 자리(CameraStreamSlots)
 * MAX - 1024     웹 서버 우아한 종료(진행 중 요청 대기)
 * MAX - 2048     웹 서버 정지
 * MAX/2 + 1024   AI 화재 수신 중단            ← {@link #AI_STREAM_SUBSCRIBER}
 * MAX/2 + 512    긴급·일반 알림 풀 비우기      ← {@link #NOTIFICATION_DRAIN}
 * MAX/2          AsyncConfig 풀 5개(아무것도 하지 않음 - 실제 종료는 빈 파괴 때)
 * 0              WebSocket 브로커·스케줄러·Redis(Lettuce)
 * (빈 파괴)       풀 5개 잔여 대기 → 수동 풀(Solapi·Email·AccessLog) → JPA → DB
 * </pre>
 *
 * <p>알림 풀을 비우는 단계가 0보다 커야 하는 이유: Redis가 0단계에서 멈춘다. 그 뒤에 도는 알림 작업은 쿨다운·문자 상한을
 * 확인하지 못해 fail-open(중복 발송)으로 간다. 수신 중단이 비우기보다 먼저여야 하는 이유: 풀을 닫은 뒤 들어온 화재
 * 신호는 이력·쿨다운만 남기고 알림이 폐기된다.</p>
 *
 * <p>값을 바꾸면 {@code ShutdownOrderTest}가 순서를 다시 확인한다. 스케줄러 빈을 MAX/2 단계로 옮기게 되면
 * (지금은 WebSocket 설정의 스케줄러 하나뿐이라 0단계다) 비우기 단계를 그보다 낮게 내릴 것 - 진행 중이던 예약 작업이
 * 비운 뒤에 알림을 낼 수 있다.</p>
 */
public final class ShutdownPhases {

    /** AI 화재 수신 중단 - 웹 서버가 멈춘 뒤, 알림 풀을 비우기 직전. */
    public static final int AI_STREAM_SUBSCRIBER = ExecutorConfigurationSupport.DEFAULT_PHASE + 1024;

    /** 긴급·일반 알림 풀 비우기 - 수신 중단 뒤, Redis(0단계)가 멈추기 전. */
    public static final int NOTIFICATION_DRAIN = ExecutorConfigurationSupport.DEFAULT_PHASE + 512;

    private ShutdownPhases() {
    }
}
