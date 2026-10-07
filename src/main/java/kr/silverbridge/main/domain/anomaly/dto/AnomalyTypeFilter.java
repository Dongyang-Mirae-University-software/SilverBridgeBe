package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.global.enums.DetectedType;

/**
 * 보호자 이상감지 이력의 유형 필터.
 *
 * <p>{@link DetectedType}을 그대로 받지 않는 이유는 {@link AdminAnomalyTypeFilter}와 같다 - NORMAL·UNKNOWN이
 * 400이 아니라 빈 목록이 되어 "그 유형은 0건"으로 오독되는 것을 막는다. 연기는 화재로 합쳐 저장되므로 따로 없다.</p>
 */
@Schema(description = "감지 유형 필터 (생략 시 전체) - FIRE 화재(연기 포함) · FALL 낙상 · WEAPON 흉기")
public enum AnomalyTypeFilter {

    FIRE,
    FALL,
    WEAPON;

    public DetectedType toDetectedType() {
        return DetectedType.valueOf(name());
    }
}
