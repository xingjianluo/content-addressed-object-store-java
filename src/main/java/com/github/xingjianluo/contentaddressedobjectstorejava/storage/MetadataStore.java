package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON metadata files for objects and snapshots, written atomically
 * (temp file + ATOMIC_MOVE) so readers never observe a torn document.
 *
 * <pre>
 *   root/objects/&lt;urlencoded-key&gt;.json   per-object version history
 *   root/snapshots/&lt;snapshotId&gt;.json     immutable snapshot manifests
 * </pre>
 */
public class MetadataStore {

    private static final Logger log = LoggerFactory.getLogger(MetadataStore.class);

    private final Path objectsDir;
    private final Path snapshotsDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetadataStore(Path rootDir) {
        try {
            this.objectsDir = rootDir.resolve("objects");
            this.snapshotsDir = rootDir.resolve("snapshots");
            Files.createDirectories(objectsDir);
            Files.createDirectories(snapshotsDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot initialize metadata store at " + rootDir, e);
        }
    }

    static String encodeKey(String objectKey) {
        return URLEncoder.encode(objectKey, StandardCharsets.UTF_8);
    }

    Path objectFile(String objectKey) {
        return objectsDir.resolve(encodeKey(objectKey) + ".json");
    }

    Path snapshotFile(String snapshotId) {
        return snapshotsDir.resolve(snapshotId + ".json");
    }

    /** Atomically write a value to a JSON file (write tmp then atomic move). */
    public synchronized void writeJson(Path target, Object value) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + Thread.currentThread().getId());
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing metadata file " + target, e);
        }
    }

    public <T> T readJson(Path file, Class<T> type) throws IOException {
        return mapper.readValue(file.toFile(), type);
    }

    public void deleteObjectFile(String objectKey) {
        try {
            Files.deleteIfExists(objectFile(objectKey));
        } catch (IOException e) {
            throw new UncheckedIOException("failed deleting metadata for " + objectKey, e);
        }
    }

    public boolean deleteSnapshotFile(String snapshotId) throws IOException {
        return Files.deleteIfExists(snapshotFile(snapshotId));
    }

    /** @return all object metadata files present on disk. */
    public List<Path> listObjectFiles() {
        return listJson(objectsDir);
    }

    public List<Path> listSnapshotFiles() {
        return listJson(snapshotsDir);
    }

    private static List<Path> listJson(Path dir) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : files) {
                if (Files.isRegularFile(p)) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list metadata dir " + dir, e);
        }
        return out;
    }
}
