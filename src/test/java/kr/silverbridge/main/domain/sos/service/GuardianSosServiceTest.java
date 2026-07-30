package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.sos.dto.SosAckRequest;
import kr.silverbridge.main.domain.sos.dto.SosHistoryItem;
import kr.silverbridge.main.domain.sos.entity.SosAckStatus;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.event.SosAcknowledgedEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * GuardianSosService 단위 테스트.
 *
 * <p>핵심은 <b>인가</b>다 — 보호자는 ACTIVE 연결된 피보호자의 이력만 보고 처리할 수 있어야 한다(IDOR 차단).
 * 그 밖에 ① 다중 피보호자 이력 병합·이름 매핑 ② 페이지 크기 상한 ③ ACK 기록·재기록·이벤트 발행
 * ④ 탈퇴 피보호자 이력 접근 차단 ⑤ 메모 정규화를 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class GuardianSosServiceTest {

    @Mock private SosEventRepository sosEventRepository;
    @Mock private ConnectionService connectionService;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private GuardianSosService guardianSosService;

    private static final String GUARDIAN_ID = "GD0001";
    private static final String GUARDIAN_NAME = "남궁명진";
    private static final String WARD_ID = "WD0001";
    private static final String WARD_NAME = "김영희";
    private static final String OTHER_WARD_ID = "WD0002";

    // ─── 이력 조회 ────────────────────────────────────────────────

    @Test
    @DisplayName("wardId 생략 → ACTIVE 연결된 피보호자 전원의 이력을 최신순으로 병합 조회(이름·처리결과 매핑)")
    void getHistory_전체_병합조회() {
        OffsetDateTime now = OffsetDateTime.now();
        SosEvent unacked = sosEvent(1L, WARD_ID, now);
        SosEvent acked = sosEvent(2L, OTHER_WARD_ID, now.minusDays(1));
        acked.acknowledge(GUARDIAN_ID, SosAckStatus.EMERGENCY_DISPATCHED, "119 출동 · 병원 이송");

        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID, OTHER_WARD_ID));
        when(sosEventRepository.findByWardIdInOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(unacked, acked), PageRequest.of(0, 20), 2));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(WARD_ID, WARD_NAME, Role.WARD),
                user(OTHER_WARD_ID, "박철수", Role.WARD),
                user(GUARDIAN_ID, GUARDIAN_NAME, Role.GUARDIAN)));

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
        assertThat(first.ackStatus()).isNull();
        assertThat(first.acknowledgedByName()).isNull();
        assertThat(first.acknowledgedAt()).isNull();

        SosHistoryItem second = result.content().get(1);
        assertThat(second.wardName()).isEqualTo("박철수");
        assertThat(second.ackStatus()).isEqualTo(SosAckStatus.EMERGENCY_DISPATCHED);
        assertThat(second.ackNote()).isEqualTo("119 출동 · 병원 이송");
        assertThat(second.acknowledgedByName()).isEqualTo(GUARDIAN_NAME);
        assertThat(second.acknowledgedAt()).isNotNull();
    }

    @Test
    @DisplayName("wardId 지정 → 그 피보호자 이력만 조회")
    void getHistory_특정피보호자() {
        SosEvent event = sosEvent(1L, WARD_ID, OffsetDateTime.now());
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
        when(sosEventRepository.findByWardIdInOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 20), 1));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(WARD_ID, WARD_NAME, Role.WARD)));

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
        // 정렬은 메서드 이름에 고정 — Pageable에 Sort를 넣으면 이중 적용된다
        assertThat(pageableCaptor.getValue().getSort().isSorted()).isFalse();
    }

    // ─── 처리 결과(ACK) 기록 ───────────────────────────────────────

    @Test
    @DisplayName("ACK 기록 → 이력에 결과·보호자·시각 저장 + SosAcknowledgedEvent 발행 + 갱신된 항목 반환")
    void acknowledge_기록_이벤트발행() {
        SosEvent event = sosEvent(7L, WARD_ID, OffsetDateTime.now());
        when(sosEventRepository.findById(7L)).thenReturn(Optional.of(event));
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(WARD_ID, WARD_NAME, Role.WARD),
                user(GUARDIAN_ID, GUARDIAN_NAME, Role.GUARDIAN)));

        SosHistoryItem result = guardianSosService.acknowledge(GUARDIAN_ID, 7L,
                new SosAckRequest(SosAckStatus.SAFE_CONFIRMED, "통화 연결 · 안전 확인"));

        assertThat(event.getAckStatus()).isEqualTo(SosAckStatus.SAFE_CONFIRMED);
        assertThat(event.getAckBy()).isEqualTo(GUARDIAN_ID);
        assertThat(event.getAckNote()).isEqualTo("통화 연결 · 안전 확인");
        assertThat(event.getAckAt()).isNotNull();
        assertThat(event.isAcknowledged()).isTrue();

        ArgumentCaptor<SosAcknowledgedEvent> publishCaptor = ArgumentCaptor.captor();
        verify(eventPublisher).publishEvent(publishCaptor.capture());
        SosAcknowledgedEvent published = publishCaptor.getValue();
        assertThat(published.sosEventId()).isEqualTo(7L);
        assertThat(published.wardId()).isEqualTo(WARD_ID);
        assertThat(published.guardianId()).isEqualTo(GUARDIAN_ID);
        assertThat(published.guardianName()).isEqualTo(GUARDIAN_NAME);
        assertThat(published.ackStatus()).isEqualTo(SosAckStatus.SAFE_CONFIRMED);

        assertThat(result.sosEventId()).isEqualTo(7L);
        assertThat(result.wardName()).isEqualTo(WARD_NAME);
        assertThat(result.acknowledgedByName()).isEqualTo(GUARDIAN_NAME);
    }

    @Test
    @DisplayName("이미 처리된 이력도 덮어쓴다(재ACK 허용) — 안전 확인 → 응급 출동")
    void acknowledge_재기록_덮어쓰기() {
        SosEvent event = sosEvent(7L, WARD_ID, OffsetDateTime.now());
        event.acknowledge("GD9999", SosAckStatus.SAFE_CONFIRMED, "이전 메모");
        when(sosEventRepository.findById(7L)).thenReturn(Optional.of(event));
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(WARD_ID, WARD_NAME, Role.WARD),
                user(GUARDIAN_ID, GUARDIAN_NAME, Role.GUARDIAN)));

        guardianSosService.acknowledge(GUARDIAN_ID, 7L,
                new SosAckRequest(SosAckStatus.EMERGENCY_DISPATCHED, null));

        assertThat(event.getAckStatus()).isEqualTo(SosAckStatus.EMERGENCY_DISPATCHED);
        assertThat(event.getAckBy()).isEqualTo(GUARDIAN_ID);
        assertThat(event.getAckNote()).isNull();
    }

    @Test
    @DisplayName("없는 sosEventId → 404 SOS_EVENT_NOT_FOUND")
    void acknowledge_없는이력_404() {
        when(sosEventRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guardianSosService.acknowledge(GUARDIAN_ID, 999L,
                new SosAckRequest(SosAckStatus.SAFE_CONFIRMED, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOS_EVENT_NOT_FOUND);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("연결(ACTIVE)되지 않은 피보호자의 이력 ACK → 403, 기록·이벤트 없음")
    void acknowledge_연결없는피보호자_거부() {
        SosEvent event = sosEvent(7L, OTHER_WARD_ID, OffsetDateTime.now());
        when(sosEventRepository.findById(7L)).thenReturn(Optional.of(event));
        when(connectionService.isActiveConnection(GUARDIAN_ID, OTHER_WARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> guardianSosService.acknowledge(GUARDIAN_ID, 7L,
                new SosAckRequest(SosAckStatus.SAFE_CONFIRMED, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOS_NOT_AUTHORIZED);

        assertThat(event.isAcknowledged()).isFalse();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("피보호자 탈퇴로 wardId가 비워진 익명 이력 → 403 (연결 판정 자체를 시도하지 않음)")
    void acknowledge_탈퇴피보호자이력_거부() {
        SosEvent event = sosEvent(7L, null, OffsetDateTime.now());
        when(sosEventRepository.findById(7L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> guardianSosService.acknowledge(GUARDIAN_ID, 7L,
                new SosAckRequest(SosAckStatus.SAFE_CONFIRMED, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOS_NOT_AUTHORIZED);

        verifyNoInteractions(connectionService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("공백만 입력한 메모는 null로 저장 — \"메모 있음\"으로 보이지 않게 정규화")
    void acknowledge_공백메모_null() {
        SosEvent event = sosEvent(7L, WARD_ID, OffsetDateTime.now());
        when(sosEventRepository.findById(7L)).thenReturn(Optional.of(event));
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(WARD_ID, WARD_NAME, Role.WARD),
                user(GUARDIAN_ID, GUARDIAN_NAME, Role.GUARDIAN)));

        guardianSosService.acknowledge(GUARDIAN_ID, 7L,
                new SosAckRequest(SosAckStatus.SAFE_CONFIRMED, "   "));

        assertThat(event.getAckNote()).isNull();
    }

    @Test
    @DisplayName("보호자 이름이 비어 있으면 이벤트에 폴백('보호자')을 담는다 — WS 페이로드 null 방지")
    void acknowledge_이름없는보호자_폴백() {
        SosEvent event = sosEvent(7L, WARD_ID, OffsetDateTime.now());
        when(sosEventRepository.findById(7L)).thenReturn(Optional.of(event));
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(WARD_ID, WARD_NAME, Role.WARD),
                user(GUARDIAN_ID, "", Role.GUARDIAN)));

        guardianSosService.acknowledge(GUARDIAN_ID, 7L,
                new SosAckRequest(SosAckStatus.SAFE_CONFIRMED, null));

        ArgumentCaptor<SosAcknowledgedEvent> publishCaptor = ArgumentCaptor.captor();
        verify(eventPublisher).publishEvent(publishCaptor.capture());
        assertThat(publishCaptor.getValue().guardianName()).isEqualTo("보호자");
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────

    /** id·createdAt은 JPA가 채우는 값이라 테스트에서는 리플렉션으로 주입한다. */
    private static SosEvent sosEvent(long id, String wardId, OffsetDateTime createdAt) {
        SosEvent event = SosEvent.builder().wardId(wardId).build();
        ReflectionTestUtils.setField(event, "id", id);
        ReflectionTestUtils.setField(event, "createdAt", createdAt);
        return event;
    }

    private static User user(String id, String name, Role role) {
        return User.builder().id(id).name(name).role(role).build();
    }
}
