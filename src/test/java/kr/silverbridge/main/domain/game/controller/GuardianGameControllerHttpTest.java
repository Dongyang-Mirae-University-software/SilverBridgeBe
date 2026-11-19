package kr.silverbridge.main.domain.game.controller;

import kr.silverbridge.main.domain.game.dto.GuardianGameActivityResponse;
import kr.silverbridge.main.domain.game.dto.GuardianGameProgressResponse;
import kr.silverbridge.main.domain.game.service.GuardianGameService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 게임 기록 중계 응답의 실제 HTTP 직렬화(Jackson 3 변환기) - FE가 쓰는 필드 이름·null·no-store. */
@ExtendWith(MockitoExtension.class)
class GuardianGameControllerHttpTest {

    @Mock private GuardianGameService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("GRD001", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new GuardianGameController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    @DisplayName("진행 요약: 필드 이름·숫자·null progress 가 그대로 내려가고 캐시하지 않는다")
    void progress() throws Exception {
        when(service.getProgress("GRD001", "aB3x9Z")).thenReturn(new GuardianGameProgressResponse(
                "aB3x9Z", 120, 9, "2026-11-18T05:03:09.123456", List.of(
                new GuardianGameProgressResponse.GameEntry(
                        new GuardianGameProgressResponse.Catalog("maze", "미로", "d", 10, "#fff"),
                        new GuardianGameProgressResponse.Progress("maze", 3, 120L, 9L, false, true,
                                "2026-11-01T00:00:00", "2026-11-18T05:03:09.123456", null)),
                new GuardianGameProgressResponse.GameEntry(
                        new GuardianGameProgressResponse.Catalog("cards", "카드", "d2", 5, "#000"), null))));

        mvc().perform(get("/api/guardian/game/progress").param("wardId", "aB3x9Z"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.wardId").value("aB3x9Z"))
                .andExpect(jsonPath("$.data.totalScore").value(120))
                .andExpect(jsonPath("$.data.lastPlayedAt").value("2026-11-18T05:03:09.123456"))
                .andExpect(jsonPath("$.data.games[0].game.slug").value("maze"))
                .andExpect(jsonPath("$.data.games[0].game.totalStages").value(10))
                .andExpect(jsonPath("$.data.games[0].progress.currentStageNo").value(3))
                .andExpect(jsonPath("$.data.games[0].progress.lastAnswerCorrect").value(true))
                .andExpect(jsonPath("$.data.games[1].progress").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist());
    }

    @Test
    @DisplayName("활동량: days 를 넘기고 activity 배열이 내려간다")
    void activity() throws Exception {
        when(service.getActivity("GRD001", "aB3x9Z", 30)).thenReturn(new GuardianGameActivityResponse(
                "aB3x9Z", 30, List.of(new GuardianGameActivityResponse.Day("2026-11-17", 4, 3, 30))));

        mvc().perform(get("/api/guardian/game/activity").param("wardId", "aB3x9Z").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.days").value(30))
                .andExpect(jsonPath("$.data.activity[0].date").value("2026-11-17"))
                .andExpect(jsonPath("$.data.activity[0].correct").value(3));
    }

    @Test
    @DisplayName("활동량: days 생략 시 null 로 넘긴다(서비스가 182 적용)")
    void activityWithoutDays() throws Exception {
        when(service.getActivity("GRD001", "aB3x9Z", null)).thenReturn(
                new GuardianGameActivityResponse("aB3x9Z", 182, List.of()));

        mvc().perform(get("/api/guardian/game/activity").param("wardId", "aB3x9Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(182));
    }
}
