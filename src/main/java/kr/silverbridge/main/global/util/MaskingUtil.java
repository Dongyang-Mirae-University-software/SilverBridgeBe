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