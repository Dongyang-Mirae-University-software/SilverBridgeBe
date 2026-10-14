package kr.silverbridge.main.domain.camera.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 송출(피보호자 기기 -> AI) H.264 WebSocket 중계 설정 (application.yaml {@code camera.upload.*}, 2026-10-14).
 *
 * <p>브라우저 -> BE({@code /ws/camera-upload}) -> AI({@code ingest-ws}) 중계다. AI 키는 기존
 * {@code camera.stream.api-key}를 BE -> AI 내부 헤더로만 쓴다(브라우저에 내리지 않는다).
 * 통합 계약: {@code docs/(2026-10-14) design-video-transport-contract.md}.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "camera.upload")
public class CameraUploadProperties {

    /** 킬 스위치. false면 티켓 발급·핸드셰이크가 503이고 FE는 JPEG로 폴백한다. 이미 수립된 연결은 이어 간다(새 수립만 막는다). */
    private boolean ticketEnabled = true;

    /** 송출 티켓 수명(1회용). */
    private Duration ticketTtl = Duration.ofSeconds(60);

    /** 응답에 내려주는 공개 wss 주소(경로 포함, 쿼리 제외). 비어 있으면 발급이 503이다. AI 내부 주소가 아니다. */
    private String wsUrl = "";

    /** ws-h264를 쓰는 sessionId 목록(쉼표 구분). 기본 비어 있음 = 전부 jpeg. 변경은 서버 재생성(up -d). */
    private String wsH264SessionIds = "";

    /** AI WebSocket 기준 주소. 비어 있으면 {@code camera.stream.ai-base-url}의 스킴만 ws(s)로 바꿔 쓴다. */
    private String aiWsBaseUrl = "";

    /** AI 연결 제한. */
    private Duration aiConnectTimeout = Duration.ofSeconds(3);

    /** 수립된 연결의 계정·카메라·무효화 재확인 주기. 최대 수명은 두지 않는다(24시간 감시 입력). */
    private Duration revalidateInterval = Duration.ofSeconds(60);

    /** 무수신 제한 - 이 시간 동안 브라우저에서 메시지가 없으면 종료한다. */
    private Duration idleTimeout = Duration.ofSeconds(15);

    /** 바이너리 메시지 최대 크기(바이트). 넘으면 해당 연결만 close 1009. */
    private int maxMessageBytes = 256 * 1024;

    /** 초당 바이너리 메시지 상한 = 권장 fps x 이 배수(AI는 1.5배로 더 안쪽에서 거른다). */
    private double maxFpsFactor = 2.0;

    /** 전체 동시 연결 상한(카메라 등록 상한 3 + 여유). */
    private int maxConnections = 5;

    /** 한 메시지를 상대에게 보내는 데 허용하는 시간. */
    private Duration sendTimeLimit = Duration.ofSeconds(5);

    /** AI 쪽으로 쌓아 둘 수 있는 전송 대기 바이트 상한. 넘으면 연결을 끊어 지연이 쌓이지 않게 한다(폴백 유도). */
    private int sendBufferBytes = 1024 * 1024;
}
