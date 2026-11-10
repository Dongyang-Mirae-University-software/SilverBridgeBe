package kr.silverbridge.main.domain.companion.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

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
    private int maxConcurrent = 8;
    private int perMinute = 20;
    private int perHour = 200;
    /** 대답 글 최대 길이(자). */
    private int maxTextLength = 500;
    private long maxAudioBytes = 5L * 1024 * 1024;
    private long maxFrameBytes = 1024L * 1024;
    private int maxFrames = 5;
    private int maxReplyBytes = 64 * 1024;
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

    /** 말벗이 켜져 있고 테스트 목록에 있는 계정인가. */
    public boolean isTestWard(String wardId) {
        return isUsable() && wardId != null && testWardIds.contains(wardId);
    }

    public boolean isConfigured() {
        return StringUtils.hasText(aiBaseUrl) && StringUtils.hasText(apiKey);
    }

    public boolean isUsable() {
        return enabled && isConfigured();
    }
}
