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
    /** 관리자 AI 통계 집계 호출 전체(2026-10-28 점검 L-4) - 숫자만 오는 조회라 일반 AI 조회와 같은 8초. */
    private Duration statsCallTimeout = Duration.ofSeconds(8);
    private int maxConcurrent = 10;
    private int maxPerUser = 1;
    private int perMinute = 10;
    private int perHour = 120;
    private int maxMessageChars = 2000;
    /**
     * 챗 전송 요청 본문 최대 크기(파싱 전, 2026-10 전체 점검 M-4). 정상 최대치는 기록 24개 × 4000자 + 메시지 2000자 +
     * context 8KB라 한글 UTF-8로 약 300KB다. 넘으면 파싱·속도 제한 전에 413으로 끊는다.
     */
    private int maxRequestBytes = 512 * 1024;
    /** AI 응답 본문 최대 크기(전송). 기록 목록은 {@link #maxLogsBytes}. */
    private int maxReplyBytes = 2 * 1024 * 1024;
    private int maxLogsBytes = 10 * 1024 * 1024;
}
