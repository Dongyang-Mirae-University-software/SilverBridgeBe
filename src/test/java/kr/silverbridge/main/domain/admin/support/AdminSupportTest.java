package kr.silverbridge.main.domain.admin.support;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import kr.silverbridge.main.domain.admin.dto.AdminUserUpdateRequest;
import kr.silverbridge.main.domain.admin.dto.AnnouncementCreateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** 관리자 공용 유틸·검증(페이지 보정, 목록 본문 축약, 코드포인트 길이·보이지 않는 문자 검증) - ADMIN-G09·G22·G24. */
class AdminSupportTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("page<0 → 0, size<=0 → 20, size>50 → 50, 그 사이는 그대로")
    void 페이지_보정_기준() {
        assertThat(AdminPaging.page(-5)).isZero();
        assertThat(AdminPaging.page(3)).isEqualTo(3);
        assertThat(AdminPaging.size(0)).isEqualTo(20);
        assertThat(AdminPaging.size(-1)).isEqualTo(20);
        assertThat(AdminPaging.size(1)).isEqualTo(1);
        assertThat(AdminPaging.size(50)).isEqualTo(50);
        assertThat(AdminPaging.size(51)).isEqualTo(50);
        assertThat(AdminPaging.of(-1, 1000).getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("목록 본문 축약은 코드포인트 기준 100자이고 이모지를 반으로 자르지 않는다")
    void 본문_축약() {
        String text = "a".repeat(99) + "😀" + "b".repeat(10);

        String cut = TextSummary.forList(text);

        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(100);
        assertThat(cut).endsWith("😀");
        assertThat(TextSummary.forList("짧은 글")).isEqualTo("짧은 글");
        assertThat(TextSummary.forList(null)).isNull();
    }

    @Test
    @DisplayName("공지 제목은 이모지 200개까지 허용(코드포인트 기준)하고 201개는 거절한다")
    void 이모지_코드포인트_길이() {
        assertThat(validator.validate(announcement("😀".repeat(200), "내용"))).isEmpty();
        assertThat(validator.validate(announcement("😀".repeat(201), "내용"))).isNotEmpty();
    }

    @Test
    @DisplayName("NBSP·제로폭 문자·전각공백만 있는 공지 제목·내용은 거절한다")
    void 보이지_않는_문자만_있는_값_거절() {
        assertThat(validator.validate(announcement(" ​　", "내용"))).isNotEmpty();
        assertThat(validator.validate(announcement("제목", "​ "))).isNotEmpty();
        assertThat(validator.validate(announcement("제목", "본문"))).isEmpty();
    }

    @Test
    @DisplayName("회원 이름은 null(변경 안 함)은 통과, 20자 초과·보이지 않는 문자만은 거절, 이모지는 1자로 센다")
    void 회원_이름_검증() {
        assertThat(validator.validate(new AdminUserUpdateRequest(null, null, null, null))).isEmpty();
        assertThat(validator.validate(new AdminUserUpdateRequest("😀".repeat(20), null, null, null))).isEmpty();
        assertThat(validator.validate(new AdminUserUpdateRequest("가".repeat(21), null, null, null))).isNotEmpty();
        assertThat(validator.validate(new AdminUserUpdateRequest("​ ", null, null, null))).isNotEmpty();
    }

    private AnnouncementCreateRequest announcement(String title, String content) {
        AnnouncementCreateRequest request = new AnnouncementCreateRequest();
        ReflectionTestUtils.setField(request, "title", title);
        ReflectionTestUtils.setField(request, "content", content);
        return request;
    }
}
