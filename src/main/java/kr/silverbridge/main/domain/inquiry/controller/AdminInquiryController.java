package kr.silverbridge.main.domain.inquiry.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.inquiry.dto.AdminInquiryDetailResponse;
import kr.silverbridge.main.domain.inquiry.dto.AdminInquiryListResponse;
import kr.silverbridge.main.domain.inquiry.dto.InquiryAnswerRequest;
import kr.silverbridge.main.domain.inquiry.service.AdminInquiryService;
import kr.silverbridge.main.global.enums.InquiryCategory;
import kr.silverbridge.main.global.enums.InquiryStatus;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자용 고객센터 문의 API. 전체 목록(탭 카운트·필터·검색·페이징)·상세·답변.
 * {@code /api/admin/**} 경로는 SecurityConfig에서 ADMIN 권한이 강제된다(경로 매칭).
 */
@Tag(name = "관리자 - 문의")
@RestController
@RequestMapping("/api/admin/inquiry")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminInquiryController {

    private final AdminInquiryService adminInquiryService;

    @Operation(summary = "문의 목록 조회 (탭 카운트 + 필터 + 검색 + 페이징)",
            description = """
                    전체 문의를 최신순으로 조회합니다.

                    [탭 카운트]
                    totalCount/waitingCount/answeredCount 는 필터·검색과 무관한 전역 카운트입니다(탭 배지용).

                    [필터·검색 (목록에만 적용)]
                    - category: ANOMALY/HOSPITAL/ACCOUNT/SERVICE/ETC (미지정 시 전체)
                    - status: WAITING/ANSWERED (미지정 시 전체)
                    - keyword: 제목·내용·작성자명 부분 일치 (미지정 시 전체)

                    [페이징]
                    - page: 0-based (기본 0), size: 페이지 크기 (기본 20)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "문의 목록 + 탭 카운트 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 필요", content = @Content)
    })
    @GetMapping
    public ApiResponse<AdminInquiryListResponse> getInquiries(
            @Parameter(description = "카테고리 필터") @RequestParam(required = false) InquiryCategory category,
            @Parameter(description = "상태 필터") @RequestParam(required = false) InquiryStatus status,
            @Parameter(description = "검색어 (제목·내용·작성자명)") @RequestParam(required = false) String keyword,
            @Parameter(description = "페이지 번호 (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminInquiryService.getInquiries(category, status, keyword, page, size));
    }

    @Operation(summary = "문의 상세 조회", description = "문의 ID로 단건 상세(본문 + 기존 답변)를 조회합니다. 답변 모달에 사용합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "문의 상세 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 문의", content = @Content)
    })
    @GetMapping("/{id}")
    public ApiResponse<AdminInquiryDetailResponse> getInquiry(
            @Parameter(description = "문의 ID") @PathVariable Long id) {
        return ApiResponse.ok(adminInquiryService.getInquiry(id));
    }

    @Operation(summary = "문의 답변 작성",
            description = """
                    문의에 답변을 등록합니다. 상태가 WAITING → ANSWERED 로 전환되고 답변자·답변 시각이 기록됩니다.
                    답변 완료 시 작성자(보호자)에게 FCM 알림이 발송됩니다(선택 알림 — 사용자 알림 설정에 따름).
                    이미 답변된 문의에 재답변 시 409를 반환합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "답변 완료된 문의 상세 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "answer 누락 또는 형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 문의", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 답변이 완료된 문의", content = @Content)
    })
    @PostMapping("/{id}/answer")
    public ApiResponse<AdminInquiryDetailResponse> answer(
            @Parameter(description = "문의 ID") @PathVariable Long id,
            @Valid @RequestBody InquiryAnswerRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(adminInquiryService.answer(id, request, adminId));
    }
}
