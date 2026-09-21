package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementResponse;
import kr.silverbridge.main.domain.admin.dto.AnnouncementCreateRequest;
import kr.silverbridge.main.domain.admin.dto.AnnouncementUpdateRequest;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AdminAuditAction;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 공지 CRUD 검증. 2026-06-11 Critical(C-S3-1)이 정확히 이 도메인에서 나왔는데 테스트가 없었다
 * (2026-09-11 회귀 재점검 R-5). 여기서 고정하는 것은 <b>쓰기 조작마다 감사 로그가 남는가</b>와
 * <b>작성자가 탈퇴해도 목록이 깨지지 않는가</b>다.
 */
@ExtendWith(MockitoExtension.class)
class AdminAnnouncementServiceTest {

    private static final String ADMIN_ID = "AD0001";

    @Mock private UserRepository userRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AdminAuditLogService auditLogService;
    @InjectMocks private AdminAnnouncementService service;

    private Announcement announcement(Long id, String authorId, String title) {
        Announcement a = Announcement.builder().authorId(authorId).title(title).content("내용").build();
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }

    private User admin() {
        return User.builder().id(ADMIN_ID).email("admin@example.com").name("관리자").role(Role.ADMIN).build();
    }

    @Test
    @DisplayName("생성 → 저장 + ANNOUNCEMENT_CREATE 감사 기록")
    void create_저장_및_감사기록() {
        AnnouncementCreateRequest request = new AnnouncementCreateRequest();
        ReflectionTestUtils.setField(request, "title", "점검 안내");
        ReflectionTestUtils.setField(request, "content", "내용");
        when(announcementRepository.save(any(Announcement.class)))
                .thenAnswer(inv -> { Announcement a = inv.getArgument(0); ReflectionTestUtils.setField(a, "id", 7L); return a; });
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin()));

        AdminAnnouncementResponse response = service.createAnnouncement(request, ADMIN_ID);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.authorName()).isEqualTo("관리자");
        verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.ANNOUNCEMENT_CREATE), eq("7"), anyString());
    }

    @Test
    @DisplayName("수정 → 제목·내용 반영 + ANNOUNCEMENT_UPDATE 감사 기록")
    void update_반영_및_감사기록() {
        Announcement target = announcement(3L, ADMIN_ID, "옛 제목");
        when(announcementRepository.findById(3L)).thenReturn(Optional.of(target));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin()));
        AnnouncementUpdateRequest request = new AnnouncementUpdateRequest();
        ReflectionTestUtils.setField(request, "title", "새 제목");
        ReflectionTestUtils.setField(request, "content", "새 내용");

        AdminAnnouncementResponse response = service.updateAnnouncement(3L, request, ADMIN_ID);

        assertThat(response.title()).isEqualTo("새 제목");
        assertThat(target.getContent()).isEqualTo("새 내용");
        verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.ANNOUNCEMENT_UPDATE), eq("3"), anyString());
    }

    @Test
    @DisplayName("삭제 → 삭제 + ANNOUNCEMENT_DELETE 감사 기록")
    void delete_삭제_및_감사기록() {
        Announcement target = announcement(5L, ADMIN_ID, "삭제 대상");
        when(announcementRepository.findById(5L)).thenReturn(Optional.of(target));

        service.deleteAnnouncement(5L, ADMIN_ID);

        verify(announcementRepository).delete(target);
        verify(auditLogService).log(eq(ADMIN_ID), eq(AdminAuditAction.ANNOUNCEMENT_DELETE), eq("5"), anyString());
    }

    @Test
    @DisplayName("없는 공지 수정·삭제 → ANNOUNCEMENT_NOT_FOUND, 감사 기록 없음")
    void 없는_공지는_404() {
        when(announcementRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteAnnouncement(99L, ADMIN_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANNOUNCEMENT_NOT_FOUND);

        verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("목록은 작성자를 한 번에 조회하고, 탈퇴한 작성자(author_id NULL)는 이름 없이 실린다")
    void 목록_작성자_배치조회_탈퇴자_null() {
        when(announcementRepository.findAll(any(Sort.class))).thenReturn(List.of(
                announcement(1L, ADMIN_ID, "첫 공지"),
                announcement(2L, null, "작성자 탈퇴 공지")));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(admin()));

        List<AdminAnnouncementResponse> result = service.getAnnouncements();

        assertThat(result).extracting(AdminAnnouncementResponse::authorName)
                .containsExactly("관리자", null);
        verify(userRepository).findAllById(anyCollection());
        verify(userRepository, never()).findById(anyString());
    }
}
