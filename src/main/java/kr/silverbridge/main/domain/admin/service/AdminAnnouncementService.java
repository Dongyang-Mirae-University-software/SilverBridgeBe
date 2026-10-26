package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AnnouncementCreateRequest;
import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementResponse;
import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementSummaryResponse;
import kr.silverbridge.main.domain.admin.dto.AnnouncementUpdateRequest;
import kr.silverbridge.main.domain.admin.support.AdminPaging;
import kr.silverbridge.main.domain.admin.support.AnnouncementTitleKeyword;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementDraftRepository;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.audit.AdminAuditLogService;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import kr.silverbridge.main.global.validation.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자용 공지 관리 서비스
 * 공지 CRUD를 담당하며 모든 쓰기 작업을 감사 로그에 기록한다.
 */
@Service
@RequiredArgsConstructor
public class AdminAnnouncementService {

    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final AnnouncementDraftRepository draftRepository;
    private final AdminAuditLogService auditLogService;

    // 공지 목록 조회 (최신순 + 배치 작성자 조회, 페이징·본문 축약 - ADMIN-G24, 제목 검색·건수 - 2026-10-26)
    @Transactional(readOnly = true)
    public PageResponse<AdminAnnouncementResponse> getAnnouncements(int page, int size, String keyword) {
        // 검색어 검사(50자 초과 400)를 DB 조회보다 먼저 한다
        String titleKeyword = AnnouncementTitleKeyword.normalize(keyword);
        // id 내림차순을 함께 둬 같은 시각의 공지가 페이지 사이에서 겹치거나 빠지지 않게 한다
        Pageable pageable = AdminPaging.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Announcement> announcements = titleKeyword == null
                ? announcementRepository.findAll(pageable)
                : announcementRepository.findByTitleKeyword(titleKeyword, pageable);

        Set<String> authorIds = announcements.stream()
                .map(Announcement::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, User> authorMap = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return PageResponse.of(announcements.map(a -> AdminAnnouncementResponse.ofSummary(a, authorMap.get(a.getAuthorId()))));
    }

    // 탭 건수 (검색어와 무관한 전체 기준)
    @Transactional(readOnly = true)
    public AdminAnnouncementSummaryResponse getSummary() {
        return new AdminAnnouncementSummaryResponse(announcementRepository.count(), draftRepository.count());
    }

    // 공지 상세 조회
    @Transactional(readOnly = true)
    public AdminAnnouncementResponse getAnnouncement(Long id) {
        Announcement announcement = findAnnouncement(id);
        return AdminAnnouncementResponse.of(announcement, findAuthor(announcement.getAuthorId()));
    }

    // 공지 생성 (작성 즉시 게시)
    @Transactional
    public AdminAnnouncementResponse createAnnouncement(AnnouncementCreateRequest request, String adminId) {
        Announcement announcement = Announcement.builder()
                .authorId(adminId)
                .title(TextSanitizer.sanitize(request.getTitle()))
                .content(TextSanitizer.sanitizeMultiline(request.getContent()))
                .build();

        Announcement saved = announcementRepository.save(announcement);

        auditLogService.log(adminId, AdminAuditAction.ANNOUNCEMENT_CREATE, String.valueOf(saved.getId()),
                String.format("공지 생성: %s", saved.getTitle()));

        return AdminAnnouncementResponse.of(saved, findAuthor(adminId));
    }

    // 공지 수정 (제목 + 내용)
    @Transactional
    public AdminAnnouncementResponse updateAnnouncement(Long id, AnnouncementUpdateRequest request, String adminId) {
        Announcement announcement = findAnnouncement(id);
        announcement.update(TextSanitizer.sanitize(request.getTitle()),
                TextSanitizer.sanitizeMultiline(request.getContent()));

        auditLogService.log(adminId, AdminAuditAction.ANNOUNCEMENT_UPDATE, String.valueOf(id),
                String.format("공지 수정: %s", announcement.getTitle()));

        return AdminAnnouncementResponse.of(announcement, findAuthor(announcement.getAuthorId()));
    }

    // 공지 삭제
    @Transactional
    public void deleteAnnouncement(Long id, String adminId) {
        Announcement announcement = findAnnouncement(id);
        String title = announcement.getTitle();
        announcementRepository.delete(announcement);

        auditLogService.log(adminId, AdminAuditAction.ANNOUNCEMENT_DELETE, String.valueOf(id),
                String.format("공지 삭제: %s", title));
    }

    // 공지 조회 (없으면 예외)
    private Announcement findAnnouncement(Long id) {
        return announcementRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
    }

    // 공지 작성자 조회 (탈퇴 시 null 반환)
    private User findAuthor(String authorId) {
        if (authorId == null) return null;
        return userRepository.findById(authorId).orElse(null);
    }
}
