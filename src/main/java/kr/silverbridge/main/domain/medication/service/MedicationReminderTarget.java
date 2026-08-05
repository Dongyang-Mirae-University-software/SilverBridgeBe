package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 발송이 확정(선점)된 알림 한 건. 발송 문구를 만드는 데 필요한 값만 담는다.
 *
 * <p>선점 트랜잭션이 커밋된 뒤 발송 단계로 넘기기 위한 내부 전달 객체다 — 엔티티를 트랜잭션 밖으로
 * 들고 나가면 지연 로딩·영속성 컨텍스트에 얽히므로 값만 복사한다.</p>
 *
 * @param attempt {@code 1}=최초 발송, {@code 2}=재알림
 */
public record MedicationReminderTarget(
        Long medicationId,
        String wardId,
        String name,
        MedicationTimeSlot timeSlot,
        LocalTime doseTime,
        int doseAmount,
        LocalDate doseDate,
        int attempt
) {}
