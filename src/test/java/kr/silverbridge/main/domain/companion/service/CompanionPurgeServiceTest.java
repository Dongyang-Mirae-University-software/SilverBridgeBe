package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.client.AiCompanionClient;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionPurgeRequest;
import kr.silverbridge.main.domain.companion.repository.CompanionPurgeRequestRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 탈퇴·역할 변경 AI 데이터 삭제 재시도 - 순서·첫 실패 중단·실패 기록(2026-11-04 점검 L-2). */
class CompanionPurgeServiceTest {

    private AiCompanionClient aiClient;
    private CompanionPurgeRequestRepository repository;
    private CompanionPurgeService service;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiCompanionClient.class);
        repository = mock(CompanionPurgeRequestRepository.class);
        CompanionProperties properties = new CompanionProperties();
        properties.setAiBaseUrl("http://ai.test");
        properties.setApiKey("key");
        service = new CompanionPurgeService(aiClient, properties, repository);
    }

    @Test
    @DisplayName("재시도는 시도 안 한 것·오래 전 시도한 것 순서로 돌고, 첫 실패에서 멈추며 실패 횟수를 남긴다")
    void retryOrderAndStopOnFirstFailure() {
        CompanionPurgeRequest first = CompanionPurgeRequest.of("W00001", OffsetDateTime.now());
        CompanionPurgeRequest second = CompanionPurgeRequest.of("W00002", OffsetDateTime.now());
        when(repository.findRetryOrder(any())).thenReturn(List.of(first, second));
        when(repository.findById("W00001")).thenReturn(Optional.of(first));
        doThrow(new CustomException(ErrorCode.COMPANION_UNAVAILABLE)).when(aiClient).deleteWardData("W00001");

        service.retryPending();

        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastAttemptAt()).isNotNull();
        verify(aiClient, never()).deleteWardData("W00002");
        verify(repository, never()).deleteById("W00001");
    }

    @Test
    @DisplayName("성공하면 대기열에서 지운다")
    void successRemovesRequest() {
        assertThat(service.tryPurge("W00003")).isTrue();
        verify(repository).deleteById("W00003");
    }
}
