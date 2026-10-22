package kr.silverbridge.main.domain.medication.entity;

import java.math.BigDecimal;

/**
 * 복용량(정) 규칙 한 곳(2026-10-22, 반 알 지원). 0.5 단위, 0.5 이상 99 이하다(V62 CHECK와 같은 범위).
 *
 * <p>응답은 정수면 {@code 1}, 반 알이면 {@code 0.5}로 내린다 - DB의 {@code NUMERIC(3,1)}을 그대로 내리면 {@code 1.0}이 된다.
 * 사용자에게 보이는 서버 문구는 {@link #label}을 쓴다(0.5 → "반 알", 1.5 → "1정 반").</p>
 */
public final class DoseAmounts {

    public static final BigDecimal MIN = new BigDecimal("0.5");
    public static final BigDecimal MAX = new BigDecimal("99");
    /** 복용량을 생략했을 때의 기본값. 화면 스테퍼 초기값과 같다. */
    public static final BigDecimal DEFAULT = BigDecimal.ONE;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private DoseAmounts() {
    }

    /** 0.5 이상 99 이하이고 0.5의 배수인가. {@code null}은 호출부가 따로 판단한다(false). */
    public static boolean isValid(BigDecimal amount) {
        if (amount == null || amount.compareTo(MIN) < 0 || amount.compareTo(MAX) > 0) {
            return false;
        }
        return amount.multiply(TWO).stripTrailingZeros().scale() <= 0;
    }

    /** 응답용 표기 - 정수는 소수점 없이(1), 반 알은 0.5. 지수 표기(1E+1)가 나오지 않게 scale을 0 이상으로 둔다. */
    public static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** 서버가 만드는 문구용 - "반 알" / "2정" / "1정 반". */
    public static String label(BigDecimal amount) {
        BigDecimal normalized = normalize(amount);
        if (normalized.compareTo(HALF) == 0) {
            return "반 알";
        }
        BigDecimal whole = normalized.setScale(0, java.math.RoundingMode.DOWN);
        if (normalized.compareTo(whole) == 0) {
            return whole.toPlainString() + "정";
        }
        return whole.toPlainString() + "정 반";
    }
}
