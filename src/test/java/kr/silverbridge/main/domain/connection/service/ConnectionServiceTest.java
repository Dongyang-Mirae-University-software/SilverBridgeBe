package kr.silverbridge.main.domain.connection.service;

import kr.silverbridge.main.domain.connection.dto.ConnectionRequestDto;
import kr.silverbridge.main.domain.connection.dto.ConnectionResponse;
import kr.silverbridge.main.domain.connection.dto.PendingConnectionResponse;
import kr.silverbridge.main.domain.connection.dto.WardListFilter;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.event.ConnectionAcceptedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionForcedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionRefusedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionRequestedEvent;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConnectionService 단위 테스트.
 *
 * 상태 전이 가드(PENDING/ACTIVE 검증)·소유권(IDOR) 차단·이벤트 발행·조회 매핑을 검증한다.
 *
 * 참고: A2(동시 상태 전이 lost update)에 대한 낙관적 락(@Version, V21)은 DB 레벨에서
 * 동작하므로 Mockito 단위 테스트로는 재현 불가하다(@DataJpaTest 통합 테스트 영역).
 * 여기서는 "가드 실패 시 상태가 바뀌지 않고 이벤트도 발행되지 않는다"는 불변식을 검증하여
 * 상태-알림 불일치를 방지한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectionServiceTest {

    @Mock private ConnectionRepository connectionRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private ConnectionService connectionService;

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";
    private static final String OTHER_WARD_ID = "WD0002";
    private static final long CONNECTION_ID = 100L;
    private static final String RELATION = "아들";
    private static final String GUARDIAN_PHONE = "010-1111-2222";

    // ─── 보호자: 페어링 요청 ────────────────────────────────────────────────

    @Nested
    @DisplayName("requestConnectionAsGuardian")
    class RequestConnection {

        @Test
        @DisplayName("정상 요청 → PENDING 저장 + relation 포함 ConnectionRequestedEvent 발행")
        void 정상요청_저장_및_이벤트발행() {
            ConnectionRequestDto dto = requestDto(WARD_ID, RELATION);
            when(userRepository.findById(GUARDIAN_ID)).thenReturn(java.util.Optional.of(guardian()));
            when(userRepository.findById(WARD_ID)).thenReturn(java.util.Optional.of(ward()));
            when(connectionRepository.existsByGuardianIdAndWardIdAndStatusIn(
                    GUARDIAN_ID, WARD_ID, List.of(ConnectionStatus.PENDING, ConnectionStatus.ACTIVE))).thenReturn(false);

            connectionService.requestConnectionAsGuardian(GUARDIAN_ID, dto);

            ArgumentCaptor<Connection> savedCaptor = ArgumentCaptor.forClass(Connection.class);
            verify(connectionRepository).save(savedCaptor.capture());
            Connection saved = savedCaptor.getValue();
            assertThat(saved.getGuardianId()).isEqualTo(GUARDIAN_ID);
            assertThat(saved.getWardId()).isEqualTo(WARD_ID);
            assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.PENDING);
            assertThat(saved.getInitiatedBy()).isEqualTo(GUARDIAN_ID);
            assertThat(saved.getRelation()).isEqualTo(RELATION);

            ArgumentCaptor<ConnectionRequestedEvent> eventCaptor =
                    ArgumentCaptor.forClass(ConnectionRequestedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            ConnectionRequestedEvent event = eventCaptor.getValue();
            assertThat(event.guardianId()).isEqualTo(GUARDIAN_ID);
            assertThat(event.wardId()).isEqualTo(WARD_ID);
            assertThat(event.guardianName()).isEqualTo("보호자");
            assertThat(event.relation()).isEqualTo(RELATION);
        }

        @Test
        @DisplayName("본인에게 요청 → CANNOT_CONNECT_SELF, 저장·이벤트 없음")
        void 본인연결_CANNOT_CONNECT_SELF() {
            ConnectionRequestDto dto = requestDto(GUARDIAN_ID, RELATION);

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.requestConnectionAsGuardian(GUARDIAN_ID, dto));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CANNOT_CONNECT_SELF);
            verify(connectionRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("존재하지 않는 대상 ID → USER_NOT_FOUND")
        void 대상미존재_USER_NOT_FOUND() {
            ConnectionRequestDto dto = requestDto(WARD_ID, RELATION);
            when(userRepository.findById(GUARDIAN_ID)).thenReturn(java.util.Optional.of(guardian()));
            when(userRepository.findById(WARD_ID)).thenReturn(java.util.Optional.empty());

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.requestConnectionAsGuardian(GUARDIAN_ID, dto));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
            verify(connectionRepository, never()).save(any());
        }

        @Test
        @DisplayName("대상이 탈퇴(INACTIVE) → USER_NOT_FOUND (존재 비노출), 저장 없음")
        void 대상_INACTIVE_USER_NOT_FOUND() {
            ConnectionRequestDto dto = requestDto(WARD_ID, RELATION);
            when(userRepository.findById(GUARDIAN_ID)).thenReturn(java.util.Optional.of(guardian()));
            when(userRepository.findById(WARD_ID)).thenReturn(java.util.Optional.of(inactiveWard()));

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.requestConnectionAsGuardian(GUARDIAN_ID, dto));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
            verify(connectionRepository, never()).save(any());
        }

        @Test
        @DisplayName("요청자가 GUARDIAN이 아님 → INVALID_CONNECTION_ROLE")
        void 요청자역할불일치_INVALID_CONNECTION_ROLE() {
            ConnectionRequestDto dto = requestDto(WARD_ID, RELATION);
            when(userRepository.findById(GUARDIAN_ID)).thenReturn(java.util.Optional.of(ward())); // WARD가 요청

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.requestConnectionAsGuardian(GUARDIAN_ID, dto));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CONNECTION_ROLE);
        }

        @Test
        @DisplayName("대상이 WARD가 아님 → INVALID_CONNECTION_ROLE")
        void 대상역할불일치_INVALID_CONNECTION_ROLE() {
            ConnectionRequestDto dto = requestDto(WARD_ID, RELATION);
            when(userRepository.findById(GUARDIAN_ID)).thenReturn(java.util.Optional.of(guardian()));
            when(userRepository.findById(WARD_ID)).thenReturn(java.util.Optional.of(guardian())); // 대상도 GUARDIAN

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.requestConnectionAsGuardian(GUARDIAN_ID, dto));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CONNECTION_ROLE);
        }

        @Test
        @DisplayName("이미 연결/요청 중인 쌍 → CONNECTION_ALREADY_EXISTS")
        void 중복요청_CONNECTION_ALREADY_EXISTS() {
            ConnectionRequestDto dto = requestDto(WARD_ID, RELATION);
            when(userRepository.findById(GUARDIAN_ID)).thenReturn(java.util.Optional.of(guardian()));
            when(userRepository.findById(WARD_ID)).thenReturn(java.util.Optional.of(ward()));
            when(connectionRepository.existsByGuardianIdAndWardIdAndStatusIn(
                    GUARDIAN_ID, WARD_ID, List.of(ConnectionStatus.PENDING, ConnectionStatus.ACTIVE))).thenReturn(true);

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.requestConnectionAsGuardian(GUARDIAN_ID, dto));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_ALREADY_EXISTS);
            verify(connectionRepository, never()).save(any());
        }
    }

    // ─── 피보호자: 수락 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("acceptConnectionAsWard")
    class AcceptConnection {

        @Test
        @DisplayName("PENDING 수락 → ACTIVE 전환 + ConnectionAcceptedEvent 발행")
        void 정상수락_ACTIVE전환_및_이벤트() {
            Connection connection = connection(ConnectionStatus.PENDING);
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.of(connection));

            connectionService.acceptConnectionAsWard(WARD_ID, CONNECTION_ID);

            assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
            assertThat(connection.getConnectedAt()).isNotNull();

            ArgumentCaptor<ConnectionAcceptedEvent> captor =
                    ArgumentCaptor.forClass(ConnectionAcceptedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().guardianId()).isEqualTo(GUARDIAN_ID);
        }

        @Test
        @DisplayName("존재하지 않는 연결 → CONNECTION_NOT_FOUND")
        void 미존재_CONNECTION_NOT_FOUND() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.empty());

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.acceptConnectionAsWard(WARD_ID, CONNECTION_ID));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_FOUND);
        }

        @Test
        @DisplayName("다른 피보호자가 수락 시도 → CONNECTION_NOT_AUTHORIZED (IDOR 차단)")
        void 타인연결수락_CONNECTION_NOT_AUTHORIZED() {
            Connection connection = connection(ConnectionStatus.PENDING);
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.of(connection));

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.acceptConnectionAsWard(OTHER_WARD_ID, CONNECTION_ID));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_AUTHORIZED);
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("이미 처리된(ACTIVE) 요청 수락 → CONNECTION_NOT_PENDING, 상태 불변·이벤트 없음")
        void 이미처리된요청_CONNECTION_NOT_PENDING() {
            Connection connection = connection(ConnectionStatus.ACTIVE);
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.of(connection));

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.acceptConnectionAsWard(WARD_ID, CONNECTION_ID));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_PENDING);
            assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // ─── 피보호자: 거절 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("refuseConnectionAsWard")
    class RefuseConnection {

        @Test
        @DisplayName("PENDING 거절 → REFUSED 전환 + 보호자에게 ConnectionRefusedEvent 발행")
        void 정상거절_REFUSED전환_및_이벤트() {
            Connection connection = connection(ConnectionStatus.PENDING);
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.of(connection));

            connectionService.refuseConnectionAsWard(WARD_ID, CONNECTION_ID);

            assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.REFUSED);

            ArgumentCaptor<ConnectionRefusedEvent> captor =
                    ArgumentCaptor.forClass(ConnectionRefusedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().connectionId()).isEqualTo(CONNECTION_ID);
            assertThat(captor.getValue().guardianId()).isEqualTo(GUARDIAN_ID);
        }

        @Test
        @DisplayName("PENDING이 아닌 연결 거절 → CONNECTION_NOT_PENDING, 상태 불변·이벤트 없음")
        void 비PENDING거절_CONNECTION_NOT_PENDING() {
            Connection connection = connection(ConnectionStatus.ACTIVE);
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.of(connection));

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.refuseConnectionAsWard(WARD_ID, CONNECTION_ID));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_PENDING);
            assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // ─── 보호자: 요청 취소 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelPendingAsGuardian")
    class CancelPending {

        @Test
        @DisplayName("PENDING 취소 → CANCELLED 전환")
        void 정상취소_CANCELLED() {
            Connection connection = connection(ConnectionStatus.PENDING);
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.of(connection));

            connectionService.cancelPendingAsGuardian(GUARDIAN_ID, CONNECTION_ID);

            assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.CANCELLED);
        }

        @Test
        @DisplayName("PENDING이 아닌 연결 취소 → CONNECTION_NOT_PENDING")
        void 비PENDING취소_CONNECTION_NOT_PENDING() {
            Connection connection = connection(ConnectionStatus.ACTIVE);
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.of(connection));

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.cancelPendingAsGuardian(GUARDIAN_ID, CONNECTION_ID));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_PENDING);
        }
    }

    // ─── 연결 해제 ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("disconnect")
    class Disconnect {

        @Test
        @DisplayName("보호자 해제(ACTIVE) → DISCONNECTED + 피보호자에게 DisconnectedEvent(GUARDIAN)")
        void 보호자해제_DISCONNECTED_및_이벤트() {
            Connection connection = connection(ConnectionStatus.ACTIVE);
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.of(connection));

            connectionService.disconnectAsGuardian(GUARDIAN_ID, CONNECTION_ID);

            assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
            ArgumentCaptor<ConnectionDisconnectedEvent> captor =
                    ArgumentCaptor.forClass(ConnectionDisconnectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().notifyTargetId()).isEqualTo(WARD_ID);
            assertThat(captor.getValue().disconnectedBy())
                    .isEqualTo(ConnectionDisconnectedEvent.DisconnectedBy.GUARDIAN);
        }

        @Test
        @DisplayName("보호자 해제인데 ACTIVE 아님 → CONNECTION_NOT_ACTIVE")
        void 보호자해제_비ACTIVE_CONNECTION_NOT_ACTIVE() {
            Connection connection = connection(ConnectionStatus.PENDING);
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.of(connection));

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.disconnectAsGuardian(GUARDIAN_ID, CONNECTION_ID));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_ACTIVE);
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("피보호자 해제(ACTIVE) → DISCONNECTED + 보호자에게 DisconnectedEvent(WARD)")
        void 피보호자해제_DISCONNECTED_및_이벤트() {
            Connection connection = connection(ConnectionStatus.ACTIVE);
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.of(connection));

            connectionService.disconnectAsWard(WARD_ID, CONNECTION_ID);

            assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
            ArgumentCaptor<ConnectionDisconnectedEvent> captor =
                    ArgumentCaptor.forClass(ConnectionDisconnectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().notifyTargetId()).isEqualTo(GUARDIAN_ID);
            assertThat(captor.getValue().disconnectedBy())
                    .isEqualTo(ConnectionDisconnectedEvent.DisconnectedBy.WARD);
        }

        @Test
        @DisplayName("타인이 피보호자 해제 시도 → CONNECTION_NOT_AUTHORIZED (IDOR 차단)")
        void 타인해제_CONNECTION_NOT_AUTHORIZED() {
            Connection connection = connection(ConnectionStatus.ACTIVE);
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(java.util.Optional.of(connection));

            CustomException ex = assertThrows(CustomException.class,
                    () -> connectionService.disconnectAsWard(OTHER_WARD_ID, CONNECTION_ID));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_AUTHORIZED);
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // ─── 조회 ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("조회 API")
    class Query {

        @Test
        @DisplayName("getActiveGuardians → ACTIVE 보호자만 ward 관점 반환, ACTIVE라 전화번호 노출")
        void getActiveGuardians_ACTIVE만_전화노출() {
            Connection active = connection(ConnectionStatus.ACTIVE);
            when(connectionRepository.findByWardIdAndStatusOrderByCreatedAtAsc(WARD_ID, ConnectionStatus.ACTIVE))
                    .thenReturn(List.of(active));
            when(userRepository.findAllById(anyList())).thenReturn(List.of(guardian()));

            List<ConnectionResponse> result = connectionService.getActiveGuardians(WARD_ID);

            assertThat(result).hasSize(1);
            ConnectionResponse r = result.get(0);
            assertThat(r.getPartnerUserId()).isEqualTo(GUARDIAN_ID);
            assertThat(r.getStatus()).isEqualTo("ACTIVE");
            assertThat(r.getRelation()).isEqualTo(RELATION);
            assertThat(r.getPartnerPhone()).isEqualTo(GUARDIAN_PHONE); // ACTIVE → 노출
            verify(connectionRepository)
                    .findByWardIdAndStatusOrderByCreatedAtAsc(WARD_ID, ConnectionStatus.ACTIVE);
        }

        @Test
        @DisplayName("getPendingRequests → PENDING만, 전화번호 마스킹(원본 미노출)")
        void getPendingRequests_PENDING만_전화마스킹() {
            Connection pending = connection(ConnectionStatus.PENDING);
            when(connectionRepository.findByWardIdAndStatusOrderByCreatedAtDesc(WARD_ID, ConnectionStatus.PENDING))
                    .thenReturn(List.of(pending));
            when(userRepository.findAllById(anyList())).thenReturn(List.of(guardian()));

            List<PendingConnectionResponse> result = connectionService.getPendingRequests(WARD_ID);

            assertThat(result).hasSize(1);
            PendingConnectionResponse r = result.get(0);
            assertThat(r.getGuardianId()).isEqualTo(GUARDIAN_ID);
            assertThat(r.getGuardianName()).isEqualTo("보호자");
            assertThat(r.getRelation()).isEqualTo(RELATION);
            // 수락 전이므로 원본 전화번호가 그대로 노출되면 안 됨 (마스킹 적용)
            assertThat(r.getGuardianPhone()).isNotEqualTo(GUARDIAN_PHONE);
            verify(connectionRepository)
                    .findByWardIdAndStatusOrderByCreatedAtDesc(WARD_ID, ConnectionStatus.PENDING);
        }

        @Test
        @DisplayName("getMyWards(filter=null) → 파라미터 생략은 ACTIVE+PENDING (기존 FE 하위호환), PENDING 항목은 전화번호 null")
        void getMyWards_혼합상태_PENDING은_전화null() {
            Connection activeConn = Connection.builder()
                    .id(1L).guardianId(GUARDIAN_ID).wardId(WARD_ID)
                    .status(ConnectionStatus.ACTIVE).initiatedBy(GUARDIAN_ID).relation(RELATION).build();
            Connection pendingConn = Connection.builder()
                    .id(2L).guardianId(GUARDIAN_ID).wardId(OTHER_WARD_ID)
                    .status(ConnectionStatus.PENDING).initiatedBy(GUARDIAN_ID).relation("딸").build();
            when(connectionRepository.findByGuardianIdAndStatusInOrderByCreatedAtDesc(
                    GUARDIAN_ID, List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING)))
                    .thenReturn(List.of(activeConn, pendingConn));
            when(userRepository.findAllById(anyList()))
                    .thenReturn(List.of(wardWithId(WARD_ID), wardWithId(OTHER_WARD_ID)));

            List<ConnectionResponse> result = connectionService.getMyWards(GUARDIAN_ID, null);

            assertThat(result).hasSize(2);
            ConnectionResponse activeRes = result.stream()
                    .filter(r -> r.getStatus().equals("ACTIVE")).findFirst().orElseThrow();
            ConnectionResponse pendingRes = result.stream()
                    .filter(r -> r.getStatus().equals("PENDING")).findFirst().orElseThrow();
            assertThat(activeRes.getPartnerPhone()).isNotNull();   // ACTIVE → 노출
            assertThat(pendingRes.getPartnerPhone()).isNull();     // PENDING → 미노출
        }

        @Test
        @DisplayName("getMyWards(ALL) → 생략과 같은 상태 집합을 조회한다")
        void getMyWards_ALL은_생략과_동일() {
            when(connectionRepository.findByGuardianIdAndStatusInOrderByCreatedAtDesc(
                    GUARDIAN_ID, List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING)))
                    .thenReturn(List.of());
            when(userRepository.findAllById(anyList())).thenReturn(List.of());

            connectionService.getMyWards(GUARDIAN_ID, WardListFilter.ALL);

            verify(connectionRepository).findByGuardianIdAndStatusInOrderByCreatedAtDesc(
                    GUARDIAN_ID, List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING));
        }

        @Test
        @DisplayName("getMyWards(ACTIVE) → ACTIVE만 조회하고 PENDING은 목록에 섞이지 않는다")
        void getMyWards_ACTIVE만_조회() {
            Connection activeConn = Connection.builder()
                    .id(1L).guardianId(GUARDIAN_ID).wardId(WARD_ID)
                    .status(ConnectionStatus.ACTIVE).initiatedBy(GUARDIAN_ID).relation(RELATION).build();
            when(connectionRepository.findByGuardianIdAndStatusInOrderByCreatedAtDesc(
                    GUARDIAN_ID, List.of(ConnectionStatus.ACTIVE)))
                    .thenReturn(List.of(activeConn));
            when(userRepository.findAllById(anyList())).thenReturn(List.of(wardWithId(WARD_ID)));

            List<ConnectionResponse> result = connectionService.getMyWards(GUARDIAN_ID, WardListFilter.ACTIVE);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("ACTIVE");
            verify(connectionRepository).findByGuardianIdAndStatusInOrderByCreatedAtDesc(
                    GUARDIAN_ID, List.of(ConnectionStatus.ACTIVE));
        }

        @Test
        @DisplayName("getMyWards(PENDING) → PENDING만 조회하고 전화번호는 여전히 null (필터로 마스킹이 풀리지 않는다)")
        void getMyWards_PENDING만_조회_전화번호는_여전히null() {
            Connection pendingConn = Connection.builder()
                    .id(2L).guardianId(GUARDIAN_ID).wardId(OTHER_WARD_ID)
                    .status(ConnectionStatus.PENDING).initiatedBy(GUARDIAN_ID).relation("딸").build();
            when(connectionRepository.findByGuardianIdAndStatusInOrderByCreatedAtDesc(
                    GUARDIAN_ID, List.of(ConnectionStatus.PENDING)))
                    .thenReturn(List.of(pendingConn));
            when(userRepository.findAllById(anyList())).thenReturn(List.of(wardWithId(OTHER_WARD_ID)));

            List<ConnectionResponse> result = connectionService.getMyWards(GUARDIAN_ID, WardListFilter.PENDING);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("PENDING");
            assertThat(result.get(0).getPartnerPhone()).isNull();
            assertThat(result.get(0).getPartnerAddress()).isNull();
        }

        @Test
        @DisplayName("WardListFilter는 종료 상태(REFUSED·CANCELLED·DISCONNECTED)를 조회 대상으로 삼지 않는다")
        void 필터는_종료상태를_포함하지_않는다() {
            assertThat(WardListFilter.values())
                    .allSatisfy(filter -> assertThat(filter.toStatuses())
                            .containsAnyOf(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING)
                            .doesNotContain(ConnectionStatus.REFUSED,
                                    ConnectionStatus.CANCELLED,
                                    ConnectionStatus.DISCONNECTED));
        }
    }

    // ─── 관리자 강제 연결·해제 ────────────────────────────────────

    @Nested
    @DisplayName("forceConnect / forceDisconnect")
    class AdminForce {

        @Test
        @DisplayName("수락 대기 중인 요청이 있으면 새로 만들지 않고 그것을 승격시킨다")
        void PENDING이_있으면_승격시킨다() {
            Connection pending = Connection.builder()
                    .id(7L).guardianId(GUARDIAN_ID).wardId(WARD_ID)
                    .status(ConnectionStatus.PENDING).initiatedBy(GUARDIAN_ID).relation(RELATION).build();
            when(connectionRepository.findByParticipantAndStatusIn(
                    GUARDIAN_ID, List.of(ConnectionStatus.PENDING))).thenReturn(List.of(pending));

            Connection result = connectionService.forceConnect(
                    GUARDIAN_ID, WARD_ID, "AD0001", "홍길동", "박민수");

            // 시니어가 수락 버튼을 못 눌러 요청만 떠 있는 경우 - 관리자가 대신 수락해 주는 셈이다
            assertThat(result.getId()).isEqualTo(7L);
            assertThat(pending.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
            verify(connectionRepository, never()).save(any(Connection.class));
        }

        @Test
        @DisplayName("수락 대기 요청이 없으면 새로 만들어 바로 연결한다")
        void PENDING이_없으면_새로_만든다() {
            when(connectionRepository.findByParticipantAndStatusIn(
                    GUARDIAN_ID, List.of(ConnectionStatus.PENDING))).thenReturn(List.of());
            when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));

            Connection result = connectionService.forceConnect(
                    GUARDIAN_ID, WARD_ID, "AD0001", "홍길동", "박민수");

            assertThat(result.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
            verify(connectionRepository).save(any(Connection.class));
        }

        @Test
        @DisplayName("강제 연결은 양쪽 모두에게 알리는 이벤트를 발행한다")
        void 강제연결_양쪽_알림() {
            when(connectionRepository.findByParticipantAndStatusIn(
                    GUARDIAN_ID, List.of(ConnectionStatus.PENDING))).thenReturn(List.of());
            when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));

            connectionService.forceConnect(GUARDIAN_ID, WARD_ID, "AD0001", "홍길동", "박민수");

            ArgumentCaptor<ConnectionForcedEvent> captor =
                    ArgumentCaptor.forClass(ConnectionForcedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            // 피보호자는 수락한 적이 없어 자기도 모르게 연결된 것이라 반드시 알아야 한다
            assertThat(captor.getValue().guardianId()).isEqualTo(GUARDIAN_ID);
            assertThat(captor.getValue().wardId()).isEqualTo(WARD_ID);
            assertThat(captor.getValue().wardName()).isEqualTo("박민수");
        }

        @Test
        @DisplayName("강제 해제는 DISCONNECTED로 바꾸고 양쪽에 ADMIN 주체로 알린다")
        void 강제해제_양쪽_알림() {
            Connection active = Connection.builder()
                    .id(9L).guardianId(GUARDIAN_ID).wardId(WARD_ID)
                    .status(ConnectionStatus.ACTIVE).initiatedBy(GUARDIAN_ID).relation(RELATION).build();
            when(connectionRepository.findById(9L)).thenReturn(Optional.of(active));

            connectionService.forceDisconnect(9L, "AD0001");

            assertThat(active.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
            ArgumentCaptor<ConnectionDisconnectedEvent> captor =
                    ArgumentCaptor.forClass(ConnectionDisconnectedEvent.class);
            verify(eventPublisher, times(2)).publishEvent(captor.capture());
            // 둘 다 자기가 끊지 않았으므로 한쪽만 알리면 나머지는 모른다
            assertThat(captor.getAllValues()).extracting(ConnectionDisconnectedEvent::notifyTargetId)
                    .containsExactlyInAnyOrder(GUARDIAN_ID, WARD_ID);
            assertThat(captor.getAllValues()).allSatisfy(e ->
                    assertThat(e.disconnectedBy())
                            .isEqualTo(ConnectionDisconnectedEvent.DisconnectedBy.ADMIN));
        }

        @Test
        @DisplayName("ACTIVE가 아닌 연결은 강제 해제할 수 없다")
        void 비활성_연결은_해제_불가() {
            Connection pending = Connection.builder()
                    .id(9L).guardianId(GUARDIAN_ID).wardId(WARD_ID)
                    .status(ConnectionStatus.PENDING).initiatedBy(GUARDIAN_ID).build();
            when(connectionRepository.findById(9L)).thenReturn(Optional.of(pending));

            assertThrows(CustomException.class, () -> connectionService.forceDisconnect(9L, "AD0001"));
            assertThat(pending.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        }
    }

    // ─── 역할 변경 연계 정리 (관리자 회원관리) ────────────────────────────────

    @Nested
    @DisplayName("tearDownConnectionsOnRoleChange")
    class TearDownOnRoleChange {

        @Test
        @DisplayName("ACTIVE 연결은 DISCONNECTED가 되고 상대에게 알림이 나간다 - 실제로 연결돼 있던 사람은 알아야 한다")
        void ACTIVE는_해제되고_상대에게_알린다() {
            Connection active = Connection.builder()
                    .id(1L).guardianId(GUARDIAN_ID).wardId(WARD_ID)
                    .status(ConnectionStatus.ACTIVE).initiatedBy(GUARDIAN_ID).relation(RELATION).build();
            when(connectionRepository.findByParticipantAndStatusIn(
                    GUARDIAN_ID, List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING)))
                    .thenReturn(List.of(active));

            int cleared = connectionService.tearDownConnectionsOnRoleChange(GUARDIAN_ID);

            assertThat(cleared).isEqualTo(1);
            // CANCELLED가 아니라 DISCONNECTED여야 한다 - CANCELLED는 "보호자가 수락 전 요청을 스스로 취소"라는 뜻이다
            assertThat(active.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
            ArgumentCaptor<ConnectionDisconnectedEvent> captor =
                    ArgumentCaptor.forClass(ConnectionDisconnectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().notifyTargetId()).isEqualTo(WARD_ID);
        }

        @Test
        @DisplayName("PENDING 연결은 CANCELLED가 되고 알림은 나가지 않는다 - 수락 전 요청 종료는 기존에도 무알림이다")
        void PENDING은_조용히_취소된다() {
            Connection pending = Connection.builder()
                    .id(2L).guardianId(GUARDIAN_ID).wardId(WARD_ID)
                    .status(ConnectionStatus.PENDING).initiatedBy(GUARDIAN_ID).relation(RELATION).build();
            when(connectionRepository.findByParticipantAndStatusIn(
                    WARD_ID, List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING)))
                    .thenReturn(List.of(pending));

            int cleared = connectionService.tearDownConnectionsOnRoleChange(WARD_ID);

            assertThat(cleared).isEqualTo(1);
            assertThat(pending.getStatus()).isEqualTo(ConnectionStatus.CANCELLED);
            verify(eventPublisher, never()).publishEvent(any(ConnectionDisconnectedEvent.class));
        }

        @Test
        @DisplayName("정리할 연결이 없으면 0을 반환하고 아무 이벤트도 발행하지 않는다")
        void 연결이_없으면_0건() {
            when(connectionRepository.findByParticipantAndStatusIn(
                    WARD_ID, List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING)))
                    .thenReturn(List.of());

            assertThat(connectionService.tearDownConnectionsOnRoleChange(WARD_ID)).isZero();
            verify(eventPublisher, never()).publishEvent(any(ConnectionDisconnectedEvent.class));
        }
    }

    // ─── 회원 탈퇴 연계 정리 (D-USER-3) ───────────────────────────────────────

    @Nested
    @DisplayName("tearDownConnectionsOnWithdrawal")
    class TearDownOnWithdrawal {

        @Test
        @DisplayName("탈퇴자가 보호자인 ACTIVE 연결 → DISCONNECTED + 피보호자에게 알림(GUARDIAN)")
        void 보호자탈퇴_ACTIVE해제_피보호자알림() {
            Connection active = Connection.builder()
                    .id(1L).guardianId(GUARDIAN_ID).wardId(WARD_ID)
                    .status(ConnectionStatus.ACTIVE).initiatedBy(GUARDIAN_ID).relation(RELATION).build();
            when(connectionRepository.findByParticipantAndStatusIn(
                    GUARDIAN_ID, List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING)))
                    .thenReturn(List.of(active));

            connectionService.tearDownConnectionsOnWithdrawal(GUARDIAN_ID);

            assertThat(active.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
            ArgumentCaptor<ConnectionDisconnectedEvent> captor =
                    ArgumentCaptor.forClass(ConnectionDisconnectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().notifyTargetId()).isEqualTo(WARD_ID);
            assertThat(captor.getValue().disconnectedBy())
                    .isEqualTo(ConnectionDisconnectedEvent.DisconnectedBy.GUARDIAN);
        }

        @Test
        @DisplayName("탈퇴자가 피보호자인 ACTIVE 연결 → DISCONNECTED + 보호자에게 알림(WARD)")
        void 피보호자탈퇴_ACTIVE해제_보호자알림() {
            Connection active = Connection.builder()
                    .id(1L).guardianId(GUARDIAN_ID).wardId(WARD_ID)
                    .status(ConnectionStatus.ACTIVE).initiatedBy(GUARDIAN_ID).relation(RELATION).build();
            when(connectionRepository.findByParticipantAndStatusIn(
                    WARD_ID, List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING)))
                    .thenReturn(List.of(active));

            connectionService.tearDownConnectionsOnWithdrawal(WARD_ID);

            assertThat(active.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
            ArgumentCaptor<ConnectionDisconnectedEvent> captor =
                    ArgumentCaptor.forClass(ConnectionDisconnectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().notifyTargetId()).isEqualTo(GUARDIAN_ID);
            assertThat(captor.getValue().disconnectedBy())
                    .isEqualTo(ConnectionDisconnectedEvent.DisconnectedBy.WARD);
        }

        @Test
        @DisplayName("PENDING 연결 → CANCELLED, 알림 없음")
        void PENDING_취소_무알림() {
            Connection pending = Connection.builder()
                    .id(2L).guardianId(GUARDIAN_ID).wardId(WARD_ID)
                    .status(ConnectionStatus.PENDING).initiatedBy(GUARDIAN_ID).relation(RELATION).build();
            when(connectionRepository.findByParticipantAndStatusIn(
                    WARD_ID, List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING)))
                    .thenReturn(List.of(pending));

            connectionService.tearDownConnectionsOnWithdrawal(WARD_ID);

            assertThat(pending.getStatus()).isEqualTo(ConnectionStatus.CANCELLED);
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("정리할 연결 없음 → no-op, 이벤트 없음")
        void 연결없음_noop() {
            when(connectionRepository.findByParticipantAndStatusIn(
                    WARD_ID, List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING)))
                    .thenReturn(List.of());

            connectionService.tearDownConnectionsOnWithdrawal(WARD_ID);

            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // ─── 헬퍼 ───────────────────────────────────────────────────────────────

    private ConnectionRequestDto requestDto(String targetId, String relation) {
        ConnectionRequestDto dto = org.mockito.Mockito.mock(ConnectionRequestDto.class);
        when(dto.getTargetId()).thenReturn(targetId);
        when(dto.getRelation()).thenReturn(relation);
        return dto;
    }

    private Connection connection(ConnectionStatus status) {
        return Connection.builder()
                .id(CONNECTION_ID)
                .guardianId(GUARDIAN_ID)
                .wardId(WARD_ID)
                .status(status)
                .initiatedBy(GUARDIAN_ID)
                .relation(RELATION)
                .build();
    }

    private User guardian() {
        return User.builder()
                .id(GUARDIAN_ID).email("guardian@example.com").name("보호자")
                .role(Role.GUARDIAN).status(Status.ACTIVE).provider(Provider.LOCAL)
                .phone(GUARDIAN_PHONE).address("서울시 강남구").addressDetail("101동 1001호")
                .profileImage("https://img/g.png")
                .build();
    }

    private User ward() {
        return wardWithId(WARD_ID);
    }

    private User wardWithId(String id) {
        return User.builder()
                .id(id).email(id + "@example.com").name("피보호자")
                .role(Role.WARD).status(Status.ACTIVE).provider(Provider.LOCAL)
                .phone("010-3333-4444").address("서울시 송파구").addressDetail("202호")
                .profileImage("https://img/w.png")
                .build();
    }

    private User inactiveWard() {
        return User.builder()
                .id(WARD_ID).email("inactive@example.com").name("탈퇴피보호자")
                .role(Role.WARD).status(Status.INACTIVE).provider(Provider.LOCAL)
                .build();
    }
}
