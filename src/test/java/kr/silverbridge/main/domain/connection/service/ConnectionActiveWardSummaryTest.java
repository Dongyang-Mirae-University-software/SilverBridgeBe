package kr.silverbridge.main.domain.connection.service;

import kr.silverbridge.main.domain.connection.dto.ActiveWardSummary;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 챗봇 예약 대상 선택지용 ACTIVE 피보호자 이름·관계 목록(2026-11-11). */
@ExtendWith(MockitoExtension.class)
class ConnectionActiveWardSummaryTest {

    private static final String GUARDIAN = "GRD001";

    @Mock private ConnectionRepository connectionRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private ConnectionService connectionService;

    private static Connection active(String wardId, String relation) {
        return Connection.builder().guardianId(GUARDIAN).wardId(wardId).relation(relation)
                .status(ConnectionStatus.ACTIVE).build();
    }

    private static User user(String id, String name) {
        return User.builder().id(id).name(name).phone("010-1111-2222").address("서울특별시 강남구").build();
    }

    @Test
    @DisplayName("ACTIVE 연결만 조회하고, 최신 연결순으로 이름·관계를 돌려준다(빈 관계는 null)")
    void ACTIVE만_이름_관계() {
        when(connectionRepository.findByGuardianIdAndStatusInOrderByCreatedAtDesc(GUARDIAN, List.of(ConnectionStatus.ACTIVE)))
                .thenReturn(List.of(active("WRD002", "아버지"), active("WRD001", " ")));
        when(userRepository.findAllById(any())).thenReturn(List.of(user("WRD001", "김영희"), user("WRD002", "김철수")));

        List<ActiveWardSummary> result = connectionService.getActiveWardSummaries(GUARDIAN, 20);

        assertThat(result).containsExactly(
                new ActiveWardSummary("WRD002", "김철수", "아버지"),
                new ActiveWardSummary("WRD001", "김영희", null));
    }

    @Test
    @DisplayName("ACTIVE 피보호자가 없으면 빈 목록이고 회원 조회를 하지 않는다")
    void 없으면_빈_목록() {
        when(connectionRepository.findByGuardianIdAndStatusInOrderByCreatedAtDesc(GUARDIAN, List.of(ConnectionStatus.ACTIVE)))
                .thenReturn(List.of());

        assertThat(connectionService.getActiveWardSummaries(GUARDIAN, 20)).isEmpty();
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("회원 행이 없는 연결은 빼고, 상한을 넘으면 최신 연결부터 자른다")
    void 행_없음_제외_상한() {
        when(connectionRepository.findByGuardianIdAndStatusInOrderByCreatedAtDesc(GUARDIAN, List.of(ConnectionStatus.ACTIVE)))
                .thenReturn(List.of(active("WRD003", "어머니"), active("WRD002", "아버지"), active("WRD001", "할머니")));
        when(userRepository.findAllById(any())).thenReturn(List.of(user("WRD003", "박순자")));

        List<ActiveWardSummary> result = connectionService.getActiveWardSummaries(GUARDIAN, 2);

        assertThat(result).containsExactly(new ActiveWardSummary("WRD003", "박순자", "어머니"));
    }
}
