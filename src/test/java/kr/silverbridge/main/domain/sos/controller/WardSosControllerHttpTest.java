package kr.silverbridge.main.domain.sos.controller;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.sos.dto.SosResponse;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.service.SosCalledGuardianResolver;
import kr.silverbridge.main.domain.sos.service.SosService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피보호자 SOS 요청의 전화받은 보호자(calledGuardianId) 처리 - 어떤 값이 와도 SOS는 201로 접수된다(2026-10-30).
 *
 * <p>확인기는 실제 객체(연결 조회만 목)로 둬서 "검증 실패 → 값만 버리고 SOS는 그대로"를 HTTP 단에서 고정한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class WardSosControllerHttpTest {

    private static final String WARD_ID = "WD0001";

    @Mock private SosService sosService;
    @Mock private ConnectionService connectionService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(WARD_ID, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                        new WardSosController(sosService, new SosCalledGuardianResolver(connectionService)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private void stubTrigger() {
        when(sosService.trigger(eq(WARD_ID), any(), any(), any())).thenReturn(new SosResponse(42L, OffsetDateTime.now()));
    }

    @Test
    @DisplayName("연결된 보호자에게 건 전화 → 확인한 뒤 그 ID로 저장한다 (확인이 저장보다 먼저)")
    void 연결된_보호자() throws Exception {
        stubTrigger();
        when(connectionService.isActiveConnection("GD0001", WARD_ID)).thenReturn(true);

        mvc().perform(post("/api/ward/sos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerType\":\"GUARDIAN_CALL\",\"calledGuardianId\":\"GD0001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sosEventId").value(42));

        InOrder order = inOrder(connectionService, sosService);
        order.verify(connectionService).isActiveConnection("GD0001", WARD_ID);
        order.verify(sosService).trigger(WARD_ID, null, SosTriggerType.GUARDIAN_CALL, "GD0001");
    }

    @Test
    @DisplayName("너무 긴 값·연결 안 된 보호자여도 400이 아니라 201 - 값만 저장하지 않는다")
    void 잘못된_값도_SOS는_접수() throws Exception {
        stubTrigger();
        String longValue = "x".repeat(1000);

        mvc().perform(post("/api/ward/sos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerType\":\"GUARDIAN_CALL\",\"calledGuardianId\":\"" + longValue + "\"}"))
                .andExpect(status().isCreated());

        verify(sosService).trigger(eq(WARD_ID), isNull(), eq(SosTriggerType.GUARDIAN_CALL), isNull());
    }

    @Test
    @DisplayName("바디 없는 기존 호출은 그대로 201")
    void 바디없음() throws Exception {
        stubTrigger();

        mvc().perform(post("/api/ward/sos"))
                .andExpect(status().isCreated());

        verify(sosService).trigger(WARD_ID, null, null, null);
    }
}
