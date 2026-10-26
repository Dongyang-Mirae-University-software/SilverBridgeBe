package kr.silverbridge.main.domain.announcement.repository;

import kr.silverbridge.main.domain.announcement.entity.AnnouncementDraft;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementDraftRepository extends JpaRepository<AnnouncementDraft, Long> {

    /**
     * 관리자 임시저장 목록의 제목 검색. {@code keyword}는 호출부가 LIKE 메타문자 이스케이프를 마친 값이다
     * ({@code AnnouncementTitleKeyword}, {@code escape '\'}와 짝). 대소문자는 양쪽 모두 DB {@code lower()}로 맞춘다
     * (Java 소문자화와 DB가 어긋나는 글자가 있다). 정렬은 {@link Pageable}이 정한다.
     * 제목 {@code %..%} LIKE는 인덱스를 쓰지 못해 전체를 훑는다 - 공지가 수천 건 이상이 되면 {@code pg_trgm} GIN 인덱스를 검토한다(2026-10-26 점검 L-3).
     */
    @Query(value = "select d from AnnouncementDraft d where lower(d.title) like lower(concat('%', :keyword, '%')) escape '\\'",
            countQuery = "select count(d) from AnnouncementDraft d where lower(d.title) like lower(concat('%', :keyword, '%')) escape '\\'")
    Page<AnnouncementDraft> findByTitleKeyword(@Param("keyword") String keyword, Pageable pageable);
}
