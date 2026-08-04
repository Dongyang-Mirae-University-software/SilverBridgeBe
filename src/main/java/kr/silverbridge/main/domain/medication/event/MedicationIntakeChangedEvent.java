package kr.silverbridge.main.domain.medication.event;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 피보호자가 복용을 체크하거나 해제했을 때 발행된다. 보호자 화면을 실시간으로 갱신하기 위한 신호다.
 *
 * <p>상태가 실제로 바뀐 경우에만 발행한다 — 이미 체크된 약을 다시 체크하거나 체크되지 않은 약을 해제하는
 * 중복 요청에서는 발행하지 않는다(같은 알림이 두 번 가지 않게).</p>
 *
 * @param taken   true면 복용 체크, false면 체크 해제
 * @param takenAt 체크 시각. 해제 시에는 null
 */
public record MedicationIntakeChangedEvent(
        Long medicationId,
        String wardId,
        String medicationName,
        LocalDate doseDate,
        boolean taken,
        OffsetDateTime takenAt
) {}
