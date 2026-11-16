package kr.silverbridge.main.domain.admin.dto;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AdminConnectionResponseTest {

    @Test
    @DisplayName("관리자 연결 목록의 연결 시각도 같은 순간의 +09:00 표기다 (2026-11-16 점검 L-2, 연결 응답 CONN-A12와 같게)")
    void connectedAtIsKst() {
        OffsetDateTime utc = OffsetDateTime.of(2026, 11, 15, 16, 30, 0, 0, ZoneOffset.UTC); // 한국 11/16 01:30
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        org.mockito.Mockito.when(connection.getConnectedAt()).thenReturn(utc);
        User guardian = User.builder().id("G00001").name("보호자").role(Role.GUARDIAN).build();
        User ward = User.builder().id("W00001").name("피보호자").role(Role.WARD).build();

        AdminConnectionResponse response = AdminConnectionResponse.of(connection, guardian, ward);

        assertThat(response.connectedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(response.connectedAt().toString()).startsWith("2026-11-16T01:30");
        assertThat(response.connectedAt().toInstant()).isEqualTo(utc.toInstant());
    }

    @Test
    @DisplayName("아직 연결되지 않은 행(connectedAt null)은 null 그대로")
    void nullStaysNull() {
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        User guardian = User.builder().id("G00001").name("보호자").role(Role.GUARDIAN).build();
        User ward = User.builder().id("W00001").name("피보호자").role(Role.WARD).build();

        assertThat(AdminConnectionResponse.of(connection, guardian, ward).connectedAt()).isNull();
    }
}
