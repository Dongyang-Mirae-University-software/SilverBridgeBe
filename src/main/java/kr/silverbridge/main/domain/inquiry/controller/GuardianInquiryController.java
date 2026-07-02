package kr.silverbridge.main.domain.inquiry.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.inquiry.dto.InquiryCreateRequest;
import kr.silverbridge.main.domain.inquiry.dto.InquiryResponse;
import kr.silverbridge.main.domain.inquiry.service.InquiryService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 보호자용 고객센터 문의 API. 문의 작성 + 본인 문의 목록·상세 조회.
 * 클래스 레벨 {@code @PreAuthorize("hasRole('GUARDIAN')")}로 GUARDIAN만 접근 가능(WARD/ADMIN 403).
 */
@Tag(name = "보호자 - 문의")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianInquiryController {

    private final InquiryService inquiryService;

    @Operation(summary = "문의 작성",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    보호자가 고객센터에 문의를 작성합니다. 최초 상태는 WAITING(답변 대기)입니다.
                    category 는 ANOMALY(이상감지)/HOSPITAL(병원)/ACCOUNT(계정·회원)/SERVICE(서비스 이용)/ETC(기타) 중 하나입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "작성된 문의 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "category/title/content 누락 또는 형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @PostMapping("/api/guardian/inquiry")
    public ResponseEntity<ApiResponse<InquiryResponse>> create(
            @AuthenticationPrincipal String guardianId,
            @Valid @RequestBody InquiryCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(inquiryService.create(guardianId, request)));
    }

    @Operation(summary = "내 문의 목록 조회",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    본인이 작성한 문의를 최신순으로 반환합니다. 답변 완료(ANSWERED) 건은 answer/answeredAt이 함께 채워집니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "내 문의 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @GetMapping("/api/guardian/inquiry")
    public ResponseEntity<ApiResponse<List<InquiryResponse>>> getMyInquiries(
            @AuthenticationPrincipal String guardianId) {
        return ResponseEntity.ok(ApiResponse.ok(inquiryService.getMyInquiries(guardianId)));
    }

    @Operation(summary = "내 문의 상세 조회",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    본인이 작성한 문의의 상세를 조회합니다. 타인 문의 ID로 조회 시 404로 응답합니다(IDOR 차단).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "문의 상세 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않거나 본인 문의가 아님", content = @Content)
    })
    @GetMapping("/api/guardian/inquiry/{id}")
    public ResponseEntity<ApiResponse<InquiryResponse>> getMyInquiry(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "문의 ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(inquiryService.getMyInquiry(guardianId, id)));
    }
}
