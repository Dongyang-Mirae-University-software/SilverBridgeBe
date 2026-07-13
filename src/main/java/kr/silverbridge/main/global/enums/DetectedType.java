package kr.silverbridge.main.global.enums;

/**
 * AI 이상감지 분석 결과의 감지 종류(AI {@code latest_analysis.detectedType}).
 *
 * <p>라이브 경로에 실제 탑재된 모델은 화재/연기뿐이라 {@link #FIRE}·{@link #SMOKE}만 이상감지 대상이다
 * ({@link #isDetectable()}). {@link #FALL}·{@link #WEAPON}은 AI가 학습 중인 모델의 자리만 잡아둔 값으로,
 * 라이브 연결 시 {@code isDetectable()}에 추가해야 이력·알림 대상이 된다(현재는 수신해도 무시).</p>
 *
 * <p>{@link #NORMAL}(이상 없음)·{@link #UNKNOWN}(프레임 없음·모델 미로드·디코드 실패 = 에러 상태)은 항상 무시한다.</p>
 */
public enum DetectedType {
    FIRE,
    SMOKE,
    FALL,     // AI 학습 중 — 라이브 경로 미탑재
    WEAPON,   // AI 학습 중 — 라이브 경로 미탑재
    NORMAL,
    UNKNOWN;

    /** 이상감지(이력 적재) 대상인지 여부. 현재 라이브에 탑재된 화재/연기만 인정한다. */
    public boolean isDetectable() {
        return this == FIRE || this == SMOKE;
    }

    /** AI가 보낸 문자열(fire/smoke/normal/unknown …)을 enum으로. 모르는 값은 {@link #UNKNOWN}(무시 대상). */
    public static DetectedType fromAi(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
