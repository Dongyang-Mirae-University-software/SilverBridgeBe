package kr.silverbridge.main.domain.checkin.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 안부 말벗 설정(2026-11-01). <b>기본 꺼짐</b>이고 AI 주소·키에 <b>코드 기본값이 없다</b> - 셋 중 하나라도 없으면 모든 말벗 API가
 * 503 {@code CHECKIN_DISABLED}다. dev가 gosky에 pull돼도 아무 일도 일어나지 않게 하려는 것이다(말벗은 vkcs-linux에서만 켠다).
 *
 * <p>AI 주소·키는 챗봇·이상감지용 {@code AI_HTTP_BASE_URL}·{@code AI_API_KEY}와 분리한 {@code CHECKIN_AI_BASE_URL}·
 * {@code CHECKIN_AI_API_KEY}다 - 말벗 AI(DGX)와 챗봇 AI(gosky)는 다른 서버다.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "checkin")
public class CheckinProperties {

    private boolean enabled = false;
    private String aiBaseUrl = "";
    private String apiKey = "";

    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration startTimeout = Duration.ofSeconds(10);
    /** 턴 한 번(받아쓰기 + 답 생성). DGX 실측 1~2초, AI 생성 대기 상한 10초. */
    private Duration turnTimeout = Duration.ofSeconds(20);
    private Duration finishTimeout = Duration.ofSeconds(30);
    private Duration purgeTimeout = Duration.ofSeconds(15);

    /** 대화 길이(BE가 센다). 이 시간이 지나면 새 턴을 받지 않는다. */
    private Duration sessionDuration = Duration.ofMinutes(2);
    /** 마지막 턴 녹음(최대 60초)이 도착할 여유. 시작 + 대화 길이 + 여유까지 턴을 받는다. */
    private Duration turnGrace = Duration.ofSeconds(60);
    private int maxTurns = 8;

    private int maxConcurrent = 6;
    private int maxPerUser = 1;
    private int startsPerMinute = 10;
    private int startsPerHour = 60;
    private int turnsPerMinute = 20;
    private int turnsPerHour = 200;

    private long maxAudioBytes = 5L * 1024 * 1024;
    /** 사진 장수 상한. AI는 120장까지 받지만 multipart 전역 상한(10MB) 안에 음성 5MB와 함께 들어가도록 BE가 줄인다(결정 Q4). */
    private int maxFrames = 8;
    private long maxFrameBytes = 300L * 1024;
    private int maxReplyBytes = 64 * 1024;

    /** 응답 창 - 시각부터 이 시간 안에만 대화를 시작할 수 있다(2026-11-01 결정 Q2: 1시간). */
    private Duration responseWindow = Duration.ofMinutes(60);
    /** 안부 알림 분산 - 피보호자마다 시각 + 0~(이 값-1)분에 보낸다(같은 분에 몰리지 않게). */
    private int promptSpreadMinutes = 5;
    /** 미응답 알림은 응답 창이 닫힌 뒤 이 시간 안에만 보낸다(서버 재시작 뒤 아침 미응답이 밤에 나가지 않게). */
    private Duration missedNotifyDeadline = Duration.ofMinutes(60);
    /** 평소 대비 기준 기간(일) - 결정 Q2: 14일. */
    private int baselineDays = 14;

    /** 대화 API를 쓸 수 있는지 - 켜져 있고 주소·키가 모두 있어야 한다. */
    public boolean isActive() {
        return enabled && canCallAi();
    }

    /** 주소·키가 있는지. 탈퇴 정리는 킬 스위치와 무관하게 이것만 본다(AI 계약: 삭제는 꺼져도 동작). */
    public boolean canCallAi() {
        return StringUtils.hasText(aiBaseUrl) && StringUtils.hasText(apiKey);
    }
}
