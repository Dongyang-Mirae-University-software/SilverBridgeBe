package kr.silverbridge.main.domain.admin.support;

import kr.silverbridge.main.global.response.PageOffsets;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * 관리자 목록 4종(회원·문의·이상감지·알림)의 page/size 보정 기준을 한 곳에 둔다.
 * 기준: page &lt; 0 → 0, size &lt;= 0 → 기본 20, size &gt; 50 → 50,
 * offset(page × size)이 int 범위를 넘는 page → 범위 안의 마지막 페이지({@link PageOffsets}, 2026-10-26 - 넘으면 500이었다).
 * 목록마다 기준이 달라(0→1로 보정하거나 20으로 보정) 같은 요청이 화면마다 다른 크기로 응답하던 문제(ADMIN-G09)를 막는다.
 */
public final class AdminPaging {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    private AdminPaging() {
    }

    public static int page(int page) {
        return Math.max(page, 0);
    }

    public static int size(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static PageRequest of(int page, int size) {
        int pageSize = size(size);
        return PageRequest.of(PageOffsets.clampPage(page(page), pageSize), pageSize);
    }

    public static PageRequest of(int page, int size, Sort sort) {
        int pageSize = size(size);
        return PageRequest.of(PageOffsets.clampPage(page(page), pageSize), pageSize, sort);
    }
}
