package kr.silverbridge.main.domain.announcement;

import kr.silverbridge.main.domain.admin.support.AnnouncementTitleKeyword;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.announcement.entity.AnnouncementDraft;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementDraftRepository;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementRepository;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 공지·임시저장 제목 검색 JPQL을 실제 PostgreSQL에서 확인한다(2026-10-26).
 *
 * <p>목 테스트는 {@code escape '\'}·{@code lower()}·countQuery를 실행하지 않는다. 검색어는 서비스와 같은
 * {@link AnnouncementTitleKeyword#normalize}를 거쳐 넘긴다 - %·_ 가 와일드카드로 새지 않고 글자 그대로 찾는지,
 * 대소문자를 무시하는지, 전체 건수가 검색 결과 기준인지를 본다.</p>
 */
class AnnouncementTitleSearchIntegrationTest extends PostgresIntegrationTest {

    private static final Sort LATEST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private AnnouncementDraftRepository draftRepository;

    @Test
    @DisplayName("공지 제목 검색: 부분 일치·대소문자 무시, %·_·\\ 는 글자 그대로, 건수는 검색 결과 기준")
    void 공지_제목검색() {
        save("Server 점검 안내");
        save("server 정기 점검");
        save("할인 100% 이벤트");
        save("할인 1000 이벤트");
        save("a_b 공지");
        save("axb 공지");
        save("경로 C:\\temp 안내");

        assertThat(titles(search("SERVER", 0, 20))).containsExactlyInAnyOrder("Server 점검 안내", "server 정기 점검");
        assertThat(titles(search("100%", 0, 20))).containsExactly("할인 100% 이벤트");
        assertThat(titles(search("a_b", 0, 20))).containsExactly("a_b 공지");
        assertThat(titles(search("C:\\temp", 0, 20))).containsExactly("경로 C:\\temp 안내");
        assertThat(search("없는 제목", 0, 20).getTotalElements()).isZero();

        Page<Announcement> paged = search("점검", 0, 1);
        assertThat(paged.getContent()).hasSize(1);
        assertThat(paged.getTotalElements()).isEqualTo(2);
        assertThat(paged.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("임시저장 제목 검색: 같은 규칙이며 제목이 빈 임시저장은 걸리지 않는다")
    void 임시저장_제목검색() {
        draftRepository.saveAndFlush(AnnouncementDraft.builder().title("설 연휴 휴무").content("").build());
        draftRepository.saveAndFlush(AnnouncementDraft.builder().title("").content("휴무 내용만 있음").build());

        Page<AnnouncementDraft> result = draftRepository.findByTitleKeyword(
                AnnouncementTitleKeyword.normalize("휴무"), PageRequest.of(0, 20, LATEST));

        assertThat(result.getContent()).extracting(AnnouncementDraft::getTitle).containsExactly("설 연휴 휴무");
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    private void save(String title) {
        announcementRepository.saveAndFlush(Announcement.builder().title(title).content("내용").build());
    }

    private Page<Announcement> search(String keyword, int page, int size) {
        return announcementRepository.findByTitleKeyword(
                AnnouncementTitleKeyword.normalize(keyword), PageRequest.of(page, size, LATEST));
    }

    private static java.util.List<String> titles(Page<Announcement> page) {
        return page.getContent().stream().map(Announcement::getTitle).toList();
    }
}
