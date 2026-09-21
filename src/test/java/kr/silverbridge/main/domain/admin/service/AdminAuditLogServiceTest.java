package kr.silverbridge.main.domain.admin.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.silverbridge.main.domain.admin.entity.AdminAuditLog;
import kr.silverbridge.main.domain.admin.repository.AdminAuditLogRepository;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 감사 로그의 detail(이름·이메일 등 PII)은 DB에만 남고 애플리케이션 로그로는 나가지 않는다
 * (2026-09-11 기술 점검 E-2). 컨테이너 stdout은 로그 수집 경로라 개인정보가 새는 지점이다.
 */
@ExtendWith(MockitoExtension.class)
class AdminAuditLogServiceTest {

    @Mock private AdminAuditLogRepository auditLogRepository;
    @InjectMocks private AdminAuditLogService service;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(AdminAuditLogService.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("detail은 DB에 저장되지만 SLF4J 로그에는 나가지 않는다")
    void detail은_로그에_없다() {
        service.log("AD0001", AdminAuditAction.USER_FORCE_DELETE, "EE81BF", "강제 탈퇴: 홍길동 (hong@example.com)");

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getDetail()).contains("hong@example.com");

        assertThat(appender.list).hasSize(1);
        String line = appender.list.get(0).getFormattedMessage();
        assertThat(line).contains("AD0001", "USER_FORCE_DELETE", "EE81BF");
        assertThat(line).doesNotContain("hong@example.com", "홍길동");
    }
}
