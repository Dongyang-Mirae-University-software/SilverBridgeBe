package kr.silverbridge.main.domain.inquiry.service;

import kr.silverbridge.main.domain.inquiry.dto.InquiryCreateRequest;
import kr.silverbridge.main.domain.inquiry.dto.InquiryResponse;
import kr.silverbridge.main.domain.inquiry.entity.Inquiry;
import kr.silverbridge.main.domain.inquiry.repository.InquiryRepository;
import kr.silverbridge.main.global.enums.InquiryStatus;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 보호자용 문의 서비스. 작성·본인 목록·본인 상세를 담당한다.
 *
 * <p>조회는 모두 "본인 문의만" 검증한다(IDOR 차단) — 타인 문의는 존재 노출 방지를 위해
 * {@link ErrorCode#INQUIRY_NOT_AUTHORIZED}(404 위장)로 응답한다.</p>
 */
@Service
@RequiredArgsConstructor
public class InquiryService {

    private final InquiryRepository inquiryRepository;

    // 문의 작성 (작성자 = 보호자, 최초 상태 WAITING)
    @Transactional
    public InquiryResponse create(String userId, InquiryCreateRequest request) {
        Inquiry inquiry = Inquiry.builder()
                .userId(userId)
                .category(request.getCategory())
                .title(request.getTitle())
                .content(request.getContent())
                .status(InquiryStatus.WAITING)
                .build();

        return InquiryResponse.of(inquiryRepository.save(inquiry));
    }

    // 내 문의 목록 (본인 것만, 최신순)
    @Transactional(readOnly = true)
    public List<InquiryResponse> getMyInquiries(String userId) {
        return inquiryRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(InquiryResponse::of)
                .toList();
    }

    // 내 문의 상세 (본인 것만)
    @Transactional(readOnly = true)
    public InquiryResponse getMyInquiry(String userId, Long inquiryId) {
        return InquiryResponse.of(getOwnedInquiry(userId, inquiryId));
    }

    // 조회 + 소유권 검증 (없으면 404, 타인 것이면 404 위장으로 IDOR 차단)
    private Inquiry getOwnedInquiry(String userId, Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new CustomException(ErrorCode.INQUIRY_NOT_FOUND));
        if (!inquiry.getUserId().equals(userId)) {
            throw new CustomException(ErrorCode.INQUIRY_NOT_AUTHORIZED);
        }
        return inquiry;
    }
}
