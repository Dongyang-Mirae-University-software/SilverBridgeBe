package kr.silverbridge.main.domain.inquiry.service;

import kr.silverbridge.main.domain.inquiry.dto.AdminInquiryDetailResponse;
import kr.silverbridge.main.domain.inquiry.dto.AdminInquiryListResponse;
import kr.silverbridge.main.domain.inquiry.dto.InquiryAnswerRequest;
import kr.silverbridge.main.domain.inquiry.entity.Inquiry;
import kr.silverbridge.main.domain.inquiry.event.InquiryAnsweredEvent;
import kr.silverbridge.main.domain.inquiry.repository.InquiryRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.InquiryCategory;
import kr.silverbridge.main.global.enums.InquiryStatus;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자용 AdminInquiryService 단위 테스트.
 * 목록 조회(탭 카운트·작성자명 매핑·필터 위임), 답변(상태 전환·이벤트 발행), 재답변 차단을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminInquiryServiceTest {

    @Mock private InquiryRepository inquiryRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private AdminInquiryService adminInquiryService;

    private static final String GUARDIAN_ID = "GD0001";
    private static final String ADMIN_ID = "AD0001";
    private static final long INQUIRY_ID = 1L;

    @Test
    @DisplayName("목록 조회 → 필터·검색을 repository에 위임하고 전역 탭 카운트 + 작성자명을 채워 반환")
    void getInquiries_탭카운트_작성자명_매핑() {
        Inquiry inquiry = inquiry(INQUIRY_ID, GUARDIAN_ID, InquiryStatus.WAITING);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Inquiry> page = new PageImpl<>(List.of(inquiry), pageable, 1);
        when(inquiryRepository.searchForAdmin(eq(InquiryCategory.SERVICE), eq(InquiryStatus.WAITING),
                eq("김보호"), any(Pageable.class))).thenReturn(page);
        when(userRepository.findAllById(any())).thenReturn(List.of(user(GUARDIAN_ID, "김보호")));
        when(inquiryRepository.count()).thenReturn(10L);
        when(inquiryRepository.countByStatus(InquiryStatus.WAITING)).thenReturn(3L);
        when(inquiryRepository.countByStatus(InquiryStatus.ANSWERED)).thenReturn(7L);

        AdminInquiryListResponse result = adminInquiryService.getInquiries(
                InquiryCategory.SERVICE, InquiryStatus.WAITING, "김보호", 0, 20);

        assertThat(result.totalCount()).isEqualTo(10L);
        assertThat(result.waitingCount()).isEqualTo(3L);
        assertThat(result.answeredCount()).isEqualTo(7L);
        assertThat(result.inquiries().content()).hasSize(1);
        assertThat(result.inquiries().content().get(0).authorName()).isEqualTo("김보호");
        assertThat(result.inquiries().totalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("공백 검색어는 null로 정규화되어 repository에 전달된다")
    void getInquiries_공백검색어_null정규화() {
        Pageable pageable = PageRequest.of(0, 20);
        when(inquiryRepository.searchForAdmin(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));
        when(userRepository.findAllById(any())).thenReturn(List.of());

        adminInquiryService.getInquiries(null, null, "   ", 0, 20);

        verify(inquiryRepository).searchForAdmin(eq(null), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    @DisplayName("답변 작성 → WAITING→ANSWERED 전환 + 답변자 기록 + InquiryAnsweredEvent 발행")
    void answer_상태전환_이벤트발행() {
        Inquiry inquiry = inquiry(INQUIRY_ID, GUARDIAN_ID, InquiryStatus.WAITING);
        when(inquiryRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(inquiry));
        when(userRepository.findById(GUARDIAN_ID)).thenReturn(Optional.of(user(GUARDIAN_ID, "김보호")));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(user(ADMIN_ID, "관리자")));
        InquiryAnswerRequest request = answerRequest("확인 후 조치했습니다.");

        AdminInquiryDetailResponse response = adminInquiryService.answer(INQUIRY_ID, request, ADMIN_ID);

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(inquiry.getAnsweredBy()).isEqualTo(ADMIN_ID);
        assertThat(inquiry.getAnswer()).isEqualTo("확인 후 조치했습니다.");
        assertThat(response.answeredByName()).isEqualTo("관리자");
        assertThat(response.answer()).isEqualTo("확인 후 조치했습니다.");

        ArgumentCaptor<InquiryAnsweredEvent> captor = ArgumentCaptor.forClass(InquiryAnsweredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().inquiryId()).isEqualTo(INQUIRY_ID);
        assertThat(captor.getValue().authorUserId()).isEqualTo(GUARDIAN_ID);
    }

    @Test
    @DisplayName("이미 답변된 문의 재답변 → INQUIRY_ALREADY_ANSWERED, 상태·이벤트 변화 없음")
    void answer_이미답변됨_409() {
        Inquiry inquiry = inquiry(INQUIRY_ID, GUARDIAN_ID, InquiryStatus.ANSWERED);
        when(inquiryRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(inquiry));
        InquiryAnswerRequest request = answerRequest("두 번째 답변");

        assertThatThrownBy(() -> adminInquiryService.answer(INQUIRY_ID, request, ADMIN_ID))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_ALREADY_ANSWERED);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("답변 대상 문의 없음 → INQUIRY_NOT_FOUND")
    void answer_문의없음_404() {
        when(inquiryRepository.findById(INQUIRY_ID)).thenReturn(Optional.empty());
        InquiryAnswerRequest request = answerRequest("답변");

        assertThatThrownBy(() -> adminInquiryService.answer(INQUIRY_ID, request, ADMIN_ID))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_NOT_FOUND);
    }

    private Inquiry inquiry(long id, String userId, InquiryStatus status) {
        return Inquiry.builder()
                .id(id)
                .userId(userId)
                .category(InquiryCategory.SERVICE)
                .title("제목")
                .content("내용")
                .status(status)
                .build();
    }

    private User user(String id, String name) {
        return User.builder()
                .id(id).email(id + "@example.com").name(name)
                .role(Role.GUARDIAN).status(Status.ACTIVE).provider(Provider.LOCAL)
                .build();
    }

    private InquiryAnswerRequest answerRequest(String answer) {
        InquiryAnswerRequest request = new InquiryAnswerRequest();
        ReflectionTestUtils.setField(request, "answer", answer);
        return request;
    }
}
