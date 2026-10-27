package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.client.AiServerHealthClient;
import kr.silverbridge.main.domain.admin.dto.AdminAiStatusResponse;
import kr.silverbridge.main.domain.admin.dto.AiServiceState;
import kr.silverbridge.main.domain.anomaly.client.AiLiveStreamSubscriber;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 관리자 대시보드 "AI 서비스 상태"(2026-10-27).
 *
 * <p><b>트랜잭션을 걸지 않는다</b> - DB를 읽지 않고, AI HTTP 호출(최대 5초)이 DB 연결을 붙들 이유가 없다.
 * 안전 현황({@code /safety})과 경로를 나눈 것도 같은 이유다: AI가 느려도 안전 현황 응답은 늦어지지 않는다.
 * 서버 캐시를 두지 않는다(대시보드 공통) - {@code checkedAt}이 곧 요청 시각이다.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminAiStatusService {

    private final AiLiveStreamSubscriber aiLiveStreamSubscriber;
    private final AiServerHealthClient aiServerHealthClient;
    private final ChatRelayProperties chatRelayProperties;

    public AdminAiStatusResponse getAiStatus() {
        AiServiceState anomaly = anomalyState();
        OffsetDateTime anomalyCheckedAt = AdminDashboardClock.now();

        AiServiceState chatbot = chatbotState();
        OffsetDateTime chatbotCheckedAt = AdminDashboardClock.now();

        return new AdminAiStatusResponse(
                new AdminAiStatusResponse.Item(anomaly, anomalyCheckedAt),
                new AdminAiStatusResponse.Item(chatbot, chatbotCheckedAt),
                chatbotCheckedAt);
    }

    /**
     * 구독 끊김은 우리가 확실히 아는 사실이라 null(모름)이 아니라 DISCONNECTED다. 연결은 됐지만 재접속 직후 첫 세션 목록을
     * 아직 못 받았으면 어느 카메라를 지켜보는지 모르는 몇 초라 null(확인 중) - 안전 현황의 {@code streamingCameras}와 같은 기준이다
     * (점검 L-1, {@code liveSessionSnapshot()}).
     */
    private AiServiceState anomalyState() {
        if (!aiLiveStreamSubscriber.isConnected()) {
            return AiServiceState.DISCONNECTED;
        }
        return aiLiveStreamSubscriber.liveSessionSnapshot().isPresent() ? AiServiceState.OK : null;
    }

    /**
     * 킬 스위치({@code chat.relay.enabled=false})면 사용자 전송이 모두 503이라 AI가 살아 있어도 "정상"이 아니다 -
     * AI를 부르지 않고 DISABLED(점검 M-1). 확인 실패는 AI 장애인지 우리 경로 문제인지 모르므로 DISCONNECTED로 단정하지 않고 null.
     */
    private AiServiceState chatbotState() {
        if (!chatRelayProperties.isEnabled()) {
            return AiServiceState.DISABLED;
        }
        return aiServerHealthClient.isHealthy() ? AiServiceState.OK : null;
    }
}
