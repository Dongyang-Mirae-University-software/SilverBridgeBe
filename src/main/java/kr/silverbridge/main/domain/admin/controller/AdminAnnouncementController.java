package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementResponse;
import kr.silverbridge.main.domain.admin.dto.AnnouncementCreateRequest;
import kr.silverbridge.main.domain.admin.dto.AnnouncementUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminAnnouncementService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "관리자 - 공지")
@RestController
@RequestMapping("/api/admin/announcement")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminAnnouncementController {

    private final AdminAnnouncementService adminAnnouncementService;

    @Operation(summary = "공지 목록 조회",
            description = """
                    공지 목록을 조회합니다.

                    [작성자 탈퇴 시]
                    authorName 은 null 로 반환됩니다.

                    [정렬]
                    - 작성 일시 내림차순
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공지 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/select")
    public ApiResponse<List<AdminAnnouncementResponse>> getAnnouncements() {
        return ApiResponse.ok(adminAnnouncementService.getAnnouncements());
    }

    @Operation(summary = "공지 상세 조회", description = "공지 ID로 단건 상세 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공지 상세 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 공지", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/select/detail/{id}")
    public ApiResponse<AdminAnnouncementResponse> getAnnouncement(
            @Parameter(description = "공지 ID") @PathVariable Long id) {
        return ApiResponse.ok(adminAnnouncementService.getAnnouncement(id));
    }

    @Operation(summary = "공지 등록", description = "새 공지를 등록합니다. 등록 즉시 게시됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록된 공지 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "title 또는 content 누락 / title 200자 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PostMapping("/create")
    public ApiResponse<AdminAnnouncementResponse> createAnnouncement(
            @Valid @RequestBody AnnouncementCreateRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(adminAnnouncementService.createAnnouncement(request, adminId));
    }

    @Operation(summary = "공지 수정", description = "공지의 제목과 내용을 수정합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정된 공지 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "title 또는 content 누락 / title 200자 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 공지", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PutMapping("/update/{id}")
    public ApiResponse<AdminAnnouncementResponse> updateAnnouncement(
            @Parameter(description = "공지 ID") @PathVariable Long id,
            @Valid @RequestBody AnnouncementUpdateRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(adminAnnouncementService.updateAnnouncement(id, request, adminId));
    }

    @Operation(summary = "공지 삭제",
            description = """
                    공지를 영구 삭제합니다.

                    [주의사항]
                    - 삭제된 공지는 복구할 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 공지", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> deleteAnnouncement(
            @Parameter(description = "공지 ID") @PathVariable Long id,
            @AuthenticationPrincipal String adminId) {
        adminAnnouncementService.deleteAnnouncement(id, adminId);
        return ApiResponse.ok("공지가 삭제되었습니다.");
    }
}
