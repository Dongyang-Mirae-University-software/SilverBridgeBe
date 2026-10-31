package kr.silverbridge.main.support;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MulticastMessage;
import kr.silverbridge.main.domain.admin.client.AiServerHealthClient;
import kr.silverbridge.main.domain.anomaly.client.AiClipClient;
import kr.silverbridge.main.domain.auth.oauth.KakaoOAuthClient;
import kr.silverbridge.main.domain.camera.client.AiStreamClient;
import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.hospital.client.ReservationAdminClient;
import kr.silverbridge.main.domain.medication.client.AiMedicationOcrClient;
import kr.silverbridge.main.domain.notification.service.AlimtalkSender;
import kr.silverbridge.main.global.client.FileServerClient;
import kr.silverbridge.main.global.client.SmsSender;
import kr.silverbridge.main.domain.auth.entity.RefreshToken;
import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.notification.entity.FcmToken;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.service.NotificationSettingService;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import kr.silverbridge.main.domain.notification.repository.FcmTokenRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 통합 테스트 2단계 - <b>애플리케이션 전체</b>를 실제 PostgreSQL·Redis 위에 띄우는 공통 기반 (2026-10-31).
 *
 * <p><b>왜 필요한가</b> - 1단계({@link PostgresIntegrationTest})는 슬라이스에 리스너를 손으로 골라 붙이고 이벤트를 직접
 * 발행한다. 서비스 진입점 → 커밋 → AFTER_COMMIT 리스너(동기·{@code @Async}) → 알림 디스패처 → 채널 → 발송 이력까지
 * 실제 순서와 실제 빈 구성으로 도는지는 여기서만 보인다(09-30 H-1, 10-31 H-2가 이 틈의 버그였다).</p>
 *
 * <p><b>컨텍스트는 한 번만 뜬다</b> - 대체 빈({@code @MockitoBean})과 속성을 <b>이 클래스에만</b>둔다. 하위 클래스가
 * 목이나 속성을 하나라도 더하면 Spring 테스트 컨텍스트 캐시 키가 달라져 클래스마다 앱 전체가 다시 뜬다(수십 초).</p>
 *
 * <p><b>격리</b></p>
 * <ul>
 *   <li>PostgreSQL은 1단계 컨테이너를 그대로 쓰되 <b>전용 DB {@value #DB_NAME}</b>에 붙는다(Flyway V1~ 새로 적용).
 *       1단계 테스트와 데이터·커넥션이 섞이지 않는다.</li>
 *   <li>Redis는 운영과 같은 {@code redis:7.2} 컨테이너 1개. 명령 시간 제한을 500ms로 줄여 Redis 일시 중지 시나리오가
 *       빨리 끝나게 한다(운영은 2초).</li>
 *   <li>외부 서비스(FCM·Solapi·SMTP·카카오·파일서버·AI·예약)는 실제로 부르지 않는다 - 모두 목.
 *       AI 영상 WS 구독은 {@code anomaly.enabled=false}로 접속하지 않는다.</li>
 *   <li>스케줄러는 {@link #disableScheduledMethods()}가 {@code @Scheduled} 처리기를 빼서 하나도 돌지 않는다
 *       (운영 코드 무변경). {@code TaskScheduler} 빈 자체는 남는다.</li>
 * </ul>
 *
 * <p><b>정리</b> - 테스트 트랜잭션을 쓰지 않는다(롤백은 AFTER_COMMIT을 실행하지 않는다). 각 테스트 뒤에 비동기 작업이
 * 모두 끝나기를 기다린 다음 {@value #DB_NAME}의 전 테이블을 비우고 Redis를 {@code FLUSHDB}한다.</p>
 *
 * <p><b>기다리기</b> - {@code @Async} 결과는 Awaitility({@link #eventually()})로 기다린다. {@code Thread.sleep} 금지.</p>
 */
@SpringBootTest(properties = {
        // 필수 설정 검증기를 통과시키는 가짜 값(실제 서비스에 쓰이지 않는다 - 클라이언트는 모두 목)
        "DB_USERNAME=unused", "DB_PASSWORD=unused",
        "MAIL_USERNAME=it@test.local", "MAIL_PASSWORD=unused",
        "KAKAO_REST_API_KEY=it-kakao-rest-key", "KAKAO_REDIRECT_URI=http://localhost/it/callback",
        "SOLAPI_API_KEY=it-solapi-key", "SOLAPI_API_SECRET=it-solapi-secret", "SOLAPI_SENDER_PHONE=01000000000",
        // 외부 연동·백그라운드 작업을 끈다
        "anomaly.enabled=false",
        "anomaly.clip.enabled=false",
        "medication.ocr.enabled=false",
        "medication.reminder.enabled=false",
        "chat.relay.enabled=false",
        "notification.email.enabled=false",
        "notification.alimtalk.enabled=false",
        // JavaMailSender가 목이라 메일 헬스 체크(JavaMailSenderImpl 필요)가 뜨지 못한다
        "management.health.mail.enabled=false",
        "spring.data.redis.timeout=500ms",
        "spring.data.redis.connect-timeout=500ms"
})
public abstract class FullContextIntegrationTest {

    protected static final String DB_NAME = "sb_full";

    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2").withExposedPorts(6379);

    private static final String JDBC_URL;

    static {
        // 1단계 컨테이너를 기동시키고(정적 초기화) 그 안에 전용 DB를 만든다
        String baseUrl = PostgresIntegrationTest.POSTGRES.getJdbcUrl();
        try (Connection c = DriverManager.getConnection(baseUrl,
                PostgresIntegrationTest.POSTGRES.getUsername(), PostgresIntegrationTest.POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + DB_NAME);
        } catch (SQLException e) {
            throw new IllegalStateException("통합 테스트 2단계 전용 DB 생성 실패", e);
        }
        JDBC_URL = baseUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + DB_NAME + "$1");
        REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", PostgresIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresIntegrationTest.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // 길이·자리표시자 검사를 통과하는 무작위 값(실행마다 다르다)
        registry.add("JWT_SECRET", () -> "it-" + UUID.randomUUID() + UUID.randomUUID());
        registry.add("KAKAO_CLIENT_SECRET", () -> "it-" + UUID.randomUUID() + UUID.randomUUID());
        // FcmConfig는 FirebaseMessaging 목으로 대체되지만 필수 설정 검증기는 값이 있는지만 본다
        registry.add("firebase.service-account-base64",
                () -> Base64.getEncoder().encodeToString("{\"type\":\"it-unused\"}".getBytes()));
        Path clipDir = Files.createTempDirectory("sb-it-clips");
        registry.add("anomaly.clip.storage-dir", clipDir::toString);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SchedulingOff {
        /**
         * {@code @EnableScheduling}이 등록한 {@code @Scheduled} 처리기 정의를 지운다. 처리기가 없으면 스케줄 메서드가
         * 등록되지 않는다 - 탈퇴 스윕·복약 알림 같은 주기 작업이 테스트 데이터를 건드리거나 알림을 보내지 않는다.
         * 자동 설정의 {@code TaskScheduler} 빈은 그대로 남는다(조건 평가가 이 후처리기보다 먼저 끝난다).
         */
        @Bean
        static BeanDefinitionRegistryPostProcessor disableScheduledMethods() {
            return new BeanDefinitionRegistryPostProcessor() {
                @Override
                public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                    String name = "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
                    if (registry.containsBeanDefinition(name)) {
                        registry.removeBeanDefinition(name);
                    }
                }
            };
        }
    }

    // ─── 외부 연동 대체(실제 호출 0건) ───────────────────────────────
    @MockitoBean protected FirebaseMessaging firebaseMessaging;
    @MockitoBean protected SmsSender smsSender;
    @MockitoBean protected AlimtalkSender alimtalkSender;
    @MockitoBean protected JavaMailSender javaMailSender;
    @MockitoBean protected KakaoOAuthClient kakaoOAuthClient;
    @MockitoBean protected FileServerClient fileServerClient;
    @MockitoBean protected AiChatClient aiChatClient;
    @MockitoBean protected AiClipClient aiClipClient;
    @MockitoBean protected AiStreamClient aiStreamClient;
    @MockitoBean protected AiMedicationOcrClient aiMedicationOcrClient;
    @MockitoBean protected AiServerHealthClient aiServerHealthClient;
    @MockitoBean protected ReservationAdminClient reservationAdminClient;

    // ─── 실제 동작 그대로 + 호출 기록(2026-11-01, 강제 연결 순서 시나리오) ─────────────
    // WS 신호와 알림 발송의 순서(InOrder)를 보고, 설정 조회 실패를 주입하려고 둔다. 스텁하지 않으면 실제 빈과 같다.
    // 여기(기반 클래스)에만 둔다 - 하위 클래스가 더하면 컨텍스트가 클래스마다 다시 뜬다.
    @MockitoSpyBean protected WebSocketEventPublisher webSocketEventPublisher;
    @MockitoSpyBean protected NotificationDispatcher notificationDispatcher;
    @MockitoSpyBean protected NotificationSettingService notificationSettingService;

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StringRedisTemplate redis;
    @Autowired private Map<String, ThreadPoolTaskExecutor> executors;

    @BeforeEach
    void stubPushAccepted() throws FirebaseMessagingException {
        // FCM 서버가 묶음 전체를 받아 준 것으로 둔다(발송 성공 → 이력 DELIVERED)
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenAnswer(invocation -> {
            BatchResponse response = mock(BatchResponse.class);
            when(response.getSuccessCount()).thenReturn(1);
            when(response.getFailureCount()).thenReturn(0);
            return response;
        });
    }

    @AfterEach
    void cleanUp() {
        awaitAsyncIdle();
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables.stream().map(t -> "\"" + t + "\"").toList())
                    + " RESTART IDENTITY CASCADE");
        }
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        reset(firebaseMessaging, smsSender, alimtalkSender, javaMailSender, kakaoOAuthClient, fileServerClient,
                aiChatClient, aiClipClient, aiStreamClient, aiMedicationOcrClient, aiServerHealthClient,
                reservationAdminClient, webSocketEventPublisher, notificationDispatcher, notificationSettingService);
    }

    /** 모든 {@code @Async} executor가 비기를 기다린다 - 앞 테스트의 알림이 다음 테스트 데이터에 섞이지 않게. */
    protected void awaitAsyncIdle() {
        await().atMost(Duration.ofSeconds(15)).until(() -> executors.values().stream()
                .allMatch(e -> e.getActiveCount() == 0 && e.getThreadPoolExecutor().getQueue().isEmpty()));
    }

    /** {@code @Async} 결과를 기다리는 표준 조건(최대 10초, 100ms 간격). */
    protected static org.awaitility.core.ConditionFactory eventually() {
        return await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100));
    }

    // ─── 데이터 준비(각 저장은 바로 커밋된다 - 테스트 트랜잭션 없음) ────────────────
    protected static final String PASSWORD = "Itpass123!";

    @Autowired protected UserRepository userRepository;
    @Autowired protected ConnectionRepository connectionRepository;
    @Autowired protected RefreshTokenRepository refreshTokenRepository;
    @Autowired protected FcmTokenRepository fcmTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /** 일반 가입(비밀번호 {@link #PASSWORD}) 회원. ID는 6자 이하(users.id VARCHAR(6)). */
    protected User saveUser(String id, Role role, Status status) {
        User user = TestData.user(id, "회원" + id, role);
        user.updatePassword(passwordEncoder.encode(PASSWORD));
        if (status != Status.ACTIVE) {
            jdbcAfterSave(user, status);
            return user;
        }
        return userRepository.save(user);
    }

    private void jdbcAfterSave(User user, Status status) {
        userRepository.save(user);
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", status.name(), user.getId());
    }

    protected Long saveActiveConnection(String guardianId, String wardId) {
        return connectionRepository.save(
                TestData.activeConnection(guardianId, wardId, OffsetDateTime.now().minusDays(1))).getId();
    }

    /** 로그인해 둔 상태(refresh 토큰 1개 + FCM 토큰 1개). */
    protected void signedIn(String userId) {
        refreshTokenRepository.save(RefreshToken.of(userId, "rt-" + userId + "-" + UUID.randomUUID(), 3_600_000));
        fcmTokenRepository.save(FcmToken.of(userId, "fcm-" + userId + "-" + UUID.randomUUID(), "ANDROID"));
    }

    protected int notificationLogs(String recipientId, NotificationType type) {
        return count("SELECT count(*) FROM notification_log WHERE recipient_id = ? AND type = ?",
                recipientId, type.name());
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** Redis 컨테이너를 일시 중지한다(순단 재현). 반드시 {@link #resumeRedis()}와 짝으로 - try/finally. */
    protected static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    protected static void resumeRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
}
