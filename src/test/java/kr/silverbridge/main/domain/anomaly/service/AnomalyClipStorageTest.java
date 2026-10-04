package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipStorage.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 클립 파일 저장소 - 원자적 쓰기, 이름 형식·경로 이탈 방어, 청소용 목록. 실제 임시 디렉터리를 쓴다. */
class AnomalyClipStorageTest {

    @TempDir
    Path tempDir;

    private Path root;
    private AnomalyClipStorage storage;
    private AnomalyProperties properties;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("clips");
        properties = new AnomalyProperties();
        properties.getClip().setStorageDir(root.toString());
        properties.getClip().setMinFreeDiskMb(0);
        storage = new AnomalyClipStorage(properties);
    }

    @Test
    @DisplayName("쓰면 UUID.webm 이름으로 저장 루트 바로 아래에 생기고, 임시 파일은 남지 않는다")
    void 쓰기() throws IOException {
        String name = storage.write(new byte[]{1, 2, 3});

        assertThat(AnomalyClipStorage.isValidName(name)).isTrue();
        assertThat(storage.find(name)).get().satisfies(path -> {
            assertThat(path.getParent()).isEqualTo(root.toAbsolutePath().normalize());
            assertThat(Files.readAllBytes(path)).containsExactly(1, 2, 3);
        });
        try (var files = Files.list(root)) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    @DisplayName("삭제하면 사라지고, 없는 파일 삭제는 조용히 넘어간다")
    void 삭제() {
        String name = storage.write(new byte[]{1});

        storage.delete(name);
        storage.delete(name);

        assertThat(storage.find(name)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../secret.webm",
            "../../etc/passwd",
            "/etc/passwd",
            "a/b.webm",
            "00000000-0000-0000-0000-000000000000.webm/../../x",
            "00000000-0000-0000-0000-000000000000.mp4",
            "",
            "..%2Fx.webm"
    })
    @DisplayName("UUID.webm 형식이 아닌 이름(경로 이탈 시도 포함)은 찾지도 지우지도 않는다")
    void 경로_이탈_거부(String malicious) throws IOException {
        Path outside = tempDir.resolve("secret.webm");
        Files.write(outside, new byte[]{9});

        assertThat(storage.find(malicious)).isEmpty();
        storage.delete(malicious);

        assertThat(outside).exists();
    }

    @Test
    @DisplayName("청소용 목록은 클립·임시 파일만 담고, 그 밖의 파일은 건드리지 않는다")
    void 목록() throws IOException {
        String clip = storage.write(new byte[]{1});
        Files.write(root.resolve(".tmp-123.part"), new byte[]{1});
        Files.write(root.resolve("README.txt"), new byte[]{1});

        assertThat(storage.listFiles())
                .extracting(StoredFile::name, StoredFile::temporary)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(clip, false),
                        org.assertj.core.groups.Tuple.tuple(".tmp-123.part", true));

        storage.deleteTemporary(".tmp-123.part");
        storage.deleteTemporary("../README.txt");
        assertThat(root.resolve(".tmp-123.part")).doesNotExist();
        assertThat(root.resolve("README.txt")).exists();
    }

    @Test
    @DisplayName("저장 루트가 아직 없으면 목록은 비어 있다")
    void 루트_없음() {
        assertThat(storage.listFiles()).isEmpty();
    }

    @Test
    @DisplayName("여유 공간이 하한보다 적으면 false")
    void 여유공간() {
        assertThat(storage.hasEnoughSpace()).isTrue();

        properties.getClip().setMinFreeDiskMb(Long.MAX_VALUE / (1024 * 1024));

        assertThat(storage.hasEnoughSpace()).isFalse();
    }
}
