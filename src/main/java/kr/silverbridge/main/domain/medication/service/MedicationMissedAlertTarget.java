package kr.silverbridge.main.domain.medication.service;

import java.time.LocalDate;

/**
 * 발송이 확정(선점)된 미복용 요약 한 건 — 보호자 1명 × 피보호자 1명.
 *
 * <p>선점 트랜잭션이 커밋된 뒤 발송 단계로 넘기기 위한 내부 전달 객체다.</p>
 *
 * @param wardName    문구에 쓰는 피보호자 이름(조회 실패 시 폴백 문구로 대체)
 * @param missedCount 체크되지 않은 약 수
 * @param totalCount  판정 시각까지 예정된 약 수
 */
public record MedicationMissedAlertTarget(
        String guardianId,
        String wardId,
        String wardName,
        LocalDate doseDate,
        int missedCount,
        int totalCount
) {}
