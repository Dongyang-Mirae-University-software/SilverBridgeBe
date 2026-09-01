package kr.silverbridge.main.domain.anomaly.dto;

import kr.silverbridge.main.global.enums.DetectedType;

/**
 * 감지 종류의 표시 문구.
 *
 * <p>{@link DetectedType}은 AI 계약을 표현하는 값이라 UI 문자열을 담지 않는다. 그렇다고 문구를 쓰는 쪽마다
 * 따로 두면 알림에는 "화재", 이력 화면에는 "불"처럼 갈라진다 - 같은 사건을 두 이름으로 부르게 되므로
 * 한 곳에 모은다.</p>
 */
public final class DetectedTypeLabel {

    private DetectedTypeLabel() {
    }

    /** 시니어/4050 대상이라 완곡어법 없이 그대로 부른다(설계 D-3). */
    public static String of(DetectedType detectedType) {
        return switch (detectedType) {
            case FIRE -> "화재";
            case SMOKE -> "연기";
            default -> "이상 상황";
        };
    }
}
