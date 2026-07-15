package kr.silverbridge.main.domain.inquiry.repository;

import kr.silverbridge.main.domain.inquiry.entity.Inquiry;
import kr.silverbridge.main.global.enums.InquiryCategory;
import kr.silverbridge.main.global.enums.InquiryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    // 보호자 본인 문의 목록 (최신순)
    List<Inquiry> findByUserIdOrderByCreatedAtDesc(String userId);

    // 관리자 탭 카운트 — 상태별 전체 건수 (필터·검색과 무관한 전역 카운트)
    long countByStatus(InquiryStatus status);

    /**
     * 관리자 목록 조회 — 카테고리/상태 필터 + 제목·내용·작성자명 검색 + 페이징.
     *
     * <p>작성자명(User.name)으로도 검색하기 위해 User와 조인한다(Inquiry는 userId만 저장하므로
     * 연관 매핑 대신 명시적 조인). 각 조건은 null이면 무시된다(동적 필터).</p>
     */
    @Query("""
            SELECT i FROM Inquiry i, User u
            WHERE u.id = i.userId
              AND (:category IS NULL OR i.category = :category)
              AND (:status IS NULL OR i.status = :status)
              AND (:keyword IS NULL OR :keyword = ''
                   OR LOWER(i.title) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\'
                   OR LOWER(i.content) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\'
                   OR LOWER(u.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\')
            """)
    Page<Inquiry> searchForAdmin(@Param("category") InquiryCategory category,
                                 @Param("status") InquiryStatus status,
                                 @Param("keyword") String keyword,
                                 Pageable pageable);
}
