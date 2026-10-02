package kr.silverbridge.main.domain.announcement;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementRepository;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공지 조회수 증가가 수정 일시를 바꾸지 않는가 (ADMIN-G05·G06, V56).
 *
 * <p>목 테스트는 DB 트리거({@code trg_announcements_updated_at})를 실행하지 않는다. 조회수만 바뀐 UPDATE는
 * {@code updated_at}을 유지하고, 제목·내용이 바뀐 UPDATE는 갱신해야 한다. 증가 쿼리는 원자적이라
 * 기존 값에 더해진다.</p>
 */
class AnnouncementViewCountIntegrationTest extends PostgresIntegrationTest {

    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("조회수 증가는 updated_at을 유지하고, 제목 수정은 updated_at을 갱신한다")
    void 조회수는_수정일시를_바꾸지_않는다() {
        Long id = announcementRepository.saveAndFlush(
                Announcement.builder().title("제목").content("내용").build()).getId();
        // 수정 일시를 과거로 고정해 둔다 - 트리거가 NOW()로 덮으면 바로 드러난다
        // (제목·내용이 안 바뀐 UPDATE라 V56 트리거는 발동하지 않는다)
        entityManager.createNativeQuery(
                "UPDATE announcement SET updated_at = TIMESTAMPTZ '2026-01-01 00:00:00+09' WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        entityManager.clear();
        double before = updatedAt(id);

        assertThat(announcementRepository.incrementViewCount(id)).isEqualTo(1);
        assertThat(announcementRepository.incrementViewCount(id)).isEqualTo(1);

        assertThat(updatedAt(id)).isEqualTo(before);
        assertThat(announcementRepository.findById(id).orElseThrow().getViewCount()).isEqualTo(2L);

        entityManager.createNativeQuery("UPDATE announcement SET title = '새 제목' WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        assertThat(updatedAt(id)).isGreaterThan(before);
    }

    @Test
    @DisplayName("없는 공지의 조회수 증가는 0행이다")
    void 없는_공지는_0행() {
        assertThat(announcementRepository.incrementViewCount(-1L)).isZero();
    }

    private double updatedAt(Long id) {
        // 드라이버별 시각 타입 차이를 피하려고 epoch 초(double)로 받는다
        return ((Number) entityManager
                .createNativeQuery("SELECT EXTRACT(EPOCH FROM updated_at)::float8 FROM announcement WHERE id = :id")
                .setParameter("id", id).getSingleResult()).doubleValue();
    }
}
