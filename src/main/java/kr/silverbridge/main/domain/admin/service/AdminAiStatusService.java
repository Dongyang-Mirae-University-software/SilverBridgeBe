package kr.silverbridge.main.domain.admin.service;

import jakarta.annotation.PreDestroy;
import kr.silverbridge.main.domain.admin.client.AiServerHealthClient;
import kr.silverbridge.main.domain.admin.dto.AdminAiStatusResponse;
import kr.silverbridge.main.domain.admin.dto.AiServiceState;
import kr.silverbridge.main.domain.anomaly.client.AiLiveStreamSubscriber;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.domain.companion.client.CompanionAiStatusClient;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 관리자 대시보드 "AI 서비스 상태"(2026-10-27).
 *
 * <p><b>트랜잭션을 걸지 않는다</b> - DB를 읽지 않고, AI HTTP 호출(최대 5초)이 DB 연결을 붙들 이유가 없다.
 * 안전 현황({@code /safety})과 경로를 나눈 것도 같은 이유다: AI가 느려도 안전 현황 응답은 늦어지지 않는다.
 * 서버 캐시를 두지 않는다(대시보드 공통) - {@code checkedAt}이 곧 요청 시각이다.</p>
 *
 * <p>챗봇·말벗 확인은 각각 최대 5초라 차례로 하면 10초까지 쌓인다. 말벗 확인을 가상 스레드에서 먼저 시작하고 챗봇은 요청
 * 스레드에서 확인해 <b>최대 약 5초</b>로 맞춘다(2026-11-11). 가상 스레드 실행기는 빈이 아니라 이 서비스 안 필드다 -
 * 요청마다 1개만 쓰고 클라이언트가 동시 요청을 하나로 묶으므로 따로 상한을 두지 않는다.
 * {@code generatedAt}은 모든 칸의 확인이 끝난 뒤의 시각이다.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminAiStatusService {

    private final AiLiveStreamSubscriber aiLiveStreamSubscriber;
    private final AiServerHealthClient aiServerHealthClient;
    private final ChatRelayProperties chatRelayProperties;
    private final CompanionAiStatusClient companionAiStatusClient;
    private final CompanionProperties companionProperties;
    private final ExecutorService checkExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    void shutdown() {
        checkExecutor.shutdownNow();
    }

    public AdminAiStatusResponse getAiStatus() {
        CompletableFuture<AdminAiStatusResponse.Item> companion = startCompanionCheck();

        AiServiceState anomaly = anomalyState();
        OffsetDateTime anomalyCheckedAt = AdminDashboardClock.now();

        AiServiceState chatbot = chatbotState();
        OffsetDateTime chatbotCheckedAt = AdminDashboardClock.now();

        AdminAiStatusResponse.Item companionItem = awaitCompanion(companion);

        return new AdminAiStatusResponse(
                new AdminAiStatusResponse.Item(anomaly, anomalyCheckedAt),
                new AdminAiStatusResponse.Item(chatbot, chatbotCheckedAt),
                companionItem,
                AdminDashboardClock.now());
    }

    /**
     * 스위치({@code companion.enabled=false})가 꺼져 있으면 기기 API가 모두 503이라 AI가 살아 있어도 "정상"이 아니다 -
     * AI를 부르지 않고 DISABLED(챗봇 킬 스위치와 같은 판단). 주소·키가 비면 확인할 수 없으니 부르지 않고 null.
     * 켜져 있으면 말벗 AI를 확인하고, 실패는 DISCONNECTED로 단정하지 않고 null.
     */
    private CompletableFuture<AdminAiStatusResponse.Item> startCompanionCheck() {
        if (!companionProperties.isEnabled()) {
            return CompletableFuture.completedFuture(
                    new AdminAiStatusResponse.Item(AiServiceState.DISABLED, AdminDashboardClock.now()));
        }
        if (!companionProperties.isConfigured()) {
            return CompletableFuture.completedFuture(new AdminAiStatusResponse.Item(null, AdminDashboardClock.now()));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                AiServiceState state = companionAiStatusClient.isAvailable() ? AiServiceState.OK : null;
                return new AdminAiStatusResponse.Item(state, AdminDashboardClock.now());
            }, checkExecutor);
        } catch (RejectedExecutionException e) {
            // 종료 중 - 확인하지 못했다
            return CompletableFuture.completedFuture(new AdminAiStatusResponse.Item(null, AdminDashboardClock.now()));
        }
    }

    /** 클라이언트 자체 상한(호출 5초 + 공유 대기 여유) 뒤에도 끝나지 않으면 기다리지 않고 null(확인 불가). */
    private AdminAiStatusResponse.Item awaitCompanion(CompletableFuture<AdminAiStatusResponse.Item> companion) {
        try {
            return companion.get(companionAiStatusClient.maxWait().toMillis() + 500L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // 결과 코드는 클라이언트가 남긴다
        }
        return new AdminAiStatusResponse.Item(null, AdminDashboardClock.now());
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
