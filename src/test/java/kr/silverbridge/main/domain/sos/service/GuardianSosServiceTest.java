package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.sos.dto.SosHistoryItem;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * GuardianSosService 단위 테스트.
 *
 * <p>핵심은 <b>인가</b>다 - 보호자는 ACTIVE 연결된 피보호자의 이력만 볼 수 있어야 한다(IDOR 차단).
 * 그 밖에 ① 다중 피보호자 이력 병합·이름 매핑 ② 발생 경로 노출 ③ 페이지 크기 상한을 검증한다.</p>
 *
 * <p>처리 결과(ACK) 기록은 기능 철회로 제거했다(2026-08-26, V39) - 조회 전용 서비스다.</p>
 */
@ExtendWith(MockitoExtension.class)
class GuardianSosServiceTest {

    @Mock private SosEventRepository sosEventRepository;
    @Mock private ConnectionService connectionService;
    @Mock private UserRepository userRepository;

    @InjectMocks private GuardianSosService guardianSosService;

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";
    private static final String WARD_NAME = "김영희";
    private static final String OTHER_WARD_ID = "WD0002";

    @Test
    @DisplayName("wardId 생략 → ACTIVE 연결된 피보호자 전원의 이력을 최신순으로 병합 조회(이름 매핑)")
    void getHistory_전체_병합조회() {
        OffsetDateTime now = OffsetDateTime.now();
        SosEvent recent = sosEvent(1L, WARD_ID, now, "자택 거실", SosTriggerType.SOS_BUTTON);
        SosEvent older = sosEvent(2L, OTHER_WARD_ID, now.minusDays(1), null, SosTriggerType.GUARDIAN_CALL);

        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID, OTHER_WARD_ID));
        when(sosEventRepository.findByWardIdInOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(recent, older), PageRequest.of(0, 20), 2));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(WARD_ID, WARD_NAME),
                user(OTHER_WARD_ID, "박철수")));

        PageResponse<SosHistoryItem> result = guardianSosService.getHistory(GUARDIAN_ID, null, 0, 20);

        // 인가된 목록만 쿼리에 전달됐는지 (IDOR 방지의 핵심)
        ArgumentCaptor<Collection<String>> wardIdsCaptor = ArgumentCaptor.captor();
        verify(sosEventRepository).findByWardIdInOrderByCreatedAtDesc(wardIdsCaptor.capture(), any(Pageable.class));
        assertThat(wardIdsCaptor.getValue()).containsExactly(WARD_ID, OTHER_WARD_ID);

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.content()).hasSize(2);

        SosHistoryItem first = result.content().get(0);
        assertThat(first.sosEventId()).isEqualTo(1L);
        assertThat(first.wardName()).isEqualTo(WARD_NAME);
        assertThat(first.triggeredAt()).isEqualTo(now);
        assertThat(first.location()).isEqualTo("자택 거실");
        assertThat(first.triggerType()).isEqualTo(SosTriggerType.SOS_BUTTON);

        SosHistoryItem second = result.content().get(1);
        assertThat(second.wardName()).isEqualTo("박철수");
        // 프론트가 위치를 보내지 않은 이력은 null(위치 미상) - 화면에서 위치 줄을 생략한다
        assertThat(second.location()).isNull();
        // 보호자에게 직접 전화한 이력도 같은 목록에 실리되 경로로 구분된다
        assertThat(second.triggerType()).isEqualTo(SosTriggerType.GUARDIAN_CALL);
    }

    @Test
    @DisplayName("wardId 지정 → 그 피보호자 이력만 조회")
    void getHistory_특정피보호자() {
        SosEvent event = sosEvent(1L, WARD_ID, OffsetDateTime.now());
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
        when(sosEventRepository.findByWardIdInOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 20), 1));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(WARD_ID, WARD_NAME)));

        PageResponse<SosHistoryItem> result = guardianSosService.getHistory(GUARDIAN_ID, WARD_ID, 0, 20);

        ArgumentCaptor<Collection<String>> wardIdsCaptor = ArgumentCaptor.captor();
        verify(sosEventRepository).findByWardIdInOrderByCreatedAtDesc(wardIdsCaptor.capture(), any(Pageable.class));
        assertThat(wardIdsCaptor.getValue()).containsExactly(WARD_ID);
        assertThat(result.content()).hasSize(1);
        verify(connectionService, never()).getActiveWardIds(anyString());
    }

    @Test
    @DisplayName("연결(ACTIVE)되지 않은 피보호자 지정 → 403 SOS_NOT_AUTHORIZED, 이력 쿼리 자체를 하지 않음")
    void getHistory_연결없는피보호자_거부() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, OTHER_WARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> guardianSosService.getHistory(GUARDIAN_ID, OTHER_WARD_ID, 0, 20))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOS_NOT_AUTHORIZED);

        verifyNoInteractions(sosEventRepository);
    }

    @Test
    @DisplayName("ACTIVE 연결된 피보호자가 없으면 빈 페이지 반환(쿼리 없음)")
    void getHistory_연결없음_빈페이지() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of());

        PageResponse<SosHistoryItem> result = guardianSosService.getHistory(GUARDIAN_ID, null, 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.size()).isEqualTo(20);
        verifyNoInteractions(sosEventRepository);
    }

    @Test
    @DisplayName("탈퇴한 피보호자의 익명 이력(wardId=null)도 이름 없이 조회된다 - 이름 조회에서 제외")
    void getHistory_탈퇴피보호자_익명이력() {
        SosEvent anonymous = sosEvent(3L, null, OffsetDateTime.now());
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(sosEventRepository.findByWardIdInOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(anonymous), PageRequest.of(0, 20), 1));

        PageResponse<SosHistoryItem> result = guardianSosService.getHistory(GUARDIAN_ID, null, 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).wardId()).isNull();
        assertThat(result.content().get(0).wardName()).isNull();
        // 조회할 ID가 없으므로 사용자 조회 자체를 하지 않는다
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("과대 size·음수 page 요청 → size는 상한 50, page는 0으로 보정")
    void getHistory_페이지파라미터_보정() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(sosEventRepository.findByWardIdInOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        guardianSosService.getHistory(GUARDIAN_ID, null, -5, 500);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.captor();
        verify(sosEventRepository).findByWardIdInOrderByCreatedAtDesc(any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
        // 정렬은 메서드 이름에 고정 - Pageable에 Sort를 넣으면 이중 적용된다
        assertThat(pageableCaptor.getValue().getSort().isSorted()).isFalse();
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────

    private static SosEvent sosEvent(long id, String wardId, OffsetDateTime createdAt) {
        return sosEvent(id, wardId, createdAt, null, null);
    }

    /** id·createdAt은 JPA가 채우는 값이라 테스트에서는 리플렉션으로 주입한다. */
    private static SosEvent sosEvent(long id, String wardId, OffsetDateTime createdAt,
                                     String location, SosTriggerType triggerType) {
        SosEvent event = SosEvent.builder()
                .wardId(wardId)
                .location(location)
                .triggerType(triggerType)
                .build();
        ReflectionTestUtils.setField(event, "id", id);
        ReflectionTestUtils.setField(event, "createdAt", createdAt);
        return event;
    }

    private static User user(String id, String name) {
        return User.builder().id(id).name(name).role(Role.WARD).build();
    }
}
