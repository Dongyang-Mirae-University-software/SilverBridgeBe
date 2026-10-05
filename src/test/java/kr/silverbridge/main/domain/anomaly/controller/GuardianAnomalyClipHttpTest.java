package kr.silverbridge.main.domain.anomaly.controller;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyClipRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipAccessService;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipService;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipStorage;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalyService;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalySettingService;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보호자용 클립 API를 <b>HTTP 응답 단위</b>로 고정한다(2026-10-05 QA 종합 점검 테스트 공백 보강).
 *
 * <p>역할 게이트(WARD·ADMIN 403)는 {@link GuardianAnomalyControllerSecurityTest}, 인가 분기는
 * {@code AnomalyClipAccessServiceTest}가 맡는다. 여기서는 실제 인가 서비스·클립 서비스·파일 저장소를 엮어
 * ① 인가 위반이 403, 숨김·만료·없음이 404로 <b>실제 응답 코드</b>가 되는지, 오류 본문에 소유자·파일 정보가 없는지
 * ② 파일 응답이 Range 206과 {@code private, no-store}를 지키는지를 본다. 저장소·연결은 목, 파일은 실제 임시 디렉터리다.</p>
 */
@ExtendWith(MockitoExtension.class)
class GuardianAnomalyClipHttpTest {

    private static final String GUARDIAN = "GD0001";
    private static final String WARD = "WD0001";
    private static final String OTHER_WARD = "WD0002";
    private static final Long CLIP_ID = 101L;
    private static final byte[] VIDEO = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    java.nio.file.Path root;

    @Mock private AnomalyClipRepository clipRepository;
    @Mock private AnomalyIncidentRepository incidentRepository;
    @Mock private CameraService cameraService;
    @Mock private ConnectionService connectionService;
    @Mock private GuardianAnomalyService guardianAnomalyService;
    @Mock private GuardianAnomalySettingService settingService;

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
                .standaloneSetup(new GuardianAnomalyController(guardianAnomalyService, settingService, accessService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(GUARDIAN, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 클립 행 + 실제 파일. */
    private String clipOf(String wardId, AnomalyClipStatus status, OffsetDateTime createdAt) {
        String fileName = storage.write(VIDEO);
        AnomalyClip clip = AnomalyClip.builder().incidentId(37L).wardId(wardId).sessionId("ward_k3m9Q2aZ7pLx01Bc")
                .fileName(fileName).sizeBytes(VIDEO.length).detectedAt(createdAt).status(status)
                .hiddenAt(status == AnomalyClipStatus.HIDDEN ? createdAt : null).build();
        ReflectionTestUtils.setField(clip, "id", CLIP_ID);
        ReflectionTestUtils.setField(clip, "createdAt", createdAt);
        when(clipRepository.findById(CLIP_ID)).thenReturn(Optional.of(clip));
        return fileName;
    }

    private void connected(String wardId, boolean active) {
        when(connectionService.isActiveConnection(GUARDIAN, wardId)).thenReturn(active);
    }

    private static String fileUrl() {
        return "/api/guardian/anomaly/clips/" + CLIP_ID + "/file";
    }

    @Nested
    @DisplayName("인가 - 403")
    class Forbidden {

        @Test
        @DisplayName("연결 없음·PENDING(isActiveConnection=false) → 403 ANOMALY_NOT_AUTHORIZED, 본문에 소유자·파일 정보 없음")
        void notConnected() throws Exception {
            String fileName = clipOf(WARD, AnomalyClipStatus.VISIBLE, OffsetDateTime.now());
            connected(WARD, false);

            MvcResult result = mockMvc.perform(get(fileUrl()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ANOMALY_NOT_AUTHORIZED"))
                    .andReturn();

            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).doesNotContain(WARD, fileName, "ward_");
            assertThat(result.getResponse().getContentType()).doesNotContain("video");
        }

        @Test
        @DisplayName("내 피보호자와는 연결돼 있어도 다른 집 클립이면 403 - 연결 여부는 클립 소유 피보호자 기준")
        void otherWardsClip() throws Exception {
            clipOf(OTHER_WARD, AnomalyClipStatus.VISIBLE, OffsetDateTime.now());
            lenient().when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
            connected(OTHER_WARD, false);

            mockMvc.perform(get(fileUrl()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ANOMALY_NOT_AUTHORIZED"));
        }

        @Test
        @DisplayName("목록도 같다 - 연결되지 않은 피보호자의 상황이면 403")
        void listNotConnected() throws Exception {
            when(incidentRepository.findById(37L)).thenReturn(Optional.of(
                    kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident.builder().wardId(OTHER_WARD)
                            .sessionId("ward_k3m9Q2aZ7pLx01Bc")
                            .detectedType(kr.silverbridge.main.global.enums.DetectedType.FIRE)
                            .detectedAt(OffsetDateTime.now()).confidence(0.9).build()));
            connected(OTHER_WARD, false);

            mockMvc.perform(get("/api/guardian/anomaly/37/clips"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ANOMALY_NOT_AUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("비공개·만료·없음 - 404(사유를 구분하지 않는다)")
    class NotFound {

        @Test
        @DisplayName("오탐으로 숨긴 클립 → 404 (연결돼 있어도)")
        void hidden() throws Exception {
            clipOf(WARD, AnomalyClipStatus.HIDDEN, OffsetDateTime.now());

            mockMvc.perform(get(fileUrl()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ANOMALY_CLIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("보관 기간(30일)이 지난 클립 → 404")
        void expired() throws Exception {
            clipOf(WARD, AnomalyClipStatus.VISIBLE, OffsetDateTime.now().minusDays(31));

            mockMvc.perform(get(fileUrl()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ANOMALY_CLIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("없는 클립 → 404")
        void missing() throws Exception {
            when(clipRepository.findById(CLIP_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get(fileUrl()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ANOMALY_CLIP_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("파일 응답 헤더")
    class FileResponse {

        @Test
        @DisplayName("전체 요청 → 200 video/webm, Cache-Control private·no-store, 파일 이름은 클립 ID만")
        void full() throws Exception {
            String fileName = clipOf(WARD, AnomalyClipStatus.VISIBLE, OffsetDateTime.now());
            connected(WARD, true);

            MvcResult result = mockMvc.perform(get(fileUrl()))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/webm"))
                    .andReturn();

            assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store", "private");
            assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                    .contains("inline", "clip-101.webm")
                    .doesNotContain(fileName);
            assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(VIDEO);
        }

        @Test
        @DisplayName("Range 요청 → 206 + Content-Range, 요청한 구간만 - 캐시 금지 헤더는 그대로")
        void range() throws Exception {
            clipOf(WARD, AnomalyClipStatus.VISIBLE, OffsetDateTime.now());
            connected(WARD, true);

            MvcResult result = mockMvc.perform(get(fileUrl()).header(HttpHeaders.RANGE, "bytes=0-9"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-9/" + VIDEO.length))
                    .andReturn();

            assertThat(result.getResponse().getContentAsByteArray())
                    .isEqualTo("0123456789".getBytes(StandardCharsets.US_ASCII));
            assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store", "private");
        }

        @Test
        @DisplayName("행은 있으나 파일이 없으면 404 JSON - 빈 영상을 200으로 내려보내지 않는다")
        void fileGone() throws Exception {
            String fileName = clipOf(WARD, AnomalyClipStatus.VISIBLE, OffsetDateTime.now());
            connected(WARD, true);
            storage.delete(fileName);

            mockMvc.perform(get(fileUrl()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ANOMALY_CLIP_NOT_FOUND"));
        }
    }
}
