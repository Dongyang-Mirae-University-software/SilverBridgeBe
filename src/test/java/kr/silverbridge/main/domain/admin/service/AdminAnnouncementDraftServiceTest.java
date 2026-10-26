package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AnnouncementDraftCreateRequest;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.announcement.entity.AnnouncementDraft;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementDraftRepository;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.audit.AdminAuditLogService;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    @DisplayName("NBSP·제로폭 문자만 있는 제목의 임시저장은 게시할 수 없다 (ADMIN-G22)")
    void publishDraft_보이지_않는_문자만_차단() {
        AnnouncementDraft invisible = draft("\u00A0\u200B", "내용");
        when(draftRepository.findById(5L)).thenReturn(Optional.of(invisible));

        assertThatThrownBy(() -> service.publishDraft(5L, "AD0001"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(announcementRepository, org.mockito.Mockito.never()).save(any(Announcement.class));
    }

    @Test
    @DisplayName("임시저장은 정리된 값으로 저장하고, 목록은 본문을 앞 100자로 축약한다 (ADMIN-G22·G24)")
    void 정리_저장_목록_축약() {
        when(draftRepository.save(any(AnnouncementDraft.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById("AD0001")).thenReturn(Optional.empty());
        AnnouncementDraftCreateRequest request = new AnnouncementDraftCreateRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(request, "title", "제\u200B목");
        org.springframework.test.util.ReflectionTestUtils.setField(request, "content", "x".repeat(150));

        assertThat(service.createDraft(request, "AD0001").title()).isEqualTo("제목");

        when(draftRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(draft("제목", "y".repeat(150)))));
        when(userRepository.findAllById(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());

        assertThat(service.getDrafts(-1, 0, null).content().get(0).content()).hasSize(100);
    }

    @Test
    @DisplayName("임시저장 목록도 제목 검색·전체 건수를 준다 - 검색어 규칙은 공지 목록과 같다 (2026-10-26)")
    void 목록_제목검색_건수() {
        when(draftRepository.findByTitleKeyword(eq("점검"), any(org.springframework.data.domain.Pageable.class)))
                .thenAnswer(inv -> new org.springframework.data.domain.PageImpl<>(
                        List.of(draft("서버 점검", "내용")), inv.getArgument(1), 21));
        when(userRepository.findAllById(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());

        var result = service.getDrafts(0, 20, " 점검 ");

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(draftRepository).findByTitleKeyword(eq("점검"), captor.capture());
        // 검색 경로도 생성 일시 내림차순 + id 내림차순 (2026-10-26 점검 L-4)
        assertThat(captor.getValue().getSort()).isEqualTo(
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")
                        .and(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")));

        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
        verify(draftRepository, never()).findAll(any(org.springframework.data.domain.Pageable.class));
        assertThatThrownBy(() -> service.getDrafts(0, 20, "a".repeat(51)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    }
}
