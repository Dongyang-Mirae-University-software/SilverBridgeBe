package kr.silverbridge.main.domain.admin.controller;

import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementDraftResponse;
import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementResponse;
import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementSummaryResponse;
import kr.silverbridge.main.domain.admin.service.AdminAnnouncementDraftService;
import kr.silverbridge.main.domain.admin.service.AdminAnnouncementService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.GlobalExceptionHandler;
import kr.silverbridge.main.global.response.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 공지 목록·탭 건수의 실제 HTTP 직렬화(2026-10-26). 관리자 웹은 계약 문서만 보고 붙으므로
 * 목록이 배열이 아니라 PageResponse 칸(content·totalElements·totalPages…)으로 내려가는지,
 * keyword가 서비스로 그대로 가고 50자 초과가 400 INVALID_INPUT으로 나가는지를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminAnnouncementControllerHttpTest {

    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 10, 26, 9, 30, 0, 0, ZoneOffset.ofHours(9));

    @Mock private AdminAnnouncementService announcementService;
    @Mock private AdminAnnouncementDraftService draftService;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                        new AdminAnnouncementController(announcementService),
                        new AdminAnnouncementDraftController(draftService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("공지 목록: PageResponse 칸과 항목 칸이 내려가고 keyword·page·size가 서비스로 간다")
    void announcementListSerializes() throws Exception {
        when(announcementService.getAnnouncements(eq(1), eq(10), eq("점검")))
                .thenReturn(new PageResponse<>(List.of(new AdminAnnouncementResponse(7L, "AD0001", "관리자",
                        "서버 점검 안내", "미리보기", 3L, AT, AT)), 1, 10, 11, 2, true));

        mvc().perform(get("/api/admin/announcement/select")
                        .param("page", "1").param("size", "10").param("keyword", "점검"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(7))
                .andExpect(jsonPath("$.data.content[0].title").value("서버 점검 안내"))
                .andExpect(jsonPath("$.data.content[0].authorName").value("관리자"))
                .andExpect(jsonPath("$.data.content[0].viewCount").value(3))
                .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-10-26T09:30:00+09:00"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(11))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.last").value(true));
    }

    @Test
    @DisplayName("공지 목록: keyword를 생략하면 null로 간다(검색 조건 없음)")
    void keywordOmittedIsNull() throws Exception {
        when(announcementService.getAnnouncements(eq(0), eq(20), isNull()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mvc().perform(get("/api/admin/announcement/select"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("공지 목록: 검색어 50자 초과는 400 INVALID_INPUT")
    void longKeywordIs400() throws Exception {
        String keyword = "가".repeat(51);
        when(announcementService.getAnnouncements(eq(0), eq(20), eq(keyword)))
                .thenThrow(new CustomException(ErrorCode.INVALID_INPUT));

        mvc().perform(get("/api/admin/announcement/select").param("keyword", keyword))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("탭 건수: publishedCount·draftCount 칸으로 내려간다")
    void summarySerializes() throws Exception {
        when(announcementService.getSummary()).thenReturn(new AdminAnnouncementSummaryResponse(12, 3));

        mvc().perform(get("/api/admin/announcement/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedCount").value(12))
                .andExpect(jsonPath("$.data.draftCount").value(3));
    }

    @Test
    @DisplayName("임시저장 목록: PageResponse로 내려가고 keyword가 서비스로 간다")
    void draftListSerializes() throws Exception {
        when(draftService.getDrafts(eq(0), eq(20), eq("안내")))
                .thenReturn(new PageResponse<>(List.of(new AdminAnnouncementDraftResponse(5L, "AD0001", null,
                        "휴무 안내", "", AT, AT)), 0, 20, 1, 1, true));

        mvc().perform(get("/api/admin/announcement/draft/select").param("keyword", "안내"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(5))
                .andExpect(jsonPath("$.data.content[0].title").value("휴무 안내"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }
}
