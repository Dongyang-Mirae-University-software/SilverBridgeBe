package kr.silverbridge.main.global.enums;

/**
 * AI 이상감지 분석 결과의 감지 종류(AI {@code latest_analysis.detectedType}).
 *
 * <p><b>연기는 화재다</b>(2026-09-21) - 백엔드는 연기를 따로 구분하지 않는다. AI가 {@code smoke}를 보내도
 * {@link #FIRE}로 받아, 같은 카메라의 화재·연기 감지가 한 상황으로 묶이고 알림·이력·통계가 모두 "화재"로 나간다.
 * 과거 SMOKE 이력은 V53이 FIRE로 옮겼다. SMOKE 값을 다시 만들지 말 것.</p>
 *
 * <p>라이브 경로에 실제 탑재된 모델은 화재뿐이라 {@link #FIRE}만 이상감지 대상이다({@link #isDetectable()}).
 * {@link #FALL}·{@link #WEAPON}은 AI가 학습 중인 모델의 자리만 잡아둔 값으로, 라이브 연결 시
 * {@code isDetectable()}에 추가해야 이력·알림 대상이 된다(현재는 수신해도 무시). 흉기 모델은 AI가
 * {@code knife}로 보내므로 {@link #WEAPON}으로 받는다.</p>
 *
 * <p>{@link #NORMAL}(이상 없음)·{@link #UNKNOWN}(프레임 없음·모델 미로드·디코드 실패 = 에러 상태)은 항상 무시한다.</p>
 */
public enum DetectedType {
    FIRE,
    FALL,     // AI 학습 중 - 라이브 경로 미탑재
    WEAPON,   // AI 학습 중 - 라이브 경로 미탑재 (AI 클래스명 knife)
    NORMAL,
    UNKNOWN;

    /** 이상감지(이력 적재) 대상인지 여부. 현재 라이브에 탑재된 화재만 인정한다. */
    public boolean isDetectable() {
        return this == FIRE;
    }

    /**
     * AI가 보낸 문자열(fire/smoke/knife/normal/unknown …)을 enum으로. 모르는 값은 {@link #UNKNOWN}(무시 대상).
     *
     * <p>AI 클래스명과 우리 값이 다른 것은 여기서만 옮긴다 - {@code smoke} → {@link #FIRE}(연기는 화재),
     * {@code knife} → {@link #WEAPON}.</p>
     */
    public static DetectedType fromAi(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "SMOKE" -> FIRE;
            case "KNIFE" -> WEAPON;
            default -> {
                try {
                    yield valueOf(normalized);
                } catch (IllegalArgumentException e) {
                    yield UNKNOWN;
                }
            }
        };
    }
}
