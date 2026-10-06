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
            case FIRE -> "화재";      // 연기도 화재로 받는다(DetectedType.fromAi)
            case FALL -> "낙상";
            case WEAPON -> "흉기";
            default -> "이상 상황";
        };
    }

    /** 주격 조사까지 붙인 표기("화재가"·"낙상이"·"흉기가") - 받침 유무로 가/이를 고른다. */
    public static String withSubjectParticle(DetectedType detectedType) {
        String label = of(detectedType);
        char last = label.charAt(label.length() - 1);
        boolean hangul = last >= '가' && last <= '힣';
        boolean hasBatchim = hangul && (last - '가') % 28 != 0;
        return label + (hasBatchim ? "이" : "가");
    }

    /**
     * 실시간 분석 상태 표시용(보호자 실시간 카메라 보기). 알림 문구({@link #of})와 달리 정상·확인 불가도 그대로 보여준다 -
     * 매 순간의 상태를 그리는 화면이라 "이상 상황"으로 뭉뚱그리면 정상 화면에 경고처럼 뜬다.
     */
    public static String ofLive(DetectedType detectedType) {
        return switch (detectedType) {
            case NORMAL -> "정상";
            case UNKNOWN -> "확인 불가";   // 프레임 없음·모델 미로드·디코드 실패
            default -> of(detectedType);
        };
    }
}
