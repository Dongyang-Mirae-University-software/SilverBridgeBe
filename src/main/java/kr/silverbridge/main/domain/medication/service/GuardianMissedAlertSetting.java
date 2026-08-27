package kr.silverbridge.main.domain.medication.service;

import java.time.LocalTime;

/**
 * 보호자 한 명에게 실제로 적용되는 미복용 요약 설정.
 *
 * <p>저장된 행이 없거나 시각이 비어 있으면 기본값으로 채워진 <b>실효값</b>이 담긴다 -
 * 호출자가 매번 "행이 없으면 기본값" 분기를 반복하지 않게 하기 위함이다.</p>
 *
 * @param alertTime 발송 시각이자 <b>집계 상한</b>(이 시각까지 복용 시각이 지난 약만 센다)
 */
public record GuardianMissedAlertSetting(
        boolean enabled,
        LocalTime alertTime
) {}
