package kr.silverbridge.main.domain.game.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 보호자 게임 기록 중계 설정 (application.yaml {@code game.relay.*}, 2026-11-19).
 *
 * <p>FE {@code /api/streams} 프록시가 송출 3개 경로만 받게 줄어들어(FE ad44cb6) 보호자 게임 관리 화면의 AI 조회가 405로
 * 막혔다. 영상·챗 중계와 같이 백엔드가 인가한 뒤 AI를 부른다. {@code apiKey}는 영상·챗 중계와 같은 {@code AI_API_KEY}를
 * 쓰고 <b>서버 안에서만</b> {@code X-API-Key} 헤더로 보낸다 - 로그·응답에 남기지 말 것.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "game.relay")
public class GameRelayProperties {

    /** AI 서버 HTTP 주소(경로 {@code /api/v1/games/...} 앞부분). */
    private String aiBaseUrl = "https://testai.gosky.kr";

    /** AI 서버 API Key. 비어 있으면 중계 API가 503을 준다(기동은 막지 않는다). */
    private String apiKey = "";

    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 읽기 1회 제한. */
    private Duration readTimeout = Duration.ofSeconds(5);

    /** 호출 전체 제한 - 넘으면 연결을 끊는다(요청 스레드를 오래 붙들지 않게). */
    private Duration callTimeout = Duration.ofSeconds(8);
}
