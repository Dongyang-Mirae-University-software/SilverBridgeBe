package kr.silverbridge.main.domain.companion.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 피보호자의 대화 내용 저장 동의 (V71, 2026-11-11). 행이 있으면 동의, 철회 = 행 삭제 + 저장된 글 즉시 비움.
 * 개인정보 보호법 제23조(민감정보 별도 동의) 때문에 기본은 동의 없음이다.
 */
@Entity
@Table(name = "companion_transcript_consent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanionTranscriptConsent {

    @Id
    @Column(name = "ward_id", length = 6)
    private String wardId;

    @Column(name = "consented_at", nullable = false)
    private OffsetDateTime consentedAt;

    private CompanionTranscriptConsent(String wardId, OffsetDateTime consentedAt) {
        this.wardId = wardId;
        this.consentedAt = consentedAt;
    }

    public static CompanionTranscriptConsent of(String wardId, OffsetDateTime consentedAt) {
        return new CompanionTranscriptConsent(wardId, consentedAt);
    }
}
