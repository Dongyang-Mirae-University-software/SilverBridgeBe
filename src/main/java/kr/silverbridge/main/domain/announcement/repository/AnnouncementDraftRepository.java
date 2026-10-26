package kr.silverbridge.main.domain.announcement.repository;

import kr.silverbridge.main.domain.announcement.entity.AnnouncementDraft;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementDraftRepository extends JpaRepository<AnnouncementDraft, Long> {

    /**
     * 관리자 임시저장 목록의 제목 검색. {@code keyword}는 호출부가 소문자화·LIKE 메타문자 이스케이프를 마친 값이다
     * ({@code AnnouncementTitleKeyword}, {@code escape '\'}와 짝). 정렬은 {@link Pageable}이 정한다.
     */
    @Query(value = "select d from AnnouncementDraft d where lower(d.title) like concat('%', :keyword, '%') escape '\\'",
            countQuery = "select count(d) from AnnouncementDraft d where lower(d.title) like concat('%', :keyword, '%') escape '\\'")
    Page<AnnouncementDraft> findByTitleKeyword(@Param("keyword") String keyword, Pageable pageable);
}
