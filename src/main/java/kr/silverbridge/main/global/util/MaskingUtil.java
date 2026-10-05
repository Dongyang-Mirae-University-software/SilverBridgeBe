package kr.silverbridge.main.global.util;

/**
 * 로그 및 응답용 개인정보 마스킹 유틸리티
 */
public final class MaskingUtil {

    private MaskingUtil() {}

    /**
     * 전화번호 마스킹
     * 예: 01012345678 → 010****5678
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 이름 마스킹 - 첫 글자와 (3자 이상이면) 마지막 글자만 남긴다.
     * 예: 홍길동 → 홍*동, 남궁민수 → 남**수, 홍길 → 홍*, 홍 → *
     *
     * <p>글자 단위는 코드포인트다(이모지 등 보조 문자가 반쪽으로 잘리지 않게). 앞뒤 공백은 무시한다.</p>
     */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) return "***";
        int[] cps = name.strip().codePoints().toArray();
        int n = cps.length;
        if (n == 1) return "*";
        StringBuilder sb = new StringBuilder().appendCodePoint(cps[0]);
        if (n == 2) {
            return sb.append('*').toString();
        }
        sb.append("*".repeat(n - 2));
        return sb.appendCodePoint(cps[n - 1]).toString();
    }

    /**
     * 이메일 마스킹
     * 예: username@example.com → us***me@example.com  (5자 이상: 앞 2자 + *** + 뒤 2자)
     *     user@example.com    → u***r@example.com     (3~4자: 앞 1자 + *** + 뒤 1자)
     *     ab@example.com      → a***@example.com      (2자 이하: 앞 1자 + ***)
     */
    public static String maskEmail(String email) {
        if (email == null) return "***";
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) return "***";

        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);

        if (local.length() >= 5) {
            return local.substring(0, 2) + "***" + local.substring(local.length() - 2) + domain;
        } else if (local.length() >= 3) {
            return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
        } else {
            return local.charAt(0) + "***" + domain;
        }
    }
}