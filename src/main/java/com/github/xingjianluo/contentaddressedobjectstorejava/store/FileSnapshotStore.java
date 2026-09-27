package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.exception.SnapshotNotFoundException;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 快照存储的文件实现。
 *
 * <p>每个快照是 snapshots/ 下一个不可变 JSON 文件，记录创建时刻
 * “对象 -> (版本号, 内容哈希)”的映射。之后对象继续更新不会影响快照；
 * 快照引用的内容哈希是 GC 的保留根。</p>
 */
public class FileSnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(FileSnapshotStore.class);

    private final StoreLayout layout;

    public FileSnapshotStore(StoreLayout layout) {
        this.layout = layout;
    }

    @Override
    public void init() throws IOException {
        Files.createDirectories(layout.snapshotsDir());
    }

    @Override
    public String create(Map<String, Long> objectVersions, Map<String, ContentHash> objectHashes)
            throws IOException {
        String snapshotId = "snap-" + System.currentTimeMillis()
                + "-" + Integer.toHexString(System.identityHashCode(objectVersions));
        Map<String, Entry> entries = new HashMap<>();
        for (Map.Entry<String, Long> e : objectVersions.entrySet()) {
            ContentHash hash = objectHashes.get(e.getKey());
            entries.put(e.getKey(), new Entry(e.getValue(), hash == null ? null : hash.value()));
        }
        SnapshotFile file = new SnapshotFile(snapshotId, System.currentTimeMillis(), entries);
        StoreLayout.writeAtomically(layout.snapshotPath(snapshotId), JsonCodec.write(file));
        log.info("[snapshot] CREATE id={} objects={}", snapshotId, entries.size());
        return snapshotId;
    }

    @Override
    public Optional<Long> versionOf(String snapshotId, String objectKey) throws IOException {
        SnapshotFile snap = load(snapshotId);
        Entry e = snap.entries.get(objectKey);
        return e == null ? Optional.empty() : Optional.of(e.version);
    }

    @Override
    public Optional<ContentHash> hashOf(String snapshotId, String objectKey) throws IOException {
        SnapshotFile snap = load(snapshotId);
        Entry e = snap.entries.get(objectKey);
        return (e == null || e.hash == null)
                ? Optional.empty() : Optional.of(ContentHash.of(e.hash));
    }

    @Override
    public boolean exists(String snapshotId) {
        return Files.exists(layout.snapshotPath(snapshotId));
    }

    @Override
    public List<String> listSnapshots() throws IOException {
        if (!Files.exists(layout.snapshotsDir())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(layout.snapshotsDir())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .sorted()
                    .toList();
        }
    }

    @Override
    public void delete(String snapshotId) throws IOException {
        boolean removed = Files.deleteIfExists(layout.snapshotPath(snapshotId));
        if (!removed) {
            throw new SnapshotNotFoundException("snapshot not found: " + snapshotId);
        }
        log.info("[snapshot] DELETE id={}", snapshotId);
    }

    @Override
    public List<ContentHash> referencedHashes(String snapshotId) throws IOException {
        SnapshotFile snap = load(snapshotId);
        List<ContentHash> hashes = new ArrayList<>();
        for (Entry e : snap.entries.values()) {
            if (e.hash != null) {
                hashes.add(ContentHash.of(e.hash));
            }
        }
        return List.copyOf(hashes);
    }

    private SnapshotFile load(String snapshotId) throws IOException {
        Path path = layout.snapshotPath(snapshotId);
        if (!Files.exists(path)) {
            throw new SnapshotNotFoundException("snapshot not found: " + snapshotId);
        }
        return JsonCodec.read(Files.readAllBytes(path), SnapshotFile.class);
    }

    /** 快照文件 JSON。 */
    public static final class SnapshotFile {
        @JsonProperty("snapshotId")
        public String snapshotId;
        @JsonProperty("createdAt")
        public long createdAt;
        @JsonProperty("entries")
        public Map<String, Entry> entries = new HashMap<>();

        public SnapshotFile() {
        }

        public SnapshotFile(String snapshotId, long createdAt, Map<String, Entry> entries) {
            this.snapshotId = snapshotId;
            this.createdAt = createdAt;
            this.entries = entries;
        }
    }

    /** 单个对象在快照中的固定版本。 */
    public static final class Entry {
        @JsonProperty("version")
        public long version;
        @JsonProperty("hash")
        public String hash;

        public Entry() {
        }

        public Entry(long version, String hash) {
            this.version = version;
            this.hash = hash;
        }
    }
}
