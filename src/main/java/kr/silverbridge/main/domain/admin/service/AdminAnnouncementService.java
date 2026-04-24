package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AnnouncementCreateRequest;
import kr.silverbridge.main.domain.admin.dto.AnnouncementResponse;
import kr.silverbridge.main.domain.admin.dto.AnnouncementUpdateRequest;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
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
 * 관리자용 공지 관리 서비스
 * 공지 CRUD를 담당하며 모든 쓰기 작업을 감사 로그에 기록한다.
 */
@Service
@RequiredArgsConstructor
public class AdminAnnouncementService {

    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final AdminAuditLogService auditLogService;

    // 공지 목록 조회 (최신순 + 배치 작성자 조회)
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> getAnnouncements() {
        List<Announcement> announcements = announcementRepository.findAll(
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Set<String> authorIds = announcements.stream()
                .map(Announcement::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, User> authorMap = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return announcements.stream()
                .map(a -> AnnouncementResponse.of(a, authorMap.get(a.getAuthorId())))
                .toList();
    }

    // 공지 상세 조회
    @Transactional(readOnly = true)
    public AnnouncementResponse getAnnouncement(Long id) {
        Announcement announcement = findAnnouncement(id);
        return AnnouncementResponse.of(announcement, findAuthor(announcement.getAuthorId()));
    }

    // 공지 생성 (작성 즉시 게시)
    @Transactional
    public AnnouncementResponse createAnnouncement(AnnouncementCreateRequest request, String adminId) {
        Announcement announcement = Announcement.builder()
                .authorId(adminId)
                .title(request.getTitle())
                .content(request.getContent())
                .build();

        Announcement saved = announcementRepository.save(announcement);

        auditLogService.log(adminId, AdminAuditAction.ANNOUNCEMENT_CREATE, String.valueOf(saved.getId()),
                String.format("공지 생성: %s", saved.getTitle()));

        return AnnouncementResponse.of(saved, findAuthor(adminId));
    }

    // 공지 수정 (제목 + 내용)
    @Transactional
    public AnnouncementResponse updateAnnouncement(Long id, AnnouncementUpdateRequest request, String adminId) {
        Announcement announcement = findAnnouncement(id);
        announcement.update(request.getTitle(), request.getContent());

        auditLogService.log(adminId, AdminAuditAction.ANNOUNCEMENT_UPDATE, String.valueOf(id),
                String.format("공지 수정: %s", request.getTitle()));

        return AnnouncementResponse.of(announcement, findAuthor(announcement.getAuthorId()));
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
