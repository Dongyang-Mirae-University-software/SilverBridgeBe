package kr.silverbridge.main.domain.inquiry.service;

import kr.silverbridge.main.domain.inquiry.dto.InquiryCreateRequest;
import kr.silverbridge.main.domain.inquiry.dto.InquiryResponse;
import kr.silverbridge.main.domain.inquiry.entity.Inquiry;
import kr.silverbridge.main.domain.inquiry.repository.InquiryRepository;
import kr.silverbridge.main.global.enums.InquiryStatus;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 보호자용 문의 서비스. 작성·본인 목록·본인 상세를 담당한다.
 *
 * <p>조회는 모두 "본인 문의만" 검증한다(IDOR 차단) — 타인 문의는 존재 노출 방지를 위해
 * {@link ErrorCode#INQUIRY_NOT_AUTHORIZED}(404 위장)로 응답한다.</p>
 */
@Slf4j
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

    /**
     * 조회 + 소유권 검증. 없으면 404, <b>타인 것이면 403 + 명시적 안내</b>.
     *
     * <p>이전에는 타인 문의를 404로 위장했으나(존재 노출 차단), "왜 안 보이지"로 이탈하는 시니어 UX를 우선해
     * 무슨 일이 일어났는지 그대로 알린다(2026-07-14 정책 — 비밀번호 재설정과 같은 판단). 노출되는 정보는
     * "그 번호의 문의가 존재한다"는 사실뿐이고 내용은 주지 않으며, 시도는 WARN으로 남긴다.</p>
     */
    private Inquiry getOwnedInquiry(String userId, Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new CustomException(ErrorCode.INQUIRY_NOT_FOUND));
        if (!inquiry.getUserId().equals(userId)) {
            log.warn("[IDOR-ATTEMPT] 타인 문의 접근 시도: userId={}, inquiryId={}", userId, inquiryId);
            throw new CustomException(ErrorCode.INQUIRY_NOT_AUTHORIZED);
        }
        return inquiry;
    }
}
