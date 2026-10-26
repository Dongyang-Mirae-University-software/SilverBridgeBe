package kr.silverbridge.main.global.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 큰 page 보정(2026-10-26). offset(page × size)이 int를 넘으면 Spring Data가 예외를 던져 500이었다.
 */
class PageOffsetsTest {

    @Test
    @DisplayName("음수·0은 0, 범위 안은 그대로")
    void 기본() {
        assertThat(PageOffsets.clampPage(-5, 20)).isZero();
        assertThat(PageOffsets.clampPage(0, 20)).isZero();
        assertThat(PageOffsets.clampPage(3, 20)).isEqualTo(3);
    }

    @Test
    @DisplayName("offset이 int를 넘는 page는 offset이 int 범위 안인 마지막 페이지로 맞춘다 (size 1·20·50)")
    void 경계() {
        for (int size : new int[]{1, 20, 50}) {
            int max = Integer.MAX_VALUE / size;

            assertThat(PageOffsets.clampPage(max, size)).isEqualTo(max);
            if (max < Integer.MAX_VALUE) { // size 1이면 max + 1이 int를 넘는다
                assertThat(PageOffsets.clampPage(max + 1, size)).isEqualTo(max);
            }
            assertThat(PageOffsets.clampPage(Integer.MAX_VALUE, size)).isEqualTo(max);
            assertThat(PageRequest.of(PageOffsets.clampPage(Integer.MAX_VALUE, size), size).getOffset())
                    .isLessThanOrEqualTo(Integer.MAX_VALUE);
        }
        assertThat(PageOffsets.clampPage(100_000_000, 50)).isEqualTo(Integer.MAX_VALUE / 50);
    }
}
