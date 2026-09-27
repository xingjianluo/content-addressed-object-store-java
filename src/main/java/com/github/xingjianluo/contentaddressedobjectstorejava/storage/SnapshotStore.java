package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable point-in-time snapshots. A snapshot captures each object's current
 * version number under the {@link ObjectStore} write lock, so it represents a
 * single consistent state that no concurrent write can straddle.
 *
 * <p>GC treats every blob referenced by a retained snapshot as live: deleting
 * or overwriting objects therefore never reclaims content a snapshot can still
 * address.</p>
 */
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    private final ObjectStore objects;
    private final MetadataStore metadata;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EntryRecord(long version, String contentId, long size) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SnapshotRecord(String snapshotId, String name, String createdAt,
                          Map<String, EntryRecord> versions) {
    }

    public SnapshotStore(ObjectStore objects, Path rootDir) {
        this.objects = objects;
        this.metadata = new MetadataStore(rootDir);
        loadFromDisk();
    }

    private void loadFromDisk() {
        for (Path file : metadata.listSnapshotFiles()) {
            try {
                SnapshotRecord rec = metadata.readJson(file, SnapshotRecord.class);
                Map<String, Snapshot.Entry> versions = new LinkedHashMap<>();
                for (Map.Entry<String, EntryRecord> e : rec.versions().entrySet()) {
                    versions.put(e.getKey(), new Snapshot.Entry(e.getValue().version(),
                            ContentId.of(e.getValue().contentId()), e.getValue().size()));
                }
                snapshots.put(rec.snapshotId(),
                        new Snapshot(rec.snapshotId(), Instant.parse(rec.createdAt()),
                                Map.copyOf(versions)));
            } catch (IOException e) {
                throw new IllegalStateException("corrupt snapshot metadata file: " + file, e);
            }
        }
        log.info("SnapshotStore loaded {} snapshot(s) from disk", snapshots.size());
    }

    /** Capture current (key -> version) state atomically with respect to writes. */
    public Snapshot create(String name) {
        objects.lock.writeLock().lock();
        try {
            String id = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
            Map<String, Snapshot.Entry> versions = new TreeMap<>();
            Map<String, EntryRecord> records = new TreeMap<>();
            for (Map.Entry<String, List<ObjectVersion>> e : objects.rawView().entrySet()) {
                ObjectVersion head = e.getValue().get(e.getValue().size() - 1);
                versions.put(e.getKey(),
                        new Snapshot.Entry(head.version(), head.contentId(), head.size()));
                records.put(e.getKey(), new EntryRecord(
                        head.version(), head.contentId().sha256Hex(), head.size()));
            }
            Snapshot snapshot = new Snapshot(id, Instant.now(), Map.copyOf(versions));
            metadata.writeJson(metadata.snapshotFile(id),
                    new SnapshotRecord(id, name, snapshot.createdAt().toString(), records));
            snapshots.put(id, snapshot);
            log.info("snapshot CREATED id={} name='{}' objects={}", id, name, versions.size());
            return snapshot;
        } finally {
            objects.lock.writeLock().unlock();
        }
    }

    public Snapshot get(String snapshotId) throws StorageException {
        Snapshot s = snapshots.get(snapshotId);
        if (s == null) {
            throw new ObjectNotFoundException("snapshot not found: id=" + snapshotId);
        }
        return s;
    }

    public List<Snapshot> list() {
        List<Snapshot> out = new ArrayList<>(snapshots.values());
        out.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        return out;
    }

    public boolean delete(String snapshotId) {
        Snapshot removed = snapshots.remove(snapshotId);
        if (removed == null) {
            log.info("snapshot delete id={} result=absent", snapshotId);
            return false;
        }
        try {
            metadata.deleteSnapshotFile(snapshotId);
        } catch (IOException e) {
            snapshots.put(snapshotId, removed);
            throw new UncheckedIOException("failed deleting snapshot file " + snapshotId, e);
        }
        log.info("snapshot DELETED id={} (its blobs become GC-eligible if otherwise unreferenced)",
                snapshotId);
        return true;
    }

    /** All blob ids pinned directly by retained snapshot manifests. */
    public Set<ContentId> allReferencedContentIds() {
        Set<ContentId> ids = new java.util.HashSet<>();
        for (Snapshot s : snapshots.values()) {
            for (Snapshot.Entry e : s.versions().values()) {
                ids.add(e.contentId());
            }
        }
        return ids;
    }
}
