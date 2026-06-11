package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AnnouncementDraftCreateRequest;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.announcement.entity.AnnouncementDraft;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementDraftRepository;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAnnouncementDraftServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AnnouncementDraftRepository draftRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AdminAuditLogService auditLogService;

    @InjectMocks private AdminAnnouncementDraftService service;

    private AnnouncementDraft draft(String title, String content) {
        return AnnouncementDraft.builder().authorId("AD0001").title(title).content(content).build();
    }

    @Test
    @DisplayName("임시저장 생성 → 저장 + ANNOUNCEMENT_DRAFT_CREATE 감사 기록 (C-S3-1 회귀 가드)")
    void createDraft_저장_및_감사기록() {
        when(draftRepository.save(any(AnnouncementDraft.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById("AD0001")).thenReturn(Optional.empty());

        service.createDraft(new AnnouncementDraftCreateRequest(), "AD0001");

        verify(auditLogService).log(eq("AD0001"), eq(AdminAuditAction.ANNOUNCEMENT_DRAFT_CREATE),
                anyString(), anyString());
    }

    @Test
    @DisplayName("임시저장 게시 → 공지 저장 + draft 삭제 + ANNOUNCEMENT_DRAFT_PUBLISH 감사 기록")
    void publishDraft_게시_및_감사기록() {
        AnnouncementDraft saved = draft("점검 안내", "내용");
        when(draftRepository.findById(1L)).thenReturn(Optional.of(saved));
        when(announcementRepository.save(any(Announcement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById("AD0001")).thenReturn(Optional.empty());

        service.publishDraft(1L, "AD0001");

        verify(announcementRepository).save(any(Announcement.class));
        verify(draftRepository).delete(saved);
        verify(auditLogService).log(eq("AD0001"), eq(AdminAuditAction.ANNOUNCEMENT_DRAFT_PUBLISH),
                anyString(), anyString());
    }

    @Test
    @DisplayName("제목/내용이 빈 임시저장 게시 → INVALID_INPUT, 공지 미생성·draft 보존")
    void publishDraft_빈내용_차단() {
        AnnouncementDraft empty = draft(null, "내용만 있음");
        when(draftRepository.findById(2L)).thenReturn(Optional.of(empty));

        assertThatThrownBy(() -> service.publishDraft(2L, "AD0001"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(announcementRepository, never()).save(any());
        verify(draftRepository, never()).delete(any());
    }

    @Test
    @DisplayName("임시저장 삭제 → ANNOUNCEMENT_DRAFT_DELETE 감사 기록")
    void deleteDraft_감사기록() {
        AnnouncementDraft target = draft("삭제 대상", "내용");
        when(draftRepository.findById(3L)).thenReturn(Optional.of(target));

        service.deleteDraft(3L, "AD0001");

        verify(draftRepository).delete(target);
        verify(auditLogService).log(eq("AD0001"), eq(AdminAuditAction.ANNOUNCEMENT_DRAFT_DELETE),
                anyString(), anyString());
    }
}
