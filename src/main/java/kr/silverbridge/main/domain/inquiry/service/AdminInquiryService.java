package kr.silverbridge.main.domain.inquiry.service;

import kr.silverbridge.main.domain.inquiry.dto.AdminInquiryDetailResponse;
import kr.silverbridge.main.domain.inquiry.dto.AdminInquiryListResponse;
import kr.silverbridge.main.domain.inquiry.dto.AdminInquiryResponse;
import kr.silverbridge.main.domain.inquiry.dto.InquiryAnswerRequest;
import kr.silverbridge.main.domain.inquiry.entity.Inquiry;
import kr.silverbridge.main.domain.inquiry.event.InquiryAnsweredEvent;
import kr.silverbridge.main.domain.inquiry.repository.InquiryRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.InquiryCategory;
import kr.silverbridge.main.global.enums.InquiryStatus;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자용 문의 서비스. 전체 목록(탭 카운트·필터·검색·페이징)·상세·답변을 담당한다.
 *
 * <p>답변 등록 시 작성자(보호자)에게 답변 완료 알림(선택)을 보내기 위해 커밋 후 발행되는
 * {@link InquiryAnsweredEvent}를 publish 한다(AFTER_COMMIT 리스너가 디스패치).</p>
 */
@Service
@RequiredArgsConstructor
public class AdminInquiryService {

    private final InquiryRepository inquiryRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 관리자 문의 목록 조회. 탭 카운트(전체/대기/완료)는 필터·검색과 무관한 전역 카운트이며,
     * 목록(page)만 카테고리/상태/검색어로 필터링된다.
     */
    @Transactional(readOnly = true)
    public AdminInquiryListResponse getInquiries(InquiryCategory category, InquiryStatus status,
                                                 String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Inquiry> result = inquiryRepository.searchForAdmin(category, status, normalize(keyword), pageable);

        // 작성자명 배치 조회 (N+1 회피) — 탈퇴 작성자는 authorMap에 없어 null 처리
        Set<String> authorIds = result.getContent().stream()
                .map(Inquiry::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, User> authorMap = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        Page<AdminInquiryResponse> mapped =
                result.map(i -> AdminInquiryResponse.of(i, authorMap.get(i.getUserId())));

        long totalCount = inquiryRepository.count();
        long waitingCount = inquiryRepository.countByStatus(InquiryStatus.WAITING);
        long answeredCount = inquiryRepository.countByStatus(InquiryStatus.ANSWERED);

        return AdminInquiryListResponse.of(totalCount, waitingCount, answeredCount, PageResponse.of(mapped));
    }

    // 문의 상세 (관리자 답변 모달용 — 본문 + 기존 답변 포함)
    @Transactional(readOnly = true)
    public AdminInquiryDetailResponse getInquiry(Long inquiryId) {
        Inquiry inquiry = findInquiry(inquiryId);
        return AdminInquiryDetailResponse.of(
                inquiry, findUser(inquiry.getUserId()), findUser(inquiry.getAnsweredBy()));
    }

    /**
     * 답변 등록 (WAITING → ANSWERED). 이미 답변된 문의는 재답변을 막는다(409).
     * 커밋 후 작성자에게 답변 완료 알림(선택)이 발송된다.
     */
    @Transactional
    public AdminInquiryDetailResponse answer(Long inquiryId, InquiryAnswerRequest request, String adminId) {
        Inquiry inquiry = findInquiry(inquiryId);
        if (inquiry.isAnswered()) {
            throw new CustomException(ErrorCode.INQUIRY_ALREADY_ANSWERED);
        }

        inquiry.answer(request.getAnswer(), adminId);

        // 커밋 후 작성자(보호자)에게 답변 완료 알림 발송 (선택 알림 — 사용자 설정 따름)
        eventPublisher.publishEvent(new InquiryAnsweredEvent(inquiry.getId(), inquiry.getUserId()));

        return AdminInquiryDetailResponse.of(
                inquiry, findUser(inquiry.getUserId()), findUser(adminId));
    }

    private Inquiry findInquiry(Long inquiryId) {
        return inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new CustomException(ErrorCode.INQUIRY_NOT_FOUND));
    }

    // 사용자 조회 (탈퇴/미답변 시 null 반환)
    private User findUser(String userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).orElse(null);
    }

    // 검색어 정규화 — 공백만 있거나 빈 문자열은 "검색 안 함"(null)으로 취급
    private String normalize(String keyword) {
        if (keyword == null) return null;
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
