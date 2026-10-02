package kr.silverbridge.main.domain.announcement.repository;

import kr.silverbridge.main.domain.announcement.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /**
     * 조회수 원자 증가. 엔티티를 읽어 +1 후 저장하면 동시 조회에서 증가분이 유실되고(read-modify-write),
     * JPA Auditing({@code @LastModifiedDate})이 수정 일시까지 바꾼다(ADMIN-G05·G06).
     * 직접 UPDATE는 Auditing을 거치지 않고, DB 트리거도 제목·내용 변경 때만 updated_at을 갱신한다(V56).
     * {@code clearAutomatically}: 이후 같은 트랜잭션의 재조회가 증가 후 값을 읽게 영속성 컨텍스트를 비운다.
     *
     * @return 갱신된 행 수(0이면 없는 공지)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Announcement a set a.viewCount = a.viewCount + 1 where a.id = :id")
    int incrementViewCount(@Param("id") Long id);
}
