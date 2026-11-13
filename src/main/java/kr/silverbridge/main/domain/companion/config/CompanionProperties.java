package kr.silverbridge.main.domain.companion.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 안부 말벗 중계 설정 (application.yaml {@code companion.*}, 2026-11-04).
 *
 * <p>주소·키는 <b>코드 기본값이 없다</b> - AI가 gosky 메인 AI 서버에 올라온 뒤 사용자 확인을 받고 {@code .env.dev}에
 * 넣는다. 키는 챗 {@code AI_API_KEY}와 다른 {@code COMPANION_AI_API_KEY}이며 서버 안에서만 헤더로 보낸다(로그·응답 금지).
 * {@code enabled=false}거나 주소·키가 비면 기기 API는 503 {@code COMPANION_DISABLED}다. 탈퇴 삭제는 스위치와 무관하게
 * 주소·키만 있으면 보낸다(챗 중계와 같은 원칙 - 스위치는 전송만 멈춘다).</p>
 *
 * <p>{@code callTimeout} 90초: AI 한 턴(음성 인식 + 언어 모델)을 60초 안에 끝내 달라고 요청하고 BE는 여유를 둬
 * 90초까지 기다린다(사용자 결정 2026-11-04). 그만큼 요청 스레드를 붙들어 서버 전체 동시 상한을 둔다.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "companion")
public class CompanionProperties {

    /** 킬 스위치. 기본 꺼짐. */
    private boolean enabled = false;
    private String aiBaseUrl = "";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    /** 대화 열기·턴·마감 호출 전체 제한. 마감이 되면 연결을 끊는다. */
    private Duration callTimeout = Duration.ofSeconds(90);
    /** 탈퇴 삭제 호출 제한(메모리·검수 목록 삭제라 짧다). */
    private Duration purgeCallTimeout = Duration.ofSeconds(10);
    /** 이어 가는 대화의 상태(마지막 말벗 문장) 조회 제한(2026-11-14). 넘으면 여는 질문으로 이어 간다. */
    private Duration stateCallTimeout = Duration.ofSeconds(3);
    private int maxConcurrent = 8;
    private int perMinute = 20;
    private int perHour = 200;
    /** 대답 글 최대 길이(자). */
    private int maxTextLength = 500;
    private long maxAudioBytes = 5L * 1024 * 1024;
    private long maxFrameBytes = 1024L * 1024;
    private int maxFrames = 5;
    private int maxReplyBytes = 64 * 1024;
    /**
     * 말벗 목소리 중계(2026-11-13). AI가 소리가 준비될 때까지 {@code audioWaitSeconds}초 기다리므로 읽기 제한은 그보다
     * 조금 길게 둔다. 소리는 메모리로만 넘기고 저장하지 않는다.
     */
    private int audioWaitSeconds = 5;
    private Duration audioCallTimeout = Duration.ofSeconds(8);
    private int audioMaxBytes = 1024 * 1024;
    private int audioMaxIndex = 50;
    /** 목소리 중계 동시 상한 - 대답 턴(maxConcurrent)과 따로 센다. 넘치면 503(기기가 직접 읽어 준다). */
    private int audioMaxConcurrent = 8;
    /** 피보호자 1명의 목소리 동시 요청 상한(2026-11-13 점검 M-1) - 지금 줄 + 다음 줄 미리 받기. 넘치면 503. */
    private int audioMaxPerWard = 2;
    /** 시간대 시작부터 대화를 열 수 있는 시간(분). */
    private long windowMinutes = 60;
    /** 이 시간 동안 활동이 없는 대화는 스케줄러가 마감한다. */
    private Duration abandonAfter = Duration.ofMinutes(10);
    /** 스케줄러 1회에 마감할 최대 대화 수. */
    private int abandonBatchSize = 10;
    private int purgeBatchSize = 50;
    /**
     * 질문 알림을 받을 피보호자 ID(쉼표 구분). 비면 아무에게도 보내지 않는다 - 피보호자 화면·알림 설정이 PR-B에서
     * 생기기 전까지 시연 계정에만 보내려는 허용 목록이다.
     */
    private List<String> promptWardIds = new ArrayList<>();

    /**
     * 최종 QA 테스트 계정(2026-11-10, {@code COMPANION_TEST_WARD_IDS}). 이 피보호자는 지금 시각과 상관없이 시간대를 골라
     * 대화를 몇 번이든 다시 하고, 안내 푸시를 자기에게 보내고, 턴·마치기 응답에 AI 판정 결과를 받는다. 비면 꺼짐.
     * 모든 계정을 뜻하는 {@code *}는 받지 않는다(기동 실패) - 운영 서버에는 넣지 않는다.
     */
    private List<String> testWardIds = new ArrayList<>();

    /**
     * 모든 피보호자를 테스트 계정으로 본다(2026-11-11, {@code COMPANION_TEST_ALL_WARDS}, 기본 false). gosky dev 최종 QA용 -
     * <b>운영 서버 {@code .env}에 넣지 말 것</b>. 보호자·관리자는 해당 없음(역할은 {@code WardCompanionController}의
     * {@code hasRole('WARD')}가 막는다). 목록 방식({@code testWardIds})은 그대로 둔다.
     */
    private boolean testAllWards = false;

    /** 대화 내용(동의 시에만 저장) 보관 일수. 지나면 매일 04:55에 글 칸을 비운다(기분은 남김). */
    private int transcriptRetentionDays = 30;

    /** 여러 날 위험 누적 알림(2026-11-11, {@code COMPANION_RISK_PATTERN_*}). 기본 꺼짐. */
    private RiskPattern riskPattern = new RiskPattern();

    /**
     * 최근 {@code windowDays}일(오늘 포함) 중 턴 위험 확률 {@code threshold} 이상인 서로 다른 날이 {@code minDays}일
     * 이상이면 보호자에게 알린다. 같은 피보호자에게 {@code windowDays}일에 1번. 기준값은 AI v9 척도 기준이다
     * (11/08 DGX 연구 - 실제 어르신 대화로는 검증 전이라 기본 꺼짐).
     */
    @Getter
    @Setter
    public static class RiskPattern {
        private boolean enabled = false;
        private BigDecimal threshold = new BigDecimal("0.97");
        private int minDays = 2;
        private int windowDays = 7;
    }

    public void setTestWardIds(List<String> testWardIds) {
        List<String> cleaned = new ArrayList<>();
        if (testWardIds != null) {
            for (String id : testWardIds) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                if (id.strip().contains("*")) {
                    throw new IllegalArgumentException("companion.test-ward-ids 에 '*' 는 쓸 수 없습니다 - 계정 ID를 적으세요.");
                }
                cleaned.add(id.strip());
            }
        }
        this.testWardIds = cleaned;
    }

    /** 말벗이 켜져 있고 테스트 계정인가(전체 공개 스위치가 켜졌거나 목록에 있음). 역할은 보지 않는다 - 컨트롤러가 막는다. */
    public boolean isTestWard(String wardId) {
        return isUsable() && wardId != null && (testAllWards || testWardIds.contains(wardId));
    }

    public boolean isConfigured() {
        return StringUtils.hasText(aiBaseUrl) && StringUtils.hasText(apiKey);
    }

    public boolean isUsable() {
        return enabled && isConfigured();
    }
}
