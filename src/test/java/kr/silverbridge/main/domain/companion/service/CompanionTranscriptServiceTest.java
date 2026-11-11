package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TranscriptConsentResponse;
import kr.silverbridge.main.domain.companion.entity.CompanionTranscriptConsent;
import kr.silverbridge.main.domain.companion.repository.CompanionTranscriptConsentRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 대화 내용 저장 동의·철회·30일 보관 (2026-11-11). */
class CompanionTranscriptServiceTest {

    private static final String W = "CW0001";
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 11, 18, 0, 0, 0, ZoneOffset.ofHours(9));

    private CompanionTranscriptConsentRepository consentRepository;
    private CompanionTurnRepository turnRepository;
    private CompanionProperties properties;
    private CompanionTranscriptService service;

    @BeforeEach
    void setUp() {
        consentRepository = mock(CompanionTranscriptConsentRepository.class);
        turnRepository = mock(CompanionTurnRepository.class);
        properties = new CompanionProperties();
        service = new CompanionTranscriptService(consentRepository, turnRepository, properties);
        service.setClock(Clock.fixed(NOW.toInstant(), ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("기본은 동의 없음 - consented=false, 보관 일수 30")
    void defaultNoConsent() {
        when(consentRepository.findById(W)).thenReturn(Optional.empty());

        TranscriptConsentResponse response = service.getConsent(W);

        assertThat(response.consented()).isFalse();
        assertThat(response.consentedAt()).isNull();
        assertThat(response.retentionDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("점검 L-3: 응답에 고지 4항목(목적·항목·보유 기간·거부 권리)이 순서대로, 보관 일수는 설정값으로 실린다")
    void noticeItems() {
        when(consentRepository.findById(W)).thenReturn(Optional.empty());
        properties.setTranscriptRetentionDays(14);

        List<String> notice = service.getConsent(W).notice();

        assertThat(notice).hasSize(4);
        assertThat(notice.get(0)).startsWith("이용 목적");
        assertThat(notice.get(1)).startsWith("저장 항목").contains("음성과 사진은 저장하지 않습니다", "민감한 내용");
        assertThat(notice.get(2)).startsWith("보관 기간").contains("14일 뒤 지웁니다", "바로 지웁니다");
        assertThat(notice.get(3)).startsWith("동의하지 않을 권리").contains("그대로 쓸 수 있습니다");
    }

    @Test
    @DisplayName("동의: 없을 때만 넣는다(처음 동의 시각 유지) - 글은 비우지 않는다")
    void consentInsertsIfAbsent() {
        when(consentRepository.findById(W)).thenReturn(Optional.of(CompanionTranscriptConsent.of(W, NOW.minusDays(2))));

        TranscriptConsentResponse response = service.setConsent(W, true);

        verify(consentRepository).insertIfAbsent(W, NOW);
        verify(turnRepository, never()).clearTextByWardId(any());
        assertThat(response.consented()).isTrue();
        assertThat(response.consentedAt()).isEqualTo(NOW.minusDays(2));
    }

    @Test
    @DisplayName("철회: 동의 행을 먼저 지우고(턴 저장 잠금과 맞물림) 그 피보호자의 글을 즉시 비운다")
    void withdrawClearsTextImmediately() {
        TranscriptConsentResponse response = service.setConsent(W, false);

        InOrder order = inOrder(consentRepository, turnRepository);
        order.verify(consentRepository).deleteByWardId(W);
        order.verify(turnRepository).clearTextByWardId(W);
        assertThat(response.consented()).isFalse();
    }

    @Test
    @DisplayName("30일 정리: 지금 - 30일보다 오래된 턴의 글을 비운다. 0 이하면 아무것도 하지 않는다")
    void clearExpired() {
        when(turnRepository.clearTextCreatedBefore(NOW.minusDays(30))).thenReturn(3);

        assertThat(service.clearExpired()).isEqualTo(3);

        properties.setTranscriptRetentionDays(0);
        assertThat(service.clearExpired()).isZero();
        verify(turnRepository).clearTextCreatedBefore(any());
    }
}
