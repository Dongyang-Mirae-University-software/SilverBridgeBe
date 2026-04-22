package kr.silverbridge.main.domain.announcement.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.announcement.dto.AnnouncementResponse;
import kr.silverbridge.main.domain.announcement.service.AnnouncementService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "공지사항 (사용자)", description = """
        로그인한 사용자(WARD/GUARDIAN/ADMIN 모두)가 열람하는 공지사항 API.
        관리자 CRUD는 /api/admin/announcement/* 참고.
        """)
@RestController
@RequestMapping("/api/commonness/announcement")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @Operation(
            summary = "공지 목록 조회",
            description = """
                    공지 목록을 페이지 단위로 반환합니다. 최신 공지가 먼저 반환됩니다.

                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [페이지네이션 쿼리 파라미터]
                    - page: 페이지 번호 (0부터 시작, 기본값 0)
                    - size: 페이지당 항목 수 (기본값 20, 최대 100)
                    - sort: 정렬 기준 (기본값: createdAt,desc)

                    [페이지네이션 응답 구조]
                    data.content       → 실제 공지 배열
                    data.totalElements → 전체 공지 수
                    data.totalPages    → 전체 페이지 수
                    data.number        → 현재 페이지 (0부터)
                    data.size          → 페이지당 항목 수
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공지 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/select")
    public ApiResponse<Page<AnnouncementResponse>> getAnnouncements(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(announcementService.getAnnouncements(pageable));
    }

    @Operation(
            summary = "공지 상세 조회",
            description = """
                    공지 ID로 단건 상세를 조회합니다. 목록 응답의 id 값을 그대로 전달하세요.

                    [요청 헤더]
                    Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공지 상세 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 공지", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/select/detail/{id}")
    public ApiResponse<AnnouncementResponse> getAnnouncement(
            @Parameter(description = "공지 ID") @PathVariable Long id) {
        return ApiResponse.ok(announcementService.getAnnouncement(id));
    }
}
