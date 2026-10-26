package kr.silverbridge.main.global.response;

/**
 * 페이지 번호 보정(2026-10-26). Spring Data는 offset(page × size)이 {@code Integer.MAX_VALUE}를 넘으면
 * {@code InvalidDataAccessApiUsageException}을 던지고, 처리하는 핸들러가 없어 500이 됐다({@code page=100000000}).
 * 넘는 값은 범위 안의 마지막 페이지로 맞춘다 - 결과는 빈 content와 정확한 전체 건수다(음수 page를 0으로 맞추는 기존 보정과 같은 방식).
 * 이 예외를 전역 400으로 바꾸지 않은 이유: 다른 코드 결함으로 생긴 같은 예외까지 400으로 가려진다.
 */
public final class PageOffsets {

    private PageOffsets() {
    }

    /**
     * @param page 요청 페이지(0부터). 음수는 0
     * @param size 이미 보정된 페이지 크기(1 이상)
     * @return offset이 int 범위를 넘지 않는 페이지 번호
     */
    public static int clampPage(int page, int size) {
        if (page <= 0) {
            return 0;
        }
        if (size <= 0) {
            return page;
        }
        return Math.min(page, Integer.MAX_VALUE / size);
    }
}
