package kr.silverbridge.main.domain.companion.controller;

import kr.silverbridge.main.domain.companion.client.CompanionAudio;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TurnResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.UtteranceAudio;
import kr.silverbridge.main.domain.companion.service.CompanionAudioService;
import kr.silverbridge.main.domain.companion.service.CompanionService;
import kr.silverbridge.main.domain.companion.service.CompanionTranscriptService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 말벗 목소리 중계의 실제 HTTP 응답(2026-11-13) - 바이트 본문·형식·캐시 금지 헤더, 오류는 JSON, 턴 응답의 audio 칸. */
class WardCompanionAudioControllerHttpTest {

    private static final String WARD = "W00001";
    private static final byte[] MP3 = {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00};

    private final CompanionService companionService = mock(CompanionService.class);
    private final CompanionAudioService audioService = mock(CompanionAudioService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(WARD, null, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new WardCompanionController(companionService,
                        mock(CompanionTranscriptService.class), audioService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("200: 소리 바이트 그대로 + audio/mpeg + Content-Length + no-store + nosniff")
    void audioBytes() throws Exception {
        when(audioService.utteranceAudio(WARD, 10L, "2")).thenReturn(new CompanionAudio(MP3, "audio/mpeg"));

        mvc.perform(get("/api/ward/companion/sessions/10/audio/2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(content().bytes(MP3))
                .andExpect(header().string("Content-Length", String.valueOf(MP3.length)))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("브라우저 audio 요청(Accept: audio/*)도 200으로 받는다")
    void audioAcceptHeader() throws Exception {
        when(audioService.utteranceAudio(WARD, 10L, "0")).thenReturn(new CompanionAudio(MP3, "audio/ogg"));

        mvc.perform(get("/api/ward/companion/sessions/10/audio/0").accept("audio/*"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/ogg"));
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"COMPANION_AUDIO_NOT_FOUND", "COMPANION_AUDIO_NOT_READY",
            "COMPANION_AUDIO_BAD_UPSTREAM", "COMPANION_AUDIO_INDEX_INVALID", "COMPANION_SESSION_NOT_OWNED",
            "COMPANION_SESSION_NOT_FOUND", "COMPANION_DISABLED"})
    @DisplayName("오류는 상태 코드 + JSON code로 내려간다(기기는 404·503이면 직접 읽어 준다)")
    void errorsAsJson(ErrorCode code) throws Exception {
        when(audioService.utteranceAudio(eq(WARD), eq(10L), any())).thenThrow(new CustomException(code));

        mvc.perform(get("/api/ward/companion/sessions/10/audio/1"))
                .andExpect(status().is(code.getStatus().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code.name()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"audio/mpeg", "audio/*", "audio/ogg, audio/wav"})
    @DisplayName("Accept가 소리 형식뿐이어도 오류는 상태 그대로 + JSON(점검 L-1 - 전에는 406 → 401)")
    void errorsIgnoreAudioAccept(String accept) throws Exception {
        when(audioService.utteranceAudio(eq(WARD), eq(10L), any()))
                .thenThrow(new CustomException(ErrorCode.COMPANION_AUDIO_NOT_FOUND));

        mvc.perform(get("/api/ward/companion/sessions/10/audio/40").accept(accept))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMPANION_AUDIO_NOT_FOUND"));
    }

    @Test
    @DisplayName("전용 처리가 있는 하위 예외(429)는 전역 처리기로 - Retry-After 유지")
    void tooManyRequestsKeepsRetryAfter() throws Exception {
        when(audioService.utteranceAudio(eq(WARD), eq(10L), any()))
                .thenThrow(new kr.silverbridge.main.global.exception.TooManyRequestsException(30));

        mvc.perform(get("/api/ward/companion/sessions/10/audio/1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"));
    }

    @Test
    @DisplayName("턴 응답: audio는 {index} 객체로, 없으면 null로 내려간다(필드는 항상 있음)")
    void turnResponseAudioField() throws Exception {
        when(companionService.replyText(WARD, 10L, "네"))
                .thenReturn(new TurnResponse(1, "그렇군요.", false, null, null, null, new UtteranceAudio(1)));
        mvc.perform(post("/api/ward/companion/sessions/10/reply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"네\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audio.index").value(1))
                .andExpect(jsonPath("$.data.analysis").doesNotExist());

        when(companionService.replyText(WARD, 10L, "응"))
                .thenReturn(new TurnResponse(2, "네.", false, null, null));
        mvc.perform(post("/api/ward/companion/sessions/10/reply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"응\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audio").hasJsonPath())
                .andExpect(jsonPath("$.data.audio").isEmpty());
    }
}
