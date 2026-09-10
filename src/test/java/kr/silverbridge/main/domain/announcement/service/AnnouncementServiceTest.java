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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
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
    @DisplayName("상세 조회는 조회수를 1 올린다")
    void 상세_조회수_증가() {
        Announcement target = announcement(1L, "AD0001");
        when(announcementRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRepository.findById("AD0001")).thenReturn(Optional.of(
                User.builder().id("AD0001").name("관리자").role(Role.ADMIN).build()));

        AnnouncementResponse first = service.getAnnouncement(1L);
        AnnouncementResponse second = service.getAnnouncement(1L);

        assertThat(first.viewCount()).isEqualTo(1L);
        assertThat(second.viewCount()).isEqualTo(2L);
        assertThat(second.authorName()).isEqualTo("관리자");
    }

    @Test
    @DisplayName("작성자가 탈퇴한 공지(author_id NULL)는 사용자 조회 없이 이름 null로 응답한다")
    void 탈퇴_작성자_조회_생략() {
        when(announcementRepository.findById(2L)).thenReturn(Optional.of(announcement(2L, null)));

        AnnouncementResponse response = service.getAnnouncement(2L);

        assertThat(response.authorName()).isNull();
        verify(userRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("없는 공지 → ANNOUNCEMENT_NOT_FOUND")
    void 없는_공지_404() {
        when(announcementRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAnnouncement(9L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANNOUNCEMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("목록은 작성자를 배치 조회한다 - 공지마다 findById를 부르지 않는다")
    void 목록_배치조회() {
        when(announcementRepository.findAll(any(Sort.class))).thenReturn(List.of(
                announcement(1L, "AD0001"), announcement(2L, "AD0001"), announcement(3L, null)));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(
                User.builder().id("AD0001").name("관리자").role(Role.ADMIN).build()));

        List<AnnouncementResponse> result = service.getAnnouncements();

        assertThat(result).hasSize(3);
        assertThat(result).extracting(AnnouncementResponse::authorName).containsExactly("관리자", "관리자", null);
        verify(userRepository, never()).findById(anyString());
    }
}
