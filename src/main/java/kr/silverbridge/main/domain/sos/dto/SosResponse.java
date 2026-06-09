package kr.silverbridge.main.domain.sos.dto;

import java.time.OffsetDateTime;

/**
 * SOS 발생 응답. 저장된 이력 ID와 발생 시각을 반환한다.
 *
 * @param sosEventId 저장된 sos_events 행 ID
 * @param triggeredAt SOS 발생(이력 생성) 시각
 */
public record SosResponse(
        Long sosEventId,
        OffsetDateTime triggeredAt
) {}
