package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.global.enums.DetectedType;

/**
 * 관리자 이상감지 로그의 유형 필터.
 *
 * <p><b>{@link DetectedType}을 그대로 받지 않는 이유</b> - 전체 enum을 열면 {@code NORMAL}·{@code UNKNOWN}처럼
 * 이력에 절대 쌓이지 않는 값이 유효해져 400이 아니라 <b>빈 목록</b>으로 응답되고, "그 유형은 0건"으로 오독된다
 * ({@code WardListFilter}·{@code AdminUserStatusFilter}와 같은 판단). 연기는 화재로 받으므로 따로 없다.</p>
 */
@Schema(description = "감지 유형 필터 (생략 시 전체) - FIRE 화재(연기 포함) · FALL 낙상 · WEAPON 흉기")
public enum AdminAnomalyTypeFilter {

    FIRE,
    FALL,
    WEAPON;

    public DetectedType toDetectedType() {
        return DetectedType.valueOf(name());
    }
}
