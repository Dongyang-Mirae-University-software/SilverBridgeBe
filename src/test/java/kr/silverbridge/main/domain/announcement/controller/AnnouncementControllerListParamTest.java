package kr.silverbridge.main.domain.announcement.controller;

import kr.silverbridge.main.domain.announcement.dto.AnnouncementResponse;
import kr.silverbridge.main.domain.announcement.service.AnnouncementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 공개 공지 목록의 선택 page/size 와 배열 응답 모양 고정 (ADMIN-G24). */
@ExtendWith(MockitoExtension.class)
class AnnouncementControllerListParamTest {

    @Mock private AnnouncementService announcementService;

    private AnnouncementResponse item() {
        return new AnnouncementResponse(1L, "관리자", "제목", "내용", 0L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    @DisplayName("파라미터 없이 불러도 배열로 응답하고 기본값 page=0, size=20으로 조회한다")
    void 파라미터_없이_배열_응답() throws Exception {
        when(announcementService.getAnnouncements(0, 20)).thenReturn(List.of(item()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnnouncementController(announcementService)).build();

        mvc.perform(get("/api/commonness/announcement/select"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(1));
        verify(announcementService).getAnnouncements(0, 20);
    }

    @Test
    @DisplayName("page/size 를 주면 그대로 서비스에 전달한다(보정은 서비스)")
    void 파라미터_전달() throws Exception {
        when(announcementService.getAnnouncements(2, 50)).thenReturn(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnnouncementController(announcementService)).build();

        mvc.perform(get("/api/commonness/announcement/select").param("page", "2").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        verify(announcementService).getAnnouncements(2, 50);
    }
}
