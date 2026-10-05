package kr.silverbridge.main.domain.anomaly;

import kr.silverbridge.main.domain.anomaly.client.AiClipClient.ClipMeta;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;
import kr.silverbridge.main.domain.anomaly.listener.AnomalyClipCleanupListener;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyClipRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipCleanupScheduler;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipService;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipService.StoredClip;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipStorage;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalyService;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.camera.service.CameraIdentifierFactory;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionRequestLimiter;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.domain.user.service.UserIdGenerator;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이상감지 클립 - 실제 DB에서만 드러나는 것들(2026-10-04).
 *
 * <ul>
 *   <li>판정 → 비공개·복구가 판정 트랜잭션과 함께 커밋되는가(벌크 UPDATE + CHECK {@code (status=HIDDEN) = (hidden_at NOT NULL)})</li>
 *   <li>오탐 응답과 클립 기록이 동시에 와도 오탐 상황의 클립이 공개로 남지 않는가(상황 행 쓰기 잠금)</li>
 *   <li>탈퇴·카메라 삭제 리스너(동기 AFTER_COMMIT)의 행 삭제가 실제로 커밋되는가(REQUIRES_NEW, H-1) + 파일 삭제</li>
 *   <li>청소의 조건부 삭제가 번복으로 돌아온 클립을 지우지 않는가</li>
 *   <li>매시 고아 청소가 실제 DB 파일 목록·실제 파일 수정 시각으로 1시간 경계를 지키는가(2026-10-05 QA 종합 점검)</li>
 * </ul>
 *
 * <p>테스트 트랜잭션을 끄고 실제로 커밋한다. 전용 ID를 쓰고 끝나면 회원 삭제(CASCADE)로 정리한다.</p>
 */
