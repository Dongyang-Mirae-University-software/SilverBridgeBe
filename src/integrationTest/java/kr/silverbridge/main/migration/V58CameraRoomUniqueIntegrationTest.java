package kr.silverbridge.main.migration;

import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * V58(한 피보호자의 같은 방에는 카메라 1대) 검증.
 *
 * <p>빈 DB에는 V58이 이미 적용돼 있으므로, 중복 상황은 테스트 트랜잭션 안에서 제약을 지운 뒤 만들고 V58 스크립트를 다시
 * 실행한다(V55 테스트와 같은 방식, PostgreSQL DDL은 롤백된다). 실패를 검증하는 테스트는 트랜잭션이 중단 상태가 되므로
 * 그 검증이 마지막 동작이다. {@code camera.ward_id}는 {@code users} FK(V29)라 피보호자 회원을 먼저 저장한다.</p>
 */
class V58CameraRoomUniqueIntegrationTest extends PostgresIntegrationTest {

    private static final Path V58 = Path.of("src/main/resources/db/migration/V58__camera_unique_room_per_ward.sql");

    @Autowired private CameraRepository cameraRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private void ward(String id) {
        userRepository.saveAndFlush(TestData.user(id, "피보호자", Role.WARD));
    }

    @Test
    @DisplayName("다른 피보호자는 같은 방 이름을 쓸 수 있고, 같은 피보호자의 다른 방도 된다")
    void 허용되는_조합() {
        ward("V58A01");
        ward("V58A02");
        cameraRepository.saveAndFlush(TestData.camera("V58A01", "ward_V58A01_a", "거실"));
        cameraRepository.saveAndFlush(TestData.camera("V58A02", "ward_V58A02_a", "거실"));
        cameraRepository.saveAndFlush(TestData.camera("V58A01", "ward_V58A01_b", "주방"));

        assertThat(cameraRepository.findByWardIdAndLabel("V58A01", "거실")).isPresent();
        assertThat(cameraRepository.findByWardIdAndLabel("V58A02", "거실")).isPresent();
        assertThat(cameraRepository.findByWardIdAndLabel("V58A01", "주방")).isPresent();
    }

    @Test
    @DisplayName("같은 피보호자의 같은 방 두 번째 카메라는 uq_camera_ward_label이 막는다 - 서비스가 이 이름으로 409를 판정한다")
    void 같은방_두번째_거절() {
        ward("V58B01");
        cameraRepository.saveAndFlush(TestData.camera("V58B01", "ward_V58B01_a", "거실"));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> cameraRepository.saveAndFlush(TestData.camera("V58B01", "ward_V58B01_b", "거실")))
                .satisfies(e -> assertThat(NestedExceptionUtils.getMostSpecificCause(e).getMessage())
                        .contains("uq_camera_ward_label"));
    }

    @Test
    @DisplayName("기존에 같은 방 중복이 있으면 데이터를 바꾸지 않고 명확한 메시지로 중단한다")
    void 중복있으면_중단() throws IOException {
        ward("V58C01");
        jdbcTemplate.execute("ALTER TABLE camera DROP CONSTRAINT uq_camera_ward_label");
        cameraRepository.saveAndFlush(TestData.camera("V58C01", "ward_V58C01_a", "거실"));
        cameraRepository.saveAndFlush(TestData.camera("V58C01", "ward_V58C01_b", "거실"));

        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> jdbcTemplate.execute(Files.readString(V58)))
                .withMessageContaining("같은 방에 카메라가 2대 이상이라 마이그레이션을 중단합니다")
                .withMessageContaining("V58C01:거실");
    }

    @Test
    @DisplayName("중복이 없으면 제약을 만든다")
    void 중복없으면_제약생성() throws IOException {
        ward("V58D01");
        jdbcTemplate.execute("ALTER TABLE camera DROP CONSTRAINT uq_camera_ward_label");
        cameraRepository.saveAndFlush(TestData.camera("V58D01", "ward_V58D01_a", "거실"));

        jdbcTemplate.execute(Files.readString(V58));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from pg_constraint where conname = 'uq_camera_ward_label'", Integer.class))
                .isEqualTo(1);
    }
}
