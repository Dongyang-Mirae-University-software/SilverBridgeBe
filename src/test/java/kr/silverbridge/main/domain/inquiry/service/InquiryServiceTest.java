package kr.silverbridge.main.domain.inquiry.service;

import kr.silverbridge.main.domain.inquiry.dto.InquiryCreateRequest;
import kr.silverbridge.main.domain.inquiry.dto.InquiryResponse;
import kr.silverbridge.main.domain.inquiry.entity.Inquiry;
import kr.silverbridge.main.domain.inquiry.repository.InquiryRepository;
import kr.silverbridge.main.global.enums.InquiryCategory;
import kr.silverbridge.main.global.enums.InquiryStatus;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보호자용 InquiryService 단위 테스트.
 * 작성 시 상태 초기화, 본인 문의 조회, 타인 문의 접근 차단(IDOR)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class InquiryServiceTest {

    @Mock private InquiryRepository inquiryRepository;

    @InjectMocks private InquiryService inquiryService;

    private static final String GUARDIAN_ID = "GD0001";
    private static final String OTHER_ID = "GD0002";
    private static final long INQUIRY_ID = 1L;

    @Test
    @DisplayName("문의 작성 → 작성자·카테고리·내용 저장 + 상태 WAITING 초기화")
    void create_상태WAITING로_저장() {
        InquiryCreateRequest request = new InquiryCreateRequest();
        ReflectionTestUtils.setField(request, "category", InquiryCategory.SERVICE);
        ReflectionTestUtils.setField(request, "title", "제목");
        ReflectionTestUtils.setField(request, "content", "내용");
        when(inquiryRepository.save(any(Inquiry.class))).thenAnswer(inv -> inv.getArgument(0));

        InquiryResponse response = inquiryService.create(GUARDIAN_ID, request);

        ArgumentCaptor<Inquiry> captor = ArgumentCaptor.forClass(Inquiry.class);
        verify(inquiryRepository).save(captor.capture());
        Inquiry saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(GUARDIAN_ID);
        assertThat(saved.getCategory()).isEqualTo(InquiryCategory.SERVICE);
        assertThat(saved.getStatus()).isEqualTo(InquiryStatus.WAITING);
        assertThat(response.status()).isEqualTo(InquiryStatus.WAITING);
        assertThat(response.title()).isEqualTo("제목");
    }

    @Test
    @DisplayName("내 문의 목록 조회 → 본인 것만 최신순으로 매핑")
    void getMyInquiries_본인것만_매핑() {
        Inquiry inquiry = inquiry(GUARDIAN_ID, InquiryStatus.WAITING);
        when(inquiryRepository.findByUserIdOrderByCreatedAtDesc(GUARDIAN_ID))
                .thenReturn(List.of(inquiry));

        List<InquiryResponse> result = inquiryService.getMyInquiries(GUARDIAN_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(INQUIRY_ID);
    }

    @Test
    @DisplayName("내 문의 상세 조회(본인) → 반환")
    void getMyInquiry_본인_반환() {
        Inquiry inquiry = inquiry(GUARDIAN_ID, InquiryStatus.ANSWERED);
        when(inquiryRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(inquiry));

        InquiryResponse response = inquiryService.getMyInquiry(GUARDIAN_ID, INQUIRY_ID);

        assertThat(response.id()).isEqualTo(INQUIRY_ID);
        assertThat(response.status()).isEqualTo(InquiryStatus.ANSWERED);
    }

    @Test
    @DisplayName("타인 문의 상세 조회 → INQUIRY_NOT_AUTHORIZED(404 위장, IDOR 차단)")
    void getMyInquiry_타인것_차단() {
        Inquiry inquiry = inquiry(OTHER_ID, InquiryStatus.WAITING);
        when(inquiryRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(inquiry));

        assertThatThrownBy(() -> inquiryService.getMyInquiry(GUARDIAN_ID, INQUIRY_ID))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_NOT_AUTHORIZED);
    }

    @Test
    @DisplayName("존재하지 않는 문의 상세 조회 → INQUIRY_NOT_FOUND")
    void getMyInquiry_없음_404() {
        when(inquiryRepository.findById(INQUIRY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inquiryService.getMyInquiry(GUARDIAN_ID, INQUIRY_ID))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_NOT_FOUND);
    }

    private Inquiry inquiry(String userId, InquiryStatus status) {
        return Inquiry.builder()
                .id(INQUIRY_ID)
                .userId(userId)
                .category(InquiryCategory.SERVICE)
                .title("제목")
                .content("내용")
                .status(status)
                .build();
    }
}
