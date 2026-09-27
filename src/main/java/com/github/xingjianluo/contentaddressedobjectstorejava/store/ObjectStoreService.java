package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.exception.ChecksumMismatchException;
import com.github.xingjianluo.contentaddressedobjectstorejava.exception.ContentCorruptedException;
import com.github.xingjianluo.contentaddressedobjectstorejava.exception.ObjectNotFoundException;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.GcResult;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地内容寻址对象存储门面。
 *
 * <p>组合 {@link BlobStore}（去重内容）、{@link VersionStore}（版本与条件写）、
 * {@link SnapshotStore}（快照保留）、{@link PendingWriteJournal}（在写引用）与
 * {@link GarbageCollector}（空间回收），并用 {@link StoreConcurrency} 保证并发正确性。</p>
 *
 * <h3>失败原因区分</h3>
 * <ul>
 *   <li>{@link ObjectNotFoundException}：对象/版本/快照不存在；</li>
 *   <li>{@link ChecksumMismatchException}：版本元数据登记哈希与内容寻址 ID 不一致
 *       （元数据与内容对不上）；</li>
 *   <li>{@link ContentCorruptedException}：blob 文件缺失/不可读，或实算哈希与文件名不一致
 *       （物理损坏）；</li>
 *   <li>{@link com.github.xingjianluo.contentaddressedobjectstorejava.exception.VersionConflictException}：
 *       条件写入版本不匹配。</li>
 * </ul>
 */
