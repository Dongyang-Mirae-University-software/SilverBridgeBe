package kr.silverbridge.main.domain.chat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 챗봇 마이크 음성 인식(STT) 중계 설정 (application.yaml {@code chat.stt.*}, 2026-11-04).
 *
 * <p>AI의 {@code POST /companion/stt}를 부른다. 그 경로는 말벗 전용 키({@code COMPANION_API_KEY})로 지켜지므로
 * {@code apiKey}에는 그 값을 넣는다 - 챗 {@code AI_API_KEY}와 다르다. 주소·키는 <b>코드 기본값이 없다</b>: AI가 gosky에
 * 올라온 뒤 사용자 확인을 받고 {@code .env.dev}에 넣는다. 키는 서버 안에서만 헤더로 보낸다(로그·응답 금지).
 * {@code enabled=false}거나 주소·키가 비면 503 {@code CHAT_STT_DISABLED}다.</p>
 *
 * <p>{@code callTimeout} 30초: AI는 자리를 5초 기다리고 30초 이하 음성을 받아 적는다(CPU 평균 0.6초 측정). 그만큼 요청
 * 스레드를 붙들므로 서버 전체 동시 상한({@code maxConcurrent})을 둔다.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "chat.stt")
public class ChatSttProperties {

    /** 킬 스위치. 기본 꺼짐. */
    private boolean enabled = false;
    private String aiBaseUrl = "";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    /** 음성 인식 호출 전체(연결·업로드·응답 수신). 마감이 되면 연결을 끊는다. */
    private Duration callTimeout = Duration.ofSeconds(30);
    private int maxConcurrent = 4;
    private int maxPerUser = 1;
    private int perMinute = 10;
    private int perHour = 100;
    /** 음성 파일 최대 크기 - AI 계약(10MB)과 같다. 서블릿 업로드 상한도 10MB다. */
    private long maxAudioBytes = 10L * 1024 * 1024;
    /** AI 응답 본문 최대 크기. 30초 말은 수백 자라 64KB면 넉넉하다. */
    private int maxReplyBytes = 64 * 1024;

    public boolean isConfigured() {
        return StringUtils.hasText(aiBaseUrl) && StringUtils.hasText(apiKey);
    }

    public boolean isUsable() {
        return enabled && isConfigured();
    }
}