@Import({
        GuardianAnomalyService.class,
        AnomalyClipService.class,
        AnomalyClipStorage.class,
        AnomalyClipCleanupListener.class,
        AnomalyClipCleanupScheduler.class,
        ConnectionService.class,
        CameraService.class,
        CameraIdentifierFactory.class,
        UserIdGenerator.class
})
@EnableConfigurationProperties(AnomalyProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnomalyClipIntegrationTest extends PostgresIntegrationTest {

    private static final String GUARDIAN_A = "GCL001";
    private static final String GUARDIAN_B = "GCL002";
    private static final String WARD_ID = "WCL001";
    private static final String SESSION = "sess-clip";
    private static final int ROUNDS = 20;
    private static final Path STORAGE = createStorage();

    @DynamicPropertySource
    static void clipStorage(DynamicPropertyRegistry registry) {
        registry.add("anomaly.clip.storage-dir", STORAGE::toString);
    }

    private static Path createStorage() {
        try {
            return Files.createTempDirectory("anomaly-clip-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private GuardianAnomalyService guardianAnomalyService;
    @Autowired private AnomalyClipService clipService;
    @Autowired private AnomalyClipStorage storage;
    @Autowired private AnomalyClipCleanupScheduler cleanupScheduler;
    @Autowired private CameraService cameraService;
    @Autowired private AnomalyClipRepository clipRepository;
    @Autowired private AnomalyIncidentRepository incidentRepository;
    @Autowired private CameraRepository cameraRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // ConnectionService의 의존성일 뿐 이 테스트의 관심사가 아니다(Redis 없이 뜨도록 목으로 둔다)
    @MockitoBean private ConnectionRequestLimiter connectionRequestLimiter;

    private Long cameraId;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(GUARDIAN_A, "보호자A", Role.GUARDIAN));
        userRepository.save(TestData.user(GUARDIAN_B, "보호자B", Role.GUARDIAN));
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
        for (String guardianId : List.of(GUARDIAN_A, GUARDIAN_B)) {
            connectionRepository.save(Connection.builder()
                    .guardianId(guardianId)
                    .wardId(WARD_ID)
                    .status(ConnectionStatus.ACTIVE)
                    .initiatedBy(guardianId)
                    .build());
        }
        cameraId = cameraRepository.save(TestData.camera(WARD_ID, SESSION, "거실")).getId();
    }

    @AfterEach
    void tearDown() {
        // 연결·카메라·상황·클립 행은 회원 삭제의 CASCADE로 함께 지워진다
        userRepository.deleteAllById(List.of(GUARDIAN_A, GUARDIAN_B, WARD_ID));
    }

    @Test
    @DisplayName("오탐 확정 → 클립 비공개(hidden_at 기록), 번복 → 복구(hidden_at 해제)")
    void 판정에_따른_비공개와_복구() {
        Long incidentId = openIncident();
        Long clipId = recordClip(incidentId).getId();

        guardianAnomalyService.submitFeedback(GUARDIAN_A, incidentId, AnomalyVerdict.FALSE_ALARM);
        AnomalyClip hidden = clipRepository.findById(clipId).orElseThrow();
        assertThat(hidden.getStatus()).isEqualTo(AnomalyClipStatus.HIDDEN);
        assertThat(hidden.getHiddenAt()).isNotNull();
        assertThat(clipService.findViewable(clipId)).isEmpty();

        // 다른 보호자가 반대로 답해 동수(CONFLICTED) - 동수는 숨기지 않는다
        guardianAnomalyService.submitFeedback(GUARDIAN_B, incidentId, AnomalyVerdict.REAL);
        AnomalyClip restored = clipRepository.findById(clipId).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(AnomalyClipStatus.VISIBLE);
        assertThat(restored.getHiddenAt()).isNull();
        assertThat(clipService.findViewable(clipId)).isPresent();
    }

    @Test
    @DisplayName("오탐 판정 뒤 저장된 클립은 공개로 남고, 다시 오탐으로 답하면 그때까지의 클립이 숨겨진다(L-2)")
    void 오탐판정_이후_클립은_공개() {
        Long incidentId = openIncident();
        Long before = recordClip(incidentId).getId();
        guardianAnomalyService.submitFeedback(GUARDIAN_A, incidentId, AnomalyVerdict.FALSE_ALARM);

        Long after = recordClip(incidentId).getId();

        assertThat(clipRepository.findById(before).orElseThrow().getStatus()).isEqualTo(AnomalyClipStatus.HIDDEN);
        assertThat(clipRepository.findById(after).orElseThrow().getStatus()).isEqualTo(AnomalyClipStatus.VISIBLE);

        guardianAnomalyService.submitFeedback(GUARDIAN_B, incidentId, AnomalyVerdict.FALSE_ALARM);
        assertThat(clipRepository.findById(after).orElseThrow().getStatus()).isEqualTo(AnomalyClipStatus.HIDDEN);
    }

    @Test
    @DisplayName("오탐 응답과 클립 기록이 동시에 와도 한 줄로 선다 - 판정보다 먼저 저장된 클립은 반드시 숨겨진다(상황 행 잠금)")
    void 오탐응답과_클립기록_동시() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                Long incidentId = openIncident();
                CyclicBarrier start = new CyclicBarrier(2);

                CompletableFuture<?> feedback = CompletableFuture.runAsync(() -> {
                    await(start);
                    guardianAnomalyService.submitFeedback(GUARDIAN_A, incidentId, AnomalyVerdict.FALSE_ALARM);
                }, pool);
                CompletableFuture<AnomalyClip> clip = CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return recordClip(incidentId);
                }, pool);
                CompletableFuture.allOf(feedback, clip).get(30, TimeUnit.SECONDS);

                AnomalyClip saved = clipRepository.findById(clip.get().getId()).orElseThrow();
                OffsetDateTime decidedAt = incidentRepository.findById(incidentId).orElseThrow().getUpdatedAt();
                // 판정이 먼저면 클립은 판정 뒤에 저장돼 공개, 클립이 먼저면 판정이 숨긴다 - 판정보다 이른 공개 클립은 없어야 한다
                if (saved.getStatus() == AnomalyClipStatus.VISIBLE) {
                    assertThat(saved.getCreatedAt()).as("round %d", round).isAfterOrEqualTo(decidedAt);
                } else {
                    assertThat(saved.getHiddenAt()).as("round %d", round).isAfterOrEqualTo(saved.getCreatedAt());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("피보호자 탈퇴 커밋 후 리스너가 클립 행 삭제를 커밋하고 파일도 지운다")
    void 탈퇴_정리() {
        AnomalyClip clip = recordClip(openIncident());

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new UserWithdrawnEvent(WARD_ID, "127.0.0.1", "test")));

        assertThat(clipRepository.findById(clip.getId())).isEmpty();
        assertThat(storage.find(clip.getFileName())).isEmpty();
    }

    @Test
    @DisplayName("클립 기록과 피보호자 purge가 동시에 와도 교착 없이 끝나고 클립 행이 남지 않는다(L-1 - 잠금 순서 users → 상황)")
    void 클립기록과_탈퇴purge_동시() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String wardId = String.format("WP%04d", round);
                String session = "sess-purge-" + round;
                userRepository.save(TestData.user(wardId, "탈퇴피보호자", Role.WARD));
                cameraRepository.save(TestData.camera(wardId, session, "거실"));
                Long incidentId = incidentRepository.save(AnomalyIncident.builder()
                        .wardId(wardId).sessionId(session).detectedType(DetectedType.FIRE)
                        .detectedAt(OffsetDateTime.now()).confidence(0.8).build()).getId();
                String fileName = storage.write(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
                CyclicBarrier start = new CyclicBarrier(2);

                CompletableFuture<?> record = CompletableFuture.runAsync(() -> {
                    await(start);
                    clipService.record(new StoredClip(incidentId, wardId, session, fileName, 4,
                            new ClipMeta(5000, 25, 1920, 1080, null), OffsetDateTime.now()));
                }, pool);
                CompletableFuture<?> purge = CompletableFuture.runAsync(() -> {
                    await(start);
                    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                            jdbcTemplate.update("delete from users where id = ?", wardId));
                }, pool);

                // 교착이면 PostgreSQL이 한쪽을 중단시켜 예외가 난다 - 둘 다 정상 종료해야 한다
                CompletableFuture.allOf(record, purge).get(30, TimeUnit.SECONDS);

                assertThat(userRepository.findById(wardId)).as("round %d", round).isEmpty();
                assertThat(clipRepository.findByWardId(wardId)).as("round %d", round).isEmpty();
                storage.delete(fileName);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("보호자 탈퇴는 피보호자 클립을 건드리지 않는다")
    void 보호자_탈퇴는_무관() {
        AnomalyClip clip = recordClip(openIncident());

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new UserWithdrawnEvent(GUARDIAN_A, "127.0.0.1", "test")));

        assertThat(clipRepository.findById(clip.getId())).isPresent();
        assertThat(storage.find(clip.getFileName())).isPresent();
    }

    @Test
    @DisplayName("카메라 삭제 커밋 후 리스너가 그 카메라의 클립 행·파일을 지운다(상황 이력은 남는다)")
    void 카메라삭제_정리() {
        Long incidentId = openIncident();
        AnomalyClip clip = recordClip(incidentId);

        cameraService.delete(WARD_ID, cameraId);

        assertThat(clipRepository.findById(clip.getId())).isEmpty();
        assertThat(storage.find(clip.getFileName())).isEmpty();
        assertThat(incidentRepository.findById(incidentId)).isPresent();
    }

    @Test
    @DisplayName("역할 변경 일괄 삭제도 클립을 지운다")
    void 일괄삭제_정리() {
        AnomalyClip clip = recordClip(openIncident());

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                cameraService.deleteAllByWard(WARD_ID));

        assertThat(clipRepository.findById(clip.getId())).isEmpty();
        assertThat(storage.find(clip.getFileName())).isEmpty();
    }

    @Test
    @DisplayName("조건부 삭제 - 공개로 돌아온 클립은 지우지 않고, 유예가 지난 비공개만 지운다")
    void 조건부_삭제() {
        Long incidentId = openIncident();
        AnomalyClip clip = recordClip(incidentId);
        OffsetDateTime future = OffsetDateTime.now().plusDays(1);

        assertThat(clipRepository.deleteHiddenById(clip.getId(), future)).as("공개 클립").isZero();

        guardianAnomalyService.submitFeedback(GUARDIAN_A, incidentId, AnomalyVerdict.FALSE_ALARM);
        assertThat(clipRepository.deleteHiddenById(clip.getId(), OffsetDateTime.now().minusHours(24)))
                .as("유예 전").isZero();
        assertThat(clipRepository.deleteHiddenById(clip.getId(), future)).as("유예 후").isOne();
    }

    @Test
    @DisplayName("매시 고아 청소 - 행이 사라진 지 61분 된 파일만 지우고, 59분 파일·행 있는 파일은 둔다(스윕 purge 잔존 회수, L-3)")
    void 고아_파일_1시간_경계() throws IOException {
        Long incidentId = openIncident();
        AnomalyClip kept = recordClip(incidentId);
        AnomalyClip orphanOld = recordClip(incidentId);
        AnomalyClip orphanFresh = recordClip(incidentId);
        // 리스너를 거치지 않은 행 삭제(스윕 purge의 FK CASCADE와 같은 효과) - 파일만 남는다
        jdbcTemplate.update("DELETE FROM anomaly_clip WHERE id IN (?, ?)", orphanOld.getId(), orphanFresh.getId());
        age(kept.getFileName(), Duration.ofHours(3));
        age(orphanOld.getFileName(), Duration.ofMinutes(61));
        age(orphanFresh.getFileName(), Duration.ofMinutes(59));

        cleanupScheduler.cleanupOrphanFiles();

        assertThat(storage.find(orphanOld.getFileName())).as("행 없음 + 1시간 경과").isEmpty();
        assertThat(storage.find(orphanFresh.getFileName())).as("1시간 전 - 저장 중일 수 있어 보호").isPresent();
        assertThat(storage.find(kept.getFileName())).as("행이 있으면 오래돼도 둔다").isPresent();
        assertThat(clipRepository.findById(kept.getId())).isPresent();
    }

    private static void age(String fileName, Duration age) throws IOException {
        Files.setLastModifiedTime(STORAGE.resolve(fileName), FileTime.from(Instant.now().minus(age)));
    }

    @Test
    @DisplayName("카메라가 없는 세션의 클립은 기록하지 않는다")
    void 카메라없으면_기록안함() {
        Long incidentId = openIncident();
        cameraRepository.deleteById(cameraId);

        assertThat(clipService.record(stored(incidentId, storage.write(new byte[]{0x1A})))).isEmpty();
    }

    private AnomalyClip recordClip(Long incidentId) {
        String fileName = storage.write(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
        return clipService.record(stored(incidentId, fileName)).orElseThrow();
    }

    private static StoredClip stored(Long incidentId, String fileName) {
        return new StoredClip(incidentId, WARD_ID, SESSION, fileName, 4,
                new ClipMeta(5000, 25, 1920, 1080, OffsetDateTime.now()), OffsetDateTime.now());
    }

    private Long openIncident() {
        return incidentRepository.save(AnomalyIncident.builder()
                .wardId(WARD_ID)
                .sessionId(SESSION)
                .detectedType(DetectedType.FIRE)
                .detectedAt(OffsetDateTime.now())
                .confidence(0.8)
                .build()).getId();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