public class ObjectStoreService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreService.class);

    private final StoreLayout layout;
    private final StoreConcurrency concurrency;
    private BlobStore blobs;
    private VersionStore versions;
    private SnapshotStore snapshots;
    private PendingWriteJournal journal;
    private GarbageCollector gc;

    public ObjectStoreService(Path rootDir) {
        this.layout = new StoreLayout(rootDir);
        this.concurrency = new StoreConcurrency();
    }

    /** 初始化（或打开）存储。 */
    public void init() throws IOException {
        this.blobs = new FileBlobStore(layout);
        this.versions = new FileVersionStore(layout);
        this.snapshots = new FileSnapshotStore(layout);
        this.journal = new FilePendingWriteJournal(layout);
        this.gc = new GarbageCollector(blobs, versions, snapshots, journal);
        blobs.init();
        versions.init();
        snapshots.init();
        journal.init();
        log.info("[store] initialized at {}", layout.root());
    }

    /** 无条件写入：对象不存在则创建（v0），否则追加新版本。 */
    public VersionInfo put(String objectKey, byte[] content) throws IOException {
        long expected = versions.head(objectKey).map(VersionInfo::version).orElse(-1L);
        return putIfVersion(objectKey, expected, content);
    }

    /**
     * 条件写入：仅当对象当前版本 == expectedVersion 时原子生效。
     * 新对象传 -1。
     */
    public VersionInfo putIfVersion(String objectKey, long expectedVersion, byte[] content)
            throws IOException {
        // 整个多阶段写入持读锁：阻止 GC 在中途运行，保证“写入中引用”被保留
        concurrency.acquireRead();
        String entryId = null;
        boolean committed = false;
        BlobStore.StagedBlob staged = null;
        try {
            staged = blobs.stage(content);
            // 先登记在写引用，再发布内容：登记后 GC（若排队）必保留该哈希
            entryId = journal.begin(objectKey, staged.hash());
            blobs.commit(staged);

            final long expected = expectedVersion;
            final BlobStore.StagedBlob s = staged;
            VersionInfo info = concurrency.withCommit(() ->
                    versions.commitVersion(objectKey, expected, s.hash(), s.length()));
            committed = true;
            log.info("[store] PUT ok object='{}' v{} hash={} expected={}",
                    objectKey, info.version(), info.hash().value(), expectedVersion);
            return info;
        } finally {
            if (entryId != null) {
                if (committed) {
                    journal.complete(entryId);
                } else {
                    journal.abort(entryId);
                    if (staged != null) {
                        blobs.abort(staged);
                    }
                }
            }
            concurrency.releaseRead();
        }
    }

    /** 读取对象当前版本。 */
    public byte[] read(String objectKey) throws IOException {
        VersionInfo head = head(objectKey);
        return readByVersion(objectKey, head);
    }

    /** 读取指定历史版本。 */
    public byte[] readVersion(String objectKey, long version) throws IOException {
        VersionInfo info = versions.version(objectKey, version)
                .orElseThrow(() -> new ObjectNotFoundException(
                        "version not found: '" + objectKey + "' v" + version));
        return readByVersion(objectKey, info);
    }

    public VersionInfo head(String objectKey) throws IOException {
        return versions.head(objectKey)
                .orElseThrow(() -> new ObjectNotFoundException(
                        "object not found: '" + objectKey + "'"));
    }

    public List<VersionInfo> history(String objectKey) throws IOException {
        List<VersionInfo> h = versions.history(objectKey);
        if (h.isEmpty()) {
            throw new ObjectNotFoundException("object not found: '" + objectKey + "'");
        }
        return h;
    }

    public List<String> listObjects() throws IOException {
        return versions.listObjects();
    }

    /** 为当前所有对象的当前版本建立快照（持读锁保证看到一致的当前版本集合）。 */
    public String createSnapshot() throws IOException {
        concurrency.acquireRead();
        try {
            Map<String, Long> objectVersions = new LinkedHashMap<>();
            Map<String, ContentHash> objectHashes = new LinkedHashMap<>();
            for (String key : versions.listObjects()) {
                VersionInfo head = versions.head(key)
                        .orElseThrow(() -> new ObjectNotFoundException(
                                "object vanished during snapshot: " + key));
                objectVersions.put(key, head.version());
                objectHashes.put(key, head.hash());
            }
            String id = snapshots.create(objectVersions, objectHashes);
            log.info("[store] SNAPSHOT created id={} objects={}", id, objectVersions.size());
            return id;
        } finally {
            concurrency.releaseRead();
        }
    }

    /** 读取快照中某对象在快照时刻固定版本的内容。 */
    public byte[] readSnapshot(String snapshotId, String objectKey) throws IOException {
        if (!snapshots.exists(snapshotId)) {
            throw new com.github.xingjianluo.contentaddressedobjectstorejava.exception
                    .SnapshotNotFoundException("snapshot not found: " + snapshotId);
        }
        long version = snapshots.versionOf(snapshotId, objectKey)
                .orElseThrow(() -> new ObjectNotFoundException(
                        "object '" + objectKey + "' is not in snapshot " + snapshotId));
        ContentHash snapshotHash = snapshots.hashOf(snapshotId, objectKey)
                .orElseThrow(() -> new ObjectNotFoundException(
                        "missing hash mapping in snapshot " + snapshotId));

        VersionInfo info = versions.version(objectKey, version)
                .orElseThrow(() -> new ObjectNotFoundException(
                        "history version lost: '" + objectKey + "' v" + version));

        // 快照固定的哈希必须仍等于历史版本记录的哈希
        if (!info.hash().equals(snapshotHash)) {
            throw new ChecksumMismatchException(snapshotHash, info.hash().value());
        }
        return readByVersion(objectKey, info);
    }

    public List<String> listSnapshots() throws IOException {
        return snapshots.listSnapshots();
    }

    public void deleteSnapshot(String snapshotId) throws IOException {
        snapshots.delete(snapshotId);
    }

    /** 执行空间回收（与一切读/写互斥）。 */
    public GcResult garbageCollect() throws IOException {
        concurrency.acquireWrite();
        try {
            log.info("[store] GC begin (exclusive)");
            GcResult result = gc.collect();
            log.info("[store] GC done removed={} reclaimed={} bytes {} -> {}",
                    result.removedHashes().size(), result.bytesReclaimed(),
                    result.bytesBeforeGc(), result.bytesAfterGc());
            return result;
        } finally {
            concurrency.releaseWrite();
        }
    }

    /** 内容目录当前物理占用字节。 */
    public long physicalContentBytes() throws IOException {
        return blobs.totalCommittedBytes();
    }

    /** 存储根目录（测试/运维定位用）。 */
    public Path rootDir() {
        return layout.root();
    }

    /** 当前所有 GC 根引用的哈希（保留判定依据）。 */
    public Set<ContentHash> referencedHashes() throws IOException {
        return gc.collectReferencedHashes();
    }

    /**
     * 按版本记录读取并做两级校验，失败原因严格可区分：
     * 1) 物理层：内容寻址文件缺失/不可读，或实算哈希 != 文件名
     *    => {@link ContentCorruptedException}；
     * 2) 元数据层：版本记录登记哈希 != 内容寻址文件真实哈希，或登记长度不符
     *    => {@link ChecksumMismatchException}。
     *
     * <p>顺序很重要：先确认内容文件物理完好（得到真实内容哈希），再与元数据比对。
     * 这样元数据登记了一个“不存在的哈希”时会落到元数据校验失败，
     * 而不会被误报成物理文件缺失。</p>
     */
    private byte[] readByVersion(String objectKey, VersionInfo info) throws IOException {
        ContentHash registered = info.hash();
        concurrency.acquireRead();
        try {
            // 第一步：按登记哈希定位内容文件。文件缺失属于物理损坏。
            if (!blobs.exists(registered)) {
                log.warn("[store] blob missing for registered hash={} object='{}' v{}",
                        registered.value(), objectKey, info.version());
                throw new ContentCorruptedException(registered,
                        "blob file missing for registered content id");
            }

            // 第二步：读取并校验物理完整性（实算哈希必须等于文件名）
            byte[] bytes = blobs.readVerified(registered);

            // 第三步：与元数据登记核对。正常路径下二者相等；若读到的物理内容
            // 与登记哈希不符（哈希自洽但被掉换），在此暴露为校验和不匹配。
            ContentHash actual = Hashing.sha256(bytes);
            if (!actual.equals(registered)) {
                log.warn("[store] checksum mismatch object='{}' v{} registered={} actual={}",
                        objectKey, info.version(), registered.value(), actual.value());
                throw new ChecksumMismatchException(registered, actual.value());
            }
            // 哈希一致时长度必然一致；长度校验作为元数据一致性的二次防线
            if (bytes.length != info.length()) {
                log.warn("[store] length mismatch object='{}' v{} registered={} actual={}",
                        objectKey, info.version(), info.length(), bytes.length);
                throw new ChecksumMismatchException(registered,
                        "length " + bytes.length);
            }
            log.debug("[store] READ ok object='{}' v{} hash={} bytes={}",
                    objectKey, info.version(), registered.value(), bytes.length);
            return bytes;
        } finally {
            concurrency.releaseRead();
        }
    }
}
