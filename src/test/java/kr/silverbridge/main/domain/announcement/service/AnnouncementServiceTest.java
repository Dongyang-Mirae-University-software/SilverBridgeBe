package kr.silverbridge.main.domain.announcement.service;

import kr.silverbridge.main.domain.announcement.dto.AnnouncementResponse;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 공개 공지 조회 검증 (2026-09-11 회귀 재점검 R-5 - 이 도메인은 테스트가 없었다). */
@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private AnnouncementService service;

    private Announcement announcement(Long id, String authorId) {
        Announcement a = Announcement.builder().authorId(authorId).title("제목").content("내용").build();
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }

    @Test
    @DisplayName("상세 조회는 조회수를 원자적 UPDATE로 올리고 엔티티는 수정하지 않는다 (ADMIN-G05·G06)")
    void 상세_조회수_원자_증가() {
        Announcement target = announcement(1L, "AD0001");
        ReflectionTestUtils.setField(target, "viewCount", 8L); // 증가 후 값을 DB에서 다시 읽은 상태
        when(announcementRepository.incrementViewCount(1L)).thenReturn(1);
        when(announcementRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRepository.findById("AD0001")).thenReturn(Optional.of(
                User.builder().id("AD0001").name("관리자").role(Role.ADMIN).build()));

        AnnouncementResponse response = service.getAnnouncement(1L);

        InOrder order = inOrder(announcementRepository);
        order.verify(announcementRepository).incrementViewCount(1L);
        order.verify(announcementRepository).findById(1L);
        // read-modify-write 경로(save)를 타지 않는다 - Auditing이 수정 일시를 바꾸고 동시 조회가 유실된다
        verify(announcementRepository, never()).save(any());
        assertThat(response.viewCount()).isEqualTo(8L);
        assertThat(response.authorName()).isEqualTo("관리자");
    }

    @Test
    @DisplayName("작성자가 탈퇴한 공지(author_id NULL)는 사용자 조회 없이 이름 null로 응답한다")
    void 탈퇴_작성자_조회_생략() {
        when(announcementRepository.incrementViewCount(2L)).thenReturn(1);
        when(announcementRepository.findById(2L)).thenReturn(Optional.of(announcement(2L, null)));

        AnnouncementResponse response = service.getAnnouncement(2L);

        assertThat(response.authorName()).isNull();
        verify(userRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("없는 공지 → ANNOUNCEMENT_NOT_FOUND")
    void 없는_공지_404() {
        when(announcementRepository.incrementViewCount(9L)).thenReturn(0);

        assertThatThrownBy(() -> service.getAnnouncement(9L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANNOUNCEMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("목록은 작성자를 배치 조회한다 - 공지마다 findById를 부르지 않는다")
    void 목록_배치조회() {
        when(announcementRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                announcement(1L, "AD0001"), announcement(2L, "AD0001"), announcement(3L, null))));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(
                User.builder().id("AD0001").name("관리자").role(Role.ADMIN).build()));

        List<AnnouncementResponse> result = service.getAnnouncements(0, 20);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(AnnouncementResponse::authorName).containsExactly("관리자", "관리자", null);
        verify(userRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("목록 페이징은 기본 20건, page<0→0, size<=0→20, size>50→50으로 보정하고 최신순이다 (ADMIN-G24)")
    void 목록_페이지_보정() {
        when(announcementRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.getAnnouncements(0, 20);
        service.getAnnouncements(-3, 0);
        service.getAnnouncements(2, 999);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(announcementRepository, org.mockito.Mockito.times(3)).findAll(captor.capture());
        List<Pageable> used = captor.getAllValues();
        assertThat(used.get(0).getPageSize()).isEqualTo(20);
        assertThat(used.get(1).getPageNumber()).isZero();
        assertThat(used.get(1).getPageSize()).isEqualTo(20);
        assertThat(used.get(2).getPageNumber()).isEqualTo(2);
        assertThat(used.get(2).getPageSize()).isEqualTo(50);
        assertThat(used.get(0).getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    @DisplayName("목록은 본문을 앞 100자(코드포인트)로 축약하고 상세는 전체를 준다 (ADMIN-G24)")
    void 목록_본문_축약() {
        String longContent = "가".repeat(99) + "😀" + "나".repeat(50);
        Announcement a = announcement(1L, "AD0001");
        ReflectionTestUtils.setField(a, "content", longContent);
        when(announcementRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(a)));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());
        when(announcementRepository.incrementViewCount(1L)).thenReturn(1);
        when(announcementRepository.findById(1L)).thenReturn(Optional.of(a));

        String listed = service.getAnnouncements(0, 20).get(0).content();

        assertThat(listed.codePointCount(0, listed.length())).isEqualTo(100);
        assertThat(listed).endsWith("😀");
        assertThat(service.getAnnouncement(1L).content()).isEqualTo(longContent);
    }
}
