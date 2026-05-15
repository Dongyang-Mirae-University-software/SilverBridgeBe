package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementDraftResponse;
import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementResponse;
import kr.silverbridge.main.domain.admin.dto.AnnouncementDraftCreateRequest;
import kr.silverbridge.main.domain.admin.dto.AnnouncementDraftUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminAnnouncementDraftService;
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

@Tag(name = "관리자 - 공지 임시저장")
@RestController
@RequestMapping("/api/admin/announcement/draft")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminAnnouncementDraftController {

    private final AdminAnnouncementDraftService draftService;

    @Operation(summary = "임시저장 목록 조회",
            description = """
                    임시저장된 공지 목록을 조회합니다.

                    [정렬]
                    - 생성 일시 내림차순

                    [작성자 탈퇴 시]
                    authorName 은 null 로 반환됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "임시저장 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/select")
    public ApiResponse<List<AdminAnnouncementDraftResponse>> getDrafts() {
        return ApiResponse.ok(draftService.getDrafts());
    }

    @Operation(summary = "임시저장 상세 조회", description = "임시저장 ID로 단건 상세 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "임시저장 상세 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 임시저장", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/select/detail/{id}")
    public ApiResponse<AdminAnnouncementDraftResponse> getDraft(
            @Parameter(description = "임시저장 ID") @PathVariable Long id) {
        return ApiResponse.ok(draftService.getDraft(id));
    }

    @Operation(summary = "임시저장 생성",
            description = """
                    공지를 임시저장합니다. 게시되지 않은 상태로 보관되며 사용자에게는 노출되지 않습니다.

                    [입력 규칙]
                    - title 200자 이내
                    - content 5000자 이내
                    - 작성 중이라 두 값 모두 비어 있을 수 있음
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "생성된 임시저장 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "title 200자 초과 또는 content 5000자 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PostMapping("/create")
    public ApiResponse<AdminAnnouncementDraftResponse> createDraft(
            @Valid @RequestBody AnnouncementDraftCreateRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(draftService.createDraft(request, adminId));
    }

    @Operation(summary = "임시저장 수정", description = "임시저장된 공지의 제목과 내용을 수정합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정된 임시저장 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "title 200자 초과 또는 content 5000자 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 임시저장", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PutMapping("/update/{id}")
    public ApiResponse<AdminAnnouncementDraftResponse> updateDraft(
            @Parameter(description = "임시저장 ID") @PathVariable Long id,
            @Valid @RequestBody AnnouncementDraftUpdateRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(draftService.updateDraft(id, request, adminId));
    }

    @Operation(summary = "임시저장 삭제",
            description = """
                    임시저장된 공지를 영구 삭제합니다.

                    [주의사항]
                    - 삭제된 임시저장은 복구할 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 임시저장", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> deleteDraft(
            @Parameter(description = "임시저장 ID") @PathVariable Long id,
            @AuthenticationPrincipal String adminId) {
        draftService.deleteDraft(id, adminId);
        return ApiResponse.ok("임시저장이 삭제되었습니다.");
    }

    @Operation(summary = "임시저장 게시",
            description = """
                    임시저장된 공지를 공식 공지로 게시합니다.

                    [동작]
                    - announcements 에 새 공지로 저장됩니다.
                    - 임시저장 행은 삭제됩니다.

                    [전제 조건]
                    - title, content 모두 비어있지 않아야 합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "게시된 공지 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "title 또는 content 누락", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 임시저장", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PostMapping("/publish/{id}")
    public ApiResponse<AdminAnnouncementResponse> publishDraft(
            @Parameter(description = "임시저장 ID") @PathVariable Long id,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(draftService.publishDraft(id, adminId));
    }
}