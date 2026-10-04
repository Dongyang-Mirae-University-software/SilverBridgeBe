package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 이상감지 클립 파일 저장소(로컬 디스크).
 *
 * <p><b>경로는 서버가 만든 UUID 이름만</b> 쓴다 - 사용자 입력·AI 응답 값이 경로에 섞이지 않는다. 그래도 이름을 경로로
 * 바꿀 때마다 형식 검사 + {@code normalize} 후 저장 루트 안인지 다시 확인한다(DB 값이 오염돼도 루트 밖을 못 건드린다).</p>
 *
 * <p>쓰기는 <b>임시 파일 → 원자적 이동</b>이다. 쓰다 만 파일이 정식 이름으로 보이지 않게 하고, 남은 임시 파일은
 * 청소 스케줄러가 고아로 회수한다.</p>
 */
@Slf4j
@Component
public class AnomalyClipStorage {

    static final String EXTENSION = ".webm";
    private static final String TEMP_PREFIX = ".tmp-";
    private static final Pattern FILE_NAME = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.webm$");
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final AnomalyProperties properties;

    public AnomalyClipStorage(AnomalyProperties properties) {
        this.properties = properties;
    }

    /** 디스크에 있는 파일 1개(청소용). {@code temporary}면 쓰다 남은 임시 파일이다. */
    public record StoredFile(String name, Instant modifiedAt, boolean temporary) {}

    /** 저장 루트의 여유 공간이 하한 이상인가. 루트가 없거나 확인이 안 되면 false(만들지 않는 쪽). */
    public boolean hasEnoughSpace() {
        try {
            Path root = ensureRoot();
            long usable = Files.getFileStore(root).getUsableSpace();
            return usable >= properties.getClip().getMinFreeDiskMb() * BYTES_PER_MB;
        } catch (IOException | RuntimeException e) {
            log.error("[ANOMALY-CLIP] 저장 루트 여유 공간 확인 실패: error={}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 바이트를 새 UUID 이름으로 저장하고 그 이름을 돌려준다.
     *
     * @throws UncheckedIOException 쓰기 실패(임시 파일은 지운다)
     */
    public String write(byte[] bytes) {
        String fileName = UUID.randomUUID() + EXTENSION;
        Path temp = null;
        try {
            Path root = ensureRoot();
            Path target = resolve(fileName);
            temp = Files.createTempFile(root, TEMP_PREFIX, ".part");
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 같은 디렉터리라 사실상 일어나지 않는다. 지원하지 않는 파일시스템이면 일반 이동으로 대신한다.
                Files.move(temp, target);
            }
            return fileName;
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException(e);
        }
    }

    /** 파일이 있으면 그 경로. 이름 형식이 틀리면(오염된 행) 빈 값. */
    public Optional<Path> find(String fileName) {
        if (!isValidName(fileName)) {
            log.warn("[ANOMALY-CLIP] 파일 이름 형식 오류 - 무시");
            return Optional.empty();
        }
        Path path = resolve(fileName);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /** 파일을 지운다. 없으면 무시. 실패는 삼킨다 - 남은 파일은 다음 청소의 고아 회수가 맡는다. */
    public void delete(String fileName) {
        if (!isValidName(fileName)) {
            log.warn("[ANOMALY-CLIP] 파일 이름 형식 오류 - 삭제 건너뜀");
            return;
        }
        deleteQuietly(resolve(fileName));
    }

    /** 저장 루트의 클립·임시 파일 목록(하위 디렉터리·그 밖의 파일은 건드리지 않는다). 루트가 없으면 빈 목록. */
    public List<StoredFile> listFiles() {
        Path root = root();
        List<StoredFile> files = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                boolean temporary = name.startsWith(TEMP_PREFIX);
                if (temporary || isValidName(name)) {
                    files.add(new StoredFile(name, Files.getLastModifiedTime(path).toInstant(), temporary));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /** 청소용 - 쓰다 남은 임시 파일을 지운다. */
    public void deleteTemporary(String name) {
        if (name == null || !name.startsWith(TEMP_PREFIX) || name.contains("/") || name.contains("\\")) {
            return;
        }
        deleteQuietly(inside(root().resolve(name)));
    }

    static boolean isValidName(String fileName) {
        return fileName != null && FILE_NAME.matcher(fileName).matches();
    }

    private Path resolve(String fileName) {
        if (!isValidName(fileName)) {
            throw new IllegalArgumentException("invalid clip file name");
        }
        return inside(root().resolve(fileName));
    }

    /** 정규화한 경로가 저장 루트 바로 아래인지 확인한다. */
    private Path inside(Path candidate) {
        Path root = root();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || !root.equals(normalized.getParent())) {
            throw new IllegalArgumentException("clip path escapes storage root");
        }
        return normalized;
    }

    private Path root() {
        return Paths.get(properties.getClip().getStorageDir()).toAbsolutePath().normalize();
    }

    private Path ensureRoot() throws IOException {
        Path root = root();
        Files.createDirectories(root);
        return root;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[ANOMALY-CLIP] 파일 삭제 실패 - 다음 청소에서 재시도: error={}", e.getClass().getSimpleName());
        }
    }
}
