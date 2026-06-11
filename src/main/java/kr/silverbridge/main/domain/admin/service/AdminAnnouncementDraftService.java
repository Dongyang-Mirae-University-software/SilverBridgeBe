package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementDraftResponse;
import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementResponse;
import kr.silverbridge.main.domain.admin.dto.AnnouncementDraftCreateRequest;
import kr.silverbridge.main.domain.admin.dto.AnnouncementDraftUpdateRequest;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.announcement.entity.AnnouncementDraft;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementDraftRepository;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자용 공지 임시저장 서비스
 * 게시 전 상태의 공지(announcement_drafts)를 관리한다.
 * 게시 시 announcements 로 이동하고 draft 행은 삭제된다.
 */
@Service
@RequiredArgsConstructor
public class AdminAnnouncementDraftService {

    private final UserRepository userRepository;
    private final AnnouncementDraftRepository draftRepository;
    private final AnnouncementRepository announcementRepository;
    private final AdminAuditLogService auditLogService;

    // 임시저장 목록 조회 (최신순 + 배치 작성자 조회)
    @Transactional(readOnly = true)
    public List<AdminAnnouncementDraftResponse> getDrafts() {
        List<AnnouncementDraft> drafts = draftRepository.findAll(
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Set<String> authorIds = drafts.stream()
                .map(AnnouncementDraft::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, User> authorMap = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return drafts.stream()
                .map(d -> AdminAnnouncementDraftResponse.of(d, authorMap.get(d.getAuthorId())))
                .toList();
    }

    // 임시저장 상세 조회
    @Transactional(readOnly = true)
    public AdminAnnouncementDraftResponse getDraft(Long id) {
        AnnouncementDraft draft = findDraft(id);
        return AdminAnnouncementDraftResponse.of(draft, findAuthor(draft.getAuthorId()));
    }

    // 임시저장 생성
    // title/content는 "작성 중"이라 비어 있을 수 있지만 DB 컬럼은 NOT NULL이므로 null만 빈 문자열로 정규화 (L-S3-2)
    @Transactional
    public AdminAnnouncementDraftResponse createDraft(AnnouncementDraftCreateRequest request, String adminId) {
        AnnouncementDraft draft = AnnouncementDraft.builder()
                .authorId(adminId)
                .title(nullToEmpty(request.getTitle()))
                .content(nullToEmpty(request.getContent()))
                .build();

        AnnouncementDraft saved = draftRepository.save(draft);

        auditLogService.log(adminId, AdminAuditAction.ANNOUNCEMENT_DRAFT_CREATE, String.valueOf(saved.getId()),
                String.format("공지 임시저장 생성: %s", safeTitle(saved.getTitle())));

        return AdminAnnouncementDraftResponse.of(saved, findAuthor(adminId));
    }

    // 임시저장 수정
    @Transactional
    public AdminAnnouncementDraftResponse updateDraft(Long id, AnnouncementDraftUpdateRequest request, String adminId) {
        AnnouncementDraft draft = findDraft(id);
        draft.update(nullToEmpty(request.getTitle()), nullToEmpty(request.getContent()));

        auditLogService.log(adminId, AdminAuditAction.ANNOUNCEMENT_DRAFT_UPDATE, String.valueOf(id),
                String.format("공지 임시저장 수정: %s", safeTitle(request.getTitle())));

        return AdminAnnouncementDraftResponse.of(draft, findAuthor(draft.getAuthorId()));
    }

    // 임시저장 삭제
    @Transactional
    public void deleteDraft(Long id, String adminId) {
        AnnouncementDraft draft = findDraft(id);
        String title = draft.getTitle();
        draftRepository.delete(draft);

        auditLogService.log(adminId, AdminAuditAction.ANNOUNCEMENT_DRAFT_DELETE, String.valueOf(id),
                String.format("공지 임시저장 삭제: %s", safeTitle(title)));
    }

    // 임시저장 → 공지 게시 (draft 행 삭제 후 announcements 에 새로 저장)
    @Transactional
    public AdminAnnouncementResponse publishDraft(Long id, String adminId) {
        AnnouncementDraft draft = findDraft(id);

        if (draft.getTitle() == null || draft.getTitle().isBlank()
                || draft.getContent() == null || draft.getContent().isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }

        Announcement announcement = Announcement.builder()
                .authorId(adminId)
                .title(draft.getTitle())
                .content(draft.getContent())
                .build();
        Announcement saved = announcementRepository.save(announcement);
        draftRepository.delete(draft);

        auditLogService.log(adminId, AdminAuditAction.ANNOUNCEMENT_DRAFT_PUBLISH, String.valueOf(saved.getId()),
                String.format("공지 임시저장 게시: %s (draftId=%d)", saved.getTitle(), id));

        return AdminAnnouncementResponse.of(saved, findAuthor(adminId));
    }

    private AnnouncementDraft findDraft(Long id) {
        return draftRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.ANNOUNCEMENT_DRAFT_NOT_FOUND));
    }

    private User findAuthor(String authorId) {
        if (authorId == null) return null;
        return userRepository.findById(authorId).orElse(null);
    }

    private String safeTitle(String title) {
        return (title == null || title.isBlank()) ? "(제목 없음)" : title;
    }

    // DB NOT NULL 컬럼 보호 — null 전송 시 23502(500) 대신 빈 문자열로 저장 (L-S3-2)
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
