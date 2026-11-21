package kr.silverbridge.main.global.util;

/**
 * 알림·문자 문구에서 어르신(피보호자)을 부르는 말.
 *
 * <p>화면 용어는 "어르신"으로 통일했다(2026-11-22 사용자 결정). 이름이 있으면 "홍길동님", 없으면(탈퇴·조회 실패 등)
 * 대체 이름 "어르신"만 쓴다 - 대체 이름에 "님"을 붙이면 "어르신님"이 된다. 둘 다 받침으로 끝나서 뒤에 붙는
 * 조사(이·의·과·을)는 같다.
 */
public final class ElderName {

    /** 이름을 알 수 없을 때 쓰는 대체 이름. */
    public static final String FALLBACK = "어르신";

    private ElderName() {
    }

    /** "홍길동" → "홍길동님", 비었거나 대체 이름이면 "어르신". */
    public static String honorific(String name) {
        if (name == null || name.isBlank() || FALLBACK.equals(name.strip())) {
            return FALLBACK;
        }
        return name + "님";
    }
}
