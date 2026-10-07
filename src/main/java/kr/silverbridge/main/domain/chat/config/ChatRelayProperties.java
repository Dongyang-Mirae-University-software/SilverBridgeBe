package kr.silverbridge.main.domain.chat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 챗봇 중계 설정 (application.yaml {@code chat.relay.*}, 2026-10-07).
 *
 * <p>{@code apiKey}는 이상감지·영상 중계와 같은 {@code AI_API_KEY}이며 <b>서버 안에서만</b> 헤더로 보낸다 -
 * 로그·응답에 남기지 말 것. 챗은 모델 생성이라 일반 외부 호출 제한(8~10초)을 쓸 수 없고,
 * AI 쪽 상한(120초)보다 약간 긴 {@code callTimeout}을 쓴다.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "chat.relay")
public class ChatRelayProperties {

    private boolean enabled = true;
    private String aiBaseUrl = "https://testai.gosky.kr";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    /** 챗 전송 호출 전체(연결·헤더 대기·본문 수신). 마감이 되면 연결을 끊는다. */
    private Duration callTimeout = Duration.ofSeconds(130);
    /** 기록 조회 호출 전체. */
    private Duration logsCallTimeout = Duration.ofSeconds(15);
    private int maxConcurrent = 10;
    private int maxPerUser = 1;
    private int perMinute = 10;
    private int perHour = 120;
    private int maxMessageChars = 2000;
    /** AI 응답 본문 최대 크기(전송). 기록 목록은 {@link #maxLogsBytes}. */
    private int maxReplyBytes = 2 * 1024 * 1024;
    private int maxLogsBytes = 10 * 1024 * 1024;
}
