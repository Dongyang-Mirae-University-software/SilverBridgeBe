package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TranscriptConsentResponse;
import kr.silverbridge.main.domain.companion.entity.CompanionTranscriptConsent;
import kr.silverbridge.main.domain.companion.repository.CompanionTranscriptConsentRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionTurnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 대화 내용 저장 동의·보관 (2026-11-11 사용자 결정). 개인정보 보호법 제23조(민감정보 별도 동의)·제21조(보유기간 경과 시 파기).
 *
 * <ul>
 *   <li>동의: 행 1개(처음 동의 시각 유지). 그 뒤의 턴부터 말벗 문장·대답 글을 저장한다(지난 턴은 채우지 않음).</li>
 *   <li>철회: 행 삭제 + 그 피보호자의 저장된 글 즉시 비움(같은 트랜잭션). 기분은 남는다.</li>
 *   <li>보관: {@code transcriptRetentionDays}(기본 30일)가 지난 턴의 글을 매일 비운다.</li>
 * </ul>
 * 로그에는 건수만 남긴다(글 금지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionTranscriptService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CompanionTranscriptConsentRepository consentRepository;
    private final CompanionTurnRepository turnRepository;
    private final CompanionProperties properties;

    private Clock clock = Clock.system(KST);

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TranscriptConsentResponse getConsent(String wardId) {
        return toResponse(consentRepository.findById(wardId).map(CompanionTranscriptConsent::getConsentedAt).orElse(null));
    }

    @Transactional
    public TranscriptConsentResponse setConsent(String wardId, boolean consent) {
        if (consent) {
            consentRepository.insertIfAbsent(wardId, OffsetDateTime.now(clock));
            log.info("[COMPANION-TRANSCRIPT] 대화 내용 저장 동의: wardId={}", wardId);
            return getConsent(wardId);
        }
        withdraw(wardId);
        return toResponse(null);
    }

    /** 철회 - 행을 먼저 지운다(턴 저장의 FOR SHARE와 부딪히면 그 커밋을 기다린다) → 그 뒤 글 비우기가 새로 저장된 글까지 본다. */
    private void withdraw(String wardId) {
        int deleted = consentRepository.deleteByWardId(wardId);
        int cleared = turnRepository.clearTextByWardId(wardId);
        if (deleted > 0 || cleared > 0) {
            log.info("[COMPANION-TRANSCRIPT] 대화 내용 저장 동의 철회 - 저장된 글 비움: wardId={}, turns={}", wardId, cleared);
        }
    }

    /** 30일 보관 정리. 스케줄러가 부른다. */
    @Transactional
    public int clearExpired() {
        int days = properties.getTranscriptRetentionDays();
        if (days <= 0) {
            return 0;
        }
        return turnRepository.clearTextCreatedBefore(OffsetDateTime.now(clock).minusDays(days));
    }

    private TranscriptConsentResponse toResponse(OffsetDateTime consentedAt) {
        int days = properties.getTranscriptRetentionDays();
        return new TranscriptConsentResponse(consentedAt != null, consentedAt, days, notice(days));
    }

    /**
     * 동의 고지 4항목(2026-11-12 점검 L-3) - 화면마다 문구가 달라지지 않게 서버가 내려 준다. 보관 일수는 설정값을 쓴다.
     * 항목을 줄이거나 "저장하지 않는다"류 문구로 바꾸지 말 것(동의 근거가 된다).
     */
    static List<String> notice(int retentionDays) {
        return List.of(
                "이용 목적: 연결된 보호자가 어르신의 안부 대화 내용을 확인할 수 있게 합니다.",
                "저장 항목: 말벗이 한 말과 어르신의 대답 글(음성으로 답하면 받아 적은 글)입니다. 건강 상태 등 민감한 내용이 "
                        + "들어갈 수 있습니다. 음성과 사진은 저장하지 않습니다.",
                "보관 기간: 저장한 날부터 " + retentionDays + "일 뒤 지웁니다. 동의를 거두면 저장한 내용을 바로 지웁니다.",
                "동의하지 않을 권리: 동의하지 않아도 안부 대화는 그대로 쓸 수 있습니다. 이 경우 보호자에게는 대화 내용 없이 "
                        + "기분만 보입니다.");
    }
}
