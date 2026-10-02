package kr.silverbridge.main.domain.announcement.service;

import kr.silverbridge.main.domain.announcement.dto.AnnouncementResponse;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
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
 * 일반 사용자용 공지 조회 서비스 (읽기 전용)
 * 쓰기 작업은 AdminAnnouncementService가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;

    // 공지 목록 조회 (최신순 + 작성자 이름 배치 조회)
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

    // 공지 상세 조회 (조회 시 조회수 +1)
    @Transactional
    public AnnouncementResponse getAnnouncement(Long id) {
        // 조회수는 원자적 UPDATE로 올린다 - 엔티티를 고치면 동시 조회에서 증가분이 유실되고 수정 일시도 바뀐다(ADMIN-G05·G06).
        if (announcementRepository.incrementViewCount(id) == 0) {
            throw new CustomException(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        }
        // 증가 후 값을 응답에 싣는다(기존 동작과 동일). 쿼리가 영속성 컨텍스트를 비우므로 DB 값을 새로 읽는다.
        Announcement announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
        User author = announcement.getAuthorId() == null
                ? null
                : userRepository.findById(announcement.getAuthorId()).orElse(null);
        return AnnouncementResponse.of(announcement, author);
    }
}
