package kr.silverbridge.main.domain.anomaly.controller;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyClipRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipAccessService;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipService;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipStorage;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피보호자용 클립 API의 <b>HTTP 응답 단위</b> 고정(2026-10-05 기능 점검 L-1).
 *
 * <p>보호자 경로는 {@link GuardianAnomalyClipHttpTest}가 본다. 피보호자 경로는 인가 규칙이 다르다 -
 * 본인 집 클립이어야 하고(남의 것은 403 {@code ANOMALY_CLIP_NOT_OWNED}), <b>ACTIVE 보호자가 1명 이상</b>일 때만 보인다
 * (0명이면 404). 파일 응답(헤더·Range)은 보호자 경로와 같은 헬퍼를 쓰지만 라우트가 달라 따로 확인한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class WardAnomalyClipHttpTest {

    private static final String WARD = "WD0001";
    private static final String OTHER_WARD = "WD0002";
    private static final Long CLIP_ID = 101L;
    private static final byte[] VIDEO = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path root;

    @Mock private AnomalyClipRepository clipRepository;
    @Mock private AnomalyIncidentRepository incidentRepository;
    @Mock private CameraService cameraService;
    @Mock private ConnectionService connectionService;

    private AnomalyClipStorage storage;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AnomalyProperties properties = new AnomalyProperties();
        properties.getClip().setStorageDir(root.toString());
        storage = new AnomalyClipStorage(properties);
        AnomalyClipService clipService = new AnomalyClipService(clipRepository, incidentRepository, cameraService, properties);
        AnomalyClipAccessService accessService =
                new AnomalyClipAccessService(clipService, storage, incidentRepository, connectionService);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new WardAnomalyClipController(accessService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(WARD, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String clipOf(String ownerWardId) {
        String fileName = storage.write(VIDEO);
        OffsetDateTime now = OffsetDateTime.now();
        AnomalyClip clip = AnomalyClip.builder().incidentId(37L).wardId(ownerWardId).sessionId("ward_k3m9Q2aZ7pLx01Bc")
                .fileName(fileName).sizeBytes(VIDEO.length).detectedAt(now).status(AnomalyClipStatus.VISIBLE).build();
        ReflectionTestUtils.setField(clip, "id", CLIP_ID);
        ReflectionTestUtils.setField(clip, "createdAt", now);
        when(clipRepository.findById(CLIP_ID)).thenReturn(Optional.of(clip));
        return fileName;
    }

    private void activeGuardians(String wardId, List<String> guardianIds) {
        when(connectionService.getActiveGuardianIds(wardId)).thenReturn(guardianIds);
    }

    private static String fileUrl() {
        return "/api/ward/anomaly/clips/" + CLIP_ID + "/file";
    }

    @Test
    @DisplayName("다른 피보호자의 클립 → 403 ANOMALY_CLIP_NOT_OWNED, 본문에 소유자·파일 정보 없음")
    void othersClipIsForbidden() throws Exception {
        String fileName = clipOf(OTHER_WARD);

        MvcResult result = mockMvc.perform(get(fileUrl()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ANOMALY_CLIP_NOT_OWNED"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(OTHER_WARD, fileName, "ward_");
    }

    @Test
    @DisplayName("남의 상황의 클립 목록 → 403 ANOMALY_CLIP_NOT_OWNED")
    void othersIncidentListIsForbidden() throws Exception {
        when(incidentRepository.findById(37L)).thenReturn(Optional.of(AnomalyIncident.builder()
                .wardId(OTHER_WARD).sessionId("ward_k3m9Q2aZ7pLx01Bc").detectedType(DetectedType.FIRE)
                .detectedAt(OffsetDateTime.now()).confidence(0.9).build()));

        mockMvc.perform(get("/api/ward/anomaly/37/clips"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ANOMALY_CLIP_NOT_OWNED"));
    }

    @Test
    @DisplayName("본인 집 클립이어도 ACTIVE 보호자가 한 명도 없으면 404 - 연결 해제로 비공개, 파일은 지우지 않는다")
    void noActiveGuardianIsNotFound() throws Exception {
        String fileName = clipOf(WARD);
        activeGuardians(WARD, List.of());

        mockMvc.perform(get(fileUrl()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANOMALY_CLIP_NOT_FOUND"));

        assertThat(storage.find(fileName)).as("해제로 파일을 지우지 않는다").isPresent();
    }

    @Test
    @DisplayName("본인 집 클립 + ACTIVE 보호자 1명 이상 → 200 video/webm, private·no-store")
    void ownClipIsServed() throws Exception {
        clipOf(WARD);
        activeGuardians(WARD, List.of("GD0001"));

        MvcResult result = mockMvc.perform(get(fileUrl()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/webm"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store", "private");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(VIDEO);
    }

    @Test
    @DisplayName("Range 요청 → 206 + Content-Range, 요청한 구간만")
    void rangeRequest() throws Exception {
        clipOf(WARD);
        activeGuardians(WARD, List.of("GD0001"));

        MvcResult result = mockMvc.perform(get(fileUrl()).header(HttpHeaders.RANGE, "bytes=10-19"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 10-19/" + VIDEO.length))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray())
                .isEqualTo("abcdefghij".getBytes(StandardCharsets.US_ASCII));
    }
}
