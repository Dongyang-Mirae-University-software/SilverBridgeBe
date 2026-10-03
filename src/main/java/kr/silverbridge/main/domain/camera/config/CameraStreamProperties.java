package kr.silverbridge.main.domain.camera.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 보호자 카메라 영상 중계 설정 (application.yaml {@code camera.stream.*}).
 *
 * <p>브라우저가 AI 서버에 직접 붙던 경로(FE 무인증 프록시·브라우저에 노출된 AI 키)를 백엔드 중계로 바꾸면서
 * 생겼다(2026-10-03). {@code apiKey}는 이상감지 수신과 같은 {@code AI_API_KEY}를 쓰고 <b>서버 안에서만</b>
 * 헤더로 보낸다 - 로그·응답에 남기지 말 것.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "camera.stream")
public class CameraStreamProperties {

    /** AI 서버 HTTP 주소(경로 {@code /api/v1/...} 앞부분). */
    private String aiBaseUrl = "https://testai.gosky.kr";

    /** AI 서버 API Key. 비어 있으면 중계 API가 503을 준다(기동은 막지 않는다). */
    private String apiKey = "";

    /** AI 연결 제한. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 목록·상태·스냅샷 응답 제한. */
    private Duration readTimeout = Duration.ofSeconds(5);

    /**
     * MJPEG 무수신 제한 - 이 시간 동안 AI에서 바이트가 하나도 안 오면 끊는다.
     *
     * <p>영상은 끝이 없는 응답이라 일반 응답 제한(5초)을 쓸 수 없다. 대신 읽기 한 번의 제한으로 멈춘 연결을
     * 걸러낸다(AI는 0.2초마다 프레임을 보낸다).</p>
     */
    private Duration streamIdleTimeout = Duration.ofSeconds(15);

    /**
     * 영상 한 번의 최대 길이. AI MJPEG는 세션이 끝나도 마지막 프레임을 계속 보내 스스로 끝나지 않으므로
     * 서버가 끊는다 - FE는 새 티켓으로 다시 연다.
     */
    private Duration maxStreamDuration = Duration.ofMinutes(30);

    /** 시청 중 연결·계정 상태를 다시 확인하는 주기. 연결 해제·정지 후 이 시간 안에 영상이 끊긴다. */
    private Duration revalidateInterval = Duration.ofSeconds(60);

    /** 스트림 티켓 수명(1회용). */
    private Duration ticketTtl = Duration.ofSeconds(60);

    /** 서버 전체 동시 시청 상한 - 시청 1건이 요청 스레드 1개와 AI 연결 1개를 붙든다. */
    private int maxConcurrentStreams = 20;

    /** 보호자 1명의 동시 시청 상한. */
    private int maxStreamsPerUser = 2;

    /** 스냅샷 최대 크기(바이트) - AI 응답을 메모리에 올리므로 상한을 둔다. */
    private int maxFrameBytes = 5 * 1024 * 1024;
}
