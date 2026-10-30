package kr.silverbridge.main.global;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.auth.entity.AccessLog;
import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.audit.AdminAuditLog;
import kr.silverbridge.main.global.audit.AdminAuditLogRepository;
import kr.silverbridge.main.global.enums.AccessAction;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개인정보 보관 정리 쿼리(2026-10-31 전체 점검 D-2) - 실제 FK SET NULL로 익명이 된 행만 비우는지, 기준보다 오래된 행만 지우는지.
 */
class RetentionQueryIntegrationTest extends PostgresIntegrationTest {

    private static final String LEAVER = "WRT001";
    private static final String STAYER = "WRT002";
    private static final String ADMIN = "ART001";

    @Autowired private AccessLogRepository accessLogRepository;
    @Autowired private AdminAuditLogRepository auditLogRepository;
    @Autowired private SosEventRepository sosEventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(LEAVER, "탈퇴할피보호자", Role.WARD));
        userRepository.save(TestData.user(STAYER, "남는피보호자", Role.WARD));
        userRepository.save(TestData.user(ADMIN, "관리자", Role.ADMIN));
        entityManager.flush();
    }

    @Test
    @DisplayName("접속 로그 - 탈퇴(FK SET NULL)로 익명이 된 행의 IP·UA만 비우고, 오래된 행만 지운다")
    void accessLog() {
        Long leaverLog = accessLogRepository.saveAndFlush(new AccessLog(LEAVER, AccessAction.LOGIN, "1.2.3.4", "UA")).getId();
        Long stayerLog = accessLogRepository.saveAndFlush(new AccessLog(STAYER, AccessAction.LOGIN, "5.6.7.8", "UA")).getId();
        Long oldLog = accessLogRepository.saveAndFlush(new AccessLog(STAYER, AccessAction.LOGOUT, "5.6.7.8", "UA")).getId();
        setCreatedAt("access_log", oldLog, OffsetDateTime.now().minusDays(91));
        deleteUser(LEAVER);

        assertThat(accessLogRepository.anonymizeWithdrawn()).isEqualTo(1);
        assertThat(accessLogRepository.deleteOlderThan(OffsetDateTime.now().minusDays(90))).isEqualTo(1);
        entityManager.clear();

        AccessLog anonymized = accessLogRepository.findById(leaverLog).orElseThrow();
        assertThat(anonymized.getIpAddress()).isNull();
        assertThat(anonymized.getUserAgent()).isNull();
        assertThat(accessLogRepository.findById(stayerLog).orElseThrow().getIpAddress()).isEqualTo("5.6.7.8");
        assertThat(accessLogRepository.findById(oldLog)).isEmpty();
    }

    @Test
    @DisplayName("감사 로그 - 기준보다 오래된 행만 지운다")
    void auditLog() {
        Long recent = auditLogRepository.saveAndFlush(AdminAuditLog.builder().adminId(ADMIN)
                .action(AdminAuditAction.USER_NAME_CHANGE).targetId(STAYER).detail("a").build()).getId();
        Long old = auditLogRepository.saveAndFlush(AdminAuditLog.builder().adminId(ADMIN)
                .action(AdminAuditAction.USER_NAME_CHANGE).targetId(STAYER).detail("b").build()).getId();
        setCreatedAt("admin_audit_log", old, OffsetDateTime.now().minusDays(366));

        assertThat(auditLogRepository.deleteOlderThan(OffsetDateTime.now().minusDays(365))).isEqualTo(1);
        entityManager.clear();
        assertThat(auditLogRepository.findById(recent)).isPresent();
        assertThat(auditLogRepository.findById(old)).isEmpty();
    }

    @Test
    @DisplayName("SOS - 탈퇴로 익명이 된 이력의 위치만 비우고 이력 자체는 남긴다")
    void sosLocation() {
        Long leaverSos = sosEventRepository.saveAndFlush(SosEvent.builder().wardId(LEAVER).location("안방 침대 옆")
                .triggerType(SosTriggerType.SOS_BUTTON).build()).getId();
        Long stayerSos = sosEventRepository.saveAndFlush(SosEvent.builder().wardId(STAYER).location("거실")
                .triggerType(SosTriggerType.SOS_BUTTON).build()).getId();
        deleteUser(LEAVER);

        assertThat(sosEventRepository.clearLocationOfWithdrawn()).isEqualTo(1);
        entityManager.clear();
        assertThat(sosEventRepository.findById(leaverSos).orElseThrow().getLocation()).isNull();
        assertThat(sosEventRepository.findById(stayerSos).orElseThrow().getLocation()).isEqualTo("거실");
    }

    private void deleteUser(String userId) {
        entityManager.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", userId).executeUpdate();
        entityManager.clear();
    }

    private void setCreatedAt(String table, Long id, OffsetDateTime at) {
        entityManager.createNativeQuery("UPDATE " + table + " SET created_at = :at WHERE id = :id")
                .setParameter("at", at).setParameter("id", id).executeUpdate();
    }
}
