package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.exception.VersionConflictException;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.VersionInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 版本元数据的文件实现。
 *
 * <p>每个对象一个目录：v0.json、v1.json ... 是不可变版本记录（先写）；
 * meta.json 是“当前版本指针”（原子写，最后推进）。提交顺序：</p>
 * <ol>
 *   <li>读 meta.json 得到实际当前版本，与 expectedVersion 比较，不符直接拒绝；</li>
 *   <li>原子写不可变的 vN.json（历史永不被覆盖）；</li>
 *   <li>原子推进 meta.json 指向新版本——读线程要么看到旧指针，要么看到新指针，
 *       绝不会读到中间状态。</li>
 * </ol>
 * <p>本类的 {@link #commitVersion} 必须在 {@link StoreConcurrency} 写锁保护下调用，
 * 从而保证“检查 + 推进”的原子性。</p>
 */
public class FileVersionStore implements VersionStore {

    private static final Logger log = LoggerFactory.getLogger(FileVersionStore.class);
    private static final Pattern VERSION_FILE = Pattern.compile("v(\\d+)\\.json");

    private final StoreLayout layout;

    public FileVersionStore(StoreLayout layout) {
        this.layout = layout;
    }

    @Override
    public void init() throws IOException {
        Files.createDirectories(layout.objectsDir());
    }

    @Override
    public Optional<VersionInfo> head(String objectKey) throws IOException {
        Path meta = layout.objectMetaPath(objectKey);
        if (!Files.exists(meta)) {
            return Optional.empty();
        }
        Meta m = JsonCodec.read(Files.readAllBytes(meta), Meta.class);
        return Optional.of(toVersionInfo(objectKey, m));
    }

    @Override
    public Optional<VersionInfo> version(String objectKey, long version) throws IOException {
        Path record = layout.versionRecordPath(objectKey, version);
        if (!Files.exists(record)) {
            return Optional.empty();
        }
        return Optional.of(readRecord(objectKey, version, record));
    }

    @Override
    public List<VersionInfo> history(String objectKey) throws IOException {
        Path dir = layout.objectDir(objectKey);
        if (!Files.exists(dir)) {
            return List.of();
        }
        List<VersionInfo> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> records = files
                    .filter(p -> VERSION_FILE.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingLong(this::versionNumberOf))
                    .toList();
            for (Path record : records) {
                long v = versionNumberOf(record);
                result.add(readRecord(objectKey, v, record));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public List<String> listObjects() throws IOException {
        if (!Files.exists(layout.objectsDir())) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(layout.objectsDir())) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    @Override
    public VersionInfo commitVersion(String objectKey, long expectedVersion,
                                     ContentHash hash, long length) throws IOException {
        // 第一步：条件检查（调用方持写锁，保证与其它提交/GC 互斥）
        Optional<VersionInfo> current = head(objectKey);
        long actualVersion = current.map(VersionInfo::version).orElse(-1L);
        if (actualVersion != expectedVersion) {
            log.warn("[version] CONFLICT object='{}' expected={} actual={}",
                    objectKey, expectedVersion, actualVersion);
            throw new VersionConflictException(objectKey, expectedVersion, actualVersion);
        }

        long newVersion = actualVersion + 1;
        long now = System.currentTimeMillis();

        // 第二步：写不可变版本记录（历史文件从不被覆盖）
        VersionInfo info = new VersionInfo(objectKey, newVersion, hash, length, now);
        Path recordPath = layout.versionRecordPath(objectKey, newVersion);
        if (Files.exists(recordPath)) {
            // 理论上不会发生（写锁串行），显式拒绝任何覆盖历史的可能
            throw new VersionConflictException(objectKey, expectedVersion, actualVersion);
        }
        Record record = new Record(newVersion, hash.value(), length, now);
        StoreLayout.writeAtomically(recordPath, JsonCodec.write(record));

        // 第三步：原子推进当前版本指针
        Meta meta = new Meta(newVersion, hash.value(), length, now);
        StoreLayout.writeAtomically(layout.objectMetaPath(objectKey), JsonCodec.write(meta));

        log.info("[version] COMMIT object='{}' v{} hash={} (expected={} matched)",
                objectKey, newVersion, hash.value(), expectedVersion);
        return info;
    }

    private VersionInfo readRecord(String objectKey, long v, Path record) throws IOException {
        Record r = JsonCodec.read(Files.readAllBytes(record), Record.class);
        return new VersionInfo(objectKey, v, ContentHash.of(r.hash), r.length, r.createdAt);
    }

    private VersionInfo toVersionInfo(String objectKey, Meta m) {
        return new VersionInfo(objectKey, m.version, ContentHash.of(m.hash), m.length, m.createdAt);
    }

    private long versionNumberOf(Path p) {
        Matcher matcher = VERSION_FILE.matcher(p.getFileName().toString());
        if (matcher.matches()) {
            return Long.parseLong(matcher.group(1));
        }
        return -1;
    }

    /** 当前版本指针 JSON。 */
    public static final class Meta {
        @JsonProperty("version")
        public long version;
        @JsonProperty("hash")
        public String hash;
        @JsonProperty("length")
        public long length;
        @JsonProperty("updatedAt")
        public long createdAt;

        public Meta() {
        }

        public Meta(long version, String hash, long length, long createdAt) {
            this.version = version;
            this.hash = hash;
            this.length = length;
            this.createdAt = createdAt;
        }
    }

    /** 不可变版本记录 JSON。 */
    public static final class Record {
        @JsonProperty("version")
        public long version;
        @JsonProperty("hash")
        public String hash;
        @JsonProperty("length")
        public long length;
        @JsonProperty("createdAt")
        public long createdAt;

        public Record() {
        }

        public Record(long version, String hash, long length, long createdAt) {
            this.version = version;
            this.hash = hash;
            this.length = length;
            this.createdAt = createdAt;
        }
    }
}
